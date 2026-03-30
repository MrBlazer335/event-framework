package net.eventframework.annotation;

import com.google.auto.service.AutoService;
import com.palantir.javapoet.*;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.*;
import java.util.*;

@AutoService(Processor.class)
@SupportedSourceVersion(SourceVersion.RELEASE_21)
@SupportedAnnotationTypes({"net.eventframework.annotation.FabricEvent", "net.eventframework.annotation.HandleEvent"})
public class AnnotationProcessor extends AbstractProcessor {

    private final List<String> generatedMixinClassNames = new ArrayList<>();

    // Cached directories — resolved once on first call, reused for all events.
    // Prevents race conditions caused by generatedMixinClassNames being populated
    // after file generation, which caused the second event to go to the wrong directory.
    private File   cachedClientSourcesDir = null;
    private String cachedClassOutputPath  = null;

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        // Group valid methods by their declaring class so we can generate
        // one registrar per class containing all event registrations
        Map<TypeElement, List<ExecutableElement>> methodsByClass = new LinkedHashMap<>();

        for (Element element : roundEnv.getElementsAnnotatedWith(FabricEvent.class)) {
            if (element.getKind() != ElementKind.CLASS) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "@FabricEvent can only be applied to classes", element);
                continue;
            }
            TypeElement classElement    = (TypeElement) element;
            FabricEvent fabricEvent     = classElement.getAnnotation(FabricEvent.class);
            TypeMirror  targetClassMirror = getTargetClassMirror(fabricEvent);

            List<ExecutableElement> validMethods = new ArrayList<>();

            for (Element enclosed : classElement.getEnclosedElements()) {
                if (enclosed.getKind() != ElementKind.METHOD) continue;
                if (enclosed.getAnnotation(HandleEvent.class) == null) continue;

                ExecutableElement method = (ExecutableElement) enclosed;
                if (!validateHandleEventMethod(method, targetClassMirror)) continue;

                generateCallbackInterface(classElement, method, targetClassMirror);

                String mixinClassName = generateMixinClass(classElement, method, targetClassMirror);
                if (mixinClassName != null) {
                    generatedMixinClassNames.add(mixinClassName);
                }

                validMethods.add(method);
            }

            if (!validMethods.isEmpty()) {
                methodsByClass.put(classElement, validMethods);
            }
        }

        // Generate one registrar per class — contains all @HandleEvent methods
        for (Map.Entry<TypeElement, List<ExecutableElement>> entry : methodsByClass.entrySet()) {
            TypeMirror targetClassMirror = getTargetClassMirror(
                    entry.getKey().getAnnotation(FabricEvent.class));
            generateRegistrar(entry.getKey(), entry.getValue(), targetClassMirror);
        }

        if (roundEnv.processingOver() && !generatedMixinClassNames.isEmpty()) {
            patchMixinJson();
        }

        return true;
    }

    // Validates that when injectSelf=true, the first parameter type
    // is assignable from the mixin target class.
    // Emits a compile error (red underline in IDE) if the types don't match.
    private boolean validateHandleEventMethod(
            ExecutableElement method,
            TypeMirror targetClassMirror
    ) {
        HandleEvent handleEvent = method.getAnnotation(HandleEvent.class);
        if (!handleEvent.injectSelf()) return true;

        List<? extends VariableElement> params = method.getParameters();

        if (params.isEmpty()) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "@HandleEvent with injectSelf=true requires at least one parameter — " +
                            "the first parameter must be the target class type: " +
                            targetClassMirror.toString(),
                    method
            );
            return false;
        }

        TypeMirror firstParamType = params.get(0).asType();
        boolean isAssignable = processingEnv.getTypeUtils()
                .isAssignable(targetClassMirror, firstParamType);

        if (!isAssignable) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "@HandleEvent injectSelf=true — first parameter must be assignable from " +
                            "the target class '" + targetClassMirror + "'. " +
                            "Found '" + firstParamType + "' which is not a supertype of the target.",
                    params.get(0)
            );
            return false;
        }

        return true;
    }

    // ─────────────────────────────────────────────────────────────────
    // MIXIN.JSON PATCHING
    // ─────────────────────────────────────────────────────────────────
    private void patchMixinJson() {
        cleanFrameworkMixinJson();

        File mixinJsonFile = findMixinJson();

        String existingContent = "";
        if (mixinJsonFile != null && mixinJsonFile.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(mixinJsonFile))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line).append("\n");
                existingContent = sb.toString();
                processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                        "mixin config found at: " + mixinJsonFile.getAbsolutePath());
            } catch (IOException e) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                        "Could not read mixin config: " + e.getMessage());
            }
        } else {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "No mixin config found — will create from scratch.");
        }

        String updatedJson = existingContent.isBlank()
                ? buildMixinJsonFromScratch()
                : injectIntoExistingMixinJson(existingContent);

        if (mixinJsonFile != null) {
            writeToFileSystem(mixinJsonFile, updatedJson);
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // FRAMEWORK MIXIN CONFIG CLEANUP
    // ─────────────────────────────────────────────────────────────────
    private void cleanFrameworkMixinJson() {
        try {
            String classOutputPath = getClassOutputPath();
            if (classOutputPath == null) return;

            File dir = new File(classOutputPath);

            for (int i = 0; i < 10; i++) {
                if (dir == null) break;

                boolean hasGradle = new File(dir, "build.gradle").exists()
                        || new File(dir, "build.gradle.kts").exists();
                boolean hasMaven  = new File(dir, "pom.xml").exists();
                boolean hasSrc    = new File(dir, "src").exists();

                if ((hasGradle || hasMaven) && hasSrc) {
                    if (!isMixinPackageBelongingToProject(dir)) {
                        File resourcesDir = new File(dir, "src/main/resources");
                        File[] jsonFiles  = resourcesDir.listFiles(
                                f -> f.getName().endsWith(".mixins.json")
                                        || f.getName().equals("mixin.json"));
                        if (jsonFiles != null) {
                            for (File jsonFile : jsonFiles) cleanGeneratedEntriesFrom(jsonFile);
                        }
                    }
                }

                dir = dir.getParentFile();
            }
        } catch (Exception e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "Could not clean framework mixin config: " + e.getMessage());
        }
    }

    private void cleanGeneratedEntriesFrom(File jsonFile) {
        if (!jsonFile.exists()) return;

        try (BufferedReader reader = new BufferedReader(new FileReader(jsonFile))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append("\n");
            String content = sb.toString();
            String cleaned = content;

            for (String fullName : generatedMixinClassNames) {
                String simpleName = fullName.substring(fullName.lastIndexOf('.') + 1);
                if (!content.contains("\"" + simpleName + "\"")) continue;

                cleaned = cleaned
                        .replaceAll(",\\s*\"" + simpleName + "\"", "")
                        .replaceAll("\"" + simpleName + "\"\\s*,", "")
                        .replaceAll("\"" + simpleName + "\"", "");

                processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                        "Removed stale entry '" + simpleName + "' from " + jsonFile.getName());
            }

            if (!cleaned.equals(content)) {
                try (Writer writer = new FileWriter(jsonFile)) {
                    writer.write(cleaned);
                }
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "Could not clean stale entries from " + jsonFile.getName()
                            + ": " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // LOCATING THE CLIENT MOD'S MIXIN CONFIG
    // ─────────────────────────────────────────────────────────────────
    private File findMixinJson() {
        try {
            String classOutputPath = getClassOutputPath();
            if (classOutputPath == null) return null;

            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "CLASS_OUTPUT detected at: " + classOutputPath);

            File dir = new File(classOutputPath);
            for (int i = 0; i < 10; i++) {
                if (dir == null) break;

                boolean hasGradle = new File(dir, "build.gradle").exists()
                        || new File(dir, "build.gradle.kts").exists();
                boolean hasMaven  = new File(dir, "pom.xml").exists();
                boolean hasSrc    = new File(dir, "src").exists();

                if ((hasGradle || hasMaven) && hasSrc) {
                    File resourcesDir  = new File(dir, "src/main/resources");
                    File fabricModJson = new File(resourcesDir, "fabric.mod.json");

                    if (!fabricModJson.exists()) {
                        dir = dir.getParentFile();
                        continue;
                    }

                    if (!isMixinPackageBelongingToProject(dir)) {
                        dir = dir.getParentFile();
                        continue;
                    }

                    String modId = readModIdFromFabricJson(resourcesDir);
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                            "Detected mod id: " + modId);

                    String[] candidates = {
                            modId + ".mixins.json",
                            modId + "-common.mixins.json",
                            "mixin.json"
                    };

                    for (String candidate : candidates) {
                        File f = new File(resourcesDir, candidate);
                        if (f.exists()) {
                            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                                    "Found existing mixin config: " + f.getName());
                            return f;
                        }
                    }

                    resourcesDir.mkdirs();
                    File newFile = new File(resourcesDir, modId + ".mixins.json");
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                            "Will create new mixin config: " + newFile.getName());
                    return newFile;
                }

                dir = dir.getParentFile();
            }

            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "Could not locate project root from: " + classOutputPath);
            return null;

        } catch (Exception e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "Could not resolve CLASS_OUTPUT path: " + e.getMessage());
            return null;
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // CLIENT SOURCES DIRECTORY
    // Resolved once and cached. Uses CLASS_OUTPUT path to identify the
    // correct project — no longer depends on generatedMixinClassNames.
    // ─────────────────────────────────────────────────────────────────
    private File findClientSourcesDir() {
        if (cachedClientSourcesDir != null) return cachedClientSourcesDir;

        try {
            String classOutputPath = getClassOutputPath();
            if (classOutputPath == null) return null;

            File dir = new File(classOutputPath);

            for (int i = 0; i < 10; i++) {
                if (dir == null) break;

                boolean hasGradle = new File(dir, "build.gradle").exists()
                        || new File(dir, "build.gradle.kts").exists();
                boolean hasMaven  = new File(dir, "pom.xml").exists();
                boolean hasSrc    = new File(dir, "src").exists();

                if ((hasGradle || hasMaven) && hasSrc) {
                    File fabricModJson = new File(dir, "src/main/resources/fabric.mod.json");

                    if (!fabricModJson.exists()) {
                        dir = dir.getParentFile();
                        continue;
                    }

                    if (isMixinPackageBelongingToProject(dir)) {
                        File sourcesDir = new File(dir, "src/main/java");
                        if (sourcesDir.exists()) {
                            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                                    "Client sources dir found: " + sourcesDir.getAbsolutePath());
                            cachedClientSourcesDir = sourcesDir;
                            return cachedClientSourcesDir;
                        }
                    }
                }

                dir = dir.getParentFile();
            }
        } catch (Exception e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "Could not find client sources dir: " + e.getMessage());
        }
        return null;
    }

    // Returns true if CLASS_OUTPUT is inside the given project's build directory.
    // Replaces the old package-name-based check which depended on
    // generatedMixinClassNames being populated before file generation.
    private boolean isMixinPackageBelongingToProject(File projectRoot) {
        String classOutputPath = getClassOutputPath();
        if (classOutputPath == null) return false;

        String normalizedOutput  = classOutputPath.replace('\\', '/');
        String normalizedProject = projectRoot.getAbsolutePath().replace('\\', '/');

        boolean belongs = normalizedOutput.startsWith(normalizedProject);

        processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                "Checking project: " + projectRoot.getAbsolutePath()
                        + " → " + (belongs ? "MATCH" : "no match"));

        return belongs;
    }

    // Resolves and caches the CLASS_OUTPUT path.
    private String getClassOutputPath() {
        if (cachedClassOutputPath != null) return cachedClassOutputPath;

        try {
            FileObject dummy = processingEnv.getFiler()
                    .getResource(StandardLocation.CLASS_OUTPUT, "", "dummy_probe.tmp");
            cachedClassOutputPath = new File(dummy.toUri()).getParentFile().getAbsolutePath();
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "Could not resolve CLASS_OUTPUT: " + e.getMessage());
        }

        return cachedClassOutputPath;
    }

    private String readModIdFromFabricJson(File resourcesDir) {
        File fabricModJson = new File(resourcesDir, "fabric.mod.json");

        if (!fabricModJson.exists()) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "fabric.mod.json not found — falling back to package-derived mod id.");
            return deriveModIdFromPackage();
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(fabricModJson))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            String json = sb.toString();

            int idIndex = json.indexOf("\"id\"");
            if (idIndex == -1) return deriveModIdFromPackage();

            int colonIndex  = json.indexOf(':', idIndex);
            int firstQuote  = json.indexOf('"', colonIndex);
            int secondQuote = json.indexOf('"', firstQuote + 1);

            if (firstQuote == -1 || secondQuote == -1) return deriveModIdFromPackage();

            String modId = json.substring(firstQuote + 1, secondQuote).trim();
            return modId.isBlank() ? deriveModIdFromPackage() : modId;

        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "Could not read fabric.mod.json: " + e.getMessage());
            return deriveModIdFromPackage();
        }
    }

    private String deriveModIdFromPackage() {
        if (!generatedMixinClassNames.isEmpty()) {
            String[] parts = generatedMixinClassNames.get(0).split("\\.");
            return parts.length >= 3 ? parts[2] : (parts.length >= 2 ? parts[1] : "mod");
        }
        return "mod";
    }

    private void writeFileToClientSources(JavaFile javaFile) {
        File clientSourcesDir = findClientSourcesDir();

        if (clientSourcesDir != null) {
            try {
                javaFile.writeTo(clientSourcesDir);
                processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                        "Generated file written to: " + clientSourcesDir.getAbsolutePath());
            } catch (IOException e) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                        "Could not write to client sources, falling back to Filer: "
                                + e.getMessage());
                writeFileViaFiler(javaFile);
            }
        } else {
            writeFileViaFiler(javaFile);
        }
    }

    private void writeFileViaFiler(JavaFile javaFile) {
        try {
            javaFile.writeTo(processingEnv.getFiler());
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Failed to write generated file: " + e.getMessage());
        }
    }

    private void writeToFileSystem(File file, String content) {
        try (Writer writer = new FileWriter(file)) {
            writer.write(content);
            processingEnv.getMessager().printMessage(Diagnostic.Kind.NOTE,
                    "mixin config written to: " + file.getAbsolutePath());
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Failed to write mixin config: " + e.getMessage());
        }
    }

    private String injectIntoExistingMixinJson(String json) {
        StringBuilder entriesToAdd = new StringBuilder();

        for (String fullName : generatedMixinClassNames) {
            String simpleName = fullName.substring(fullName.lastIndexOf('.') + 1);
            if (!json.contains("\"" + simpleName + "\"")) {
                entriesToAdd.append("\"").append(simpleName).append("\"");
            }
        }

        if (entriesToAdd.isEmpty()) return json;

        String entry = entriesToAdd.toString();

        if (json.contains("\"mixins\": []") || json.contains("\"mixins\":[]")) {
            return json
                    .replace("\"mixins\": []", "\"mixins\": [\n       " + entry + "\n    ]")
                    .replace("\"mixins\":[]",   "\"mixins\": [\n       " + entry + "\n    ]");

        } else if (json.contains("\"mixins\"")) {
            int mixinsIndex    = json.indexOf("\"mixins\"");
            int closingBracket = json.indexOf(']', mixinsIndex);
            int lastQuote      = json.lastIndexOf('"', closingBracket);

            int lineStart = json.lastIndexOf('\n', lastQuote);
            String indentation = lineStart != -1
                    ? json.substring(lineStart + 1, json.indexOf('"', lineStart + 1))
                    : "       ";

            return json.substring(0, lastQuote + 1)
                    + ",\n" + indentation + entry + "\n    "
                    + json.substring(closingBracket);
        } else {
            String newArray = "\"mixins\": [\n       " + entry + "\n    ]";
            int lastBrace = json.lastIndexOf('}');
            return json.substring(0, lastBrace) + ",\n    " + newArray + "\n"
                    + json.substring(lastBrace);
        }
    }

    private String buildMixinJsonFromScratch() {
        String firstFull    = generatedMixinClassNames.get(0);
        String mixinPackage = firstFull.substring(0, firstFull.lastIndexOf('.'));

        StringBuilder mixinArray = new StringBuilder();
        for (int i = 0; i < generatedMixinClassNames.size(); i++) {
            String full   = generatedMixinClassNames.get(i);
            String simple = full.substring(full.lastIndexOf('.') + 1);
            if (i > 0) mixinArray.append(",\n       ");
            mixinArray.append("\"").append(simple).append("\"");
        }

        return "{\n" +
                "  \"required\": true,\n" +
                "  \"package\": \"" + mixinPackage + "\",\n" +
                "  \"compatibilityLevel\": \"JAVA_21\",\n" +
                "  \"mixins\": [\n" +
                "       " + mixinArray + "\n" +
                "  ],\n" +
                "  \"injectors\": {\n" +
                "    \"defaultRequire\": 1\n" +
                "  }\n" +
                "}";
    }

    // ─────────────────────────────────────────────────────────────────
    // 1. CALLBACK INTERFACE GENERATION
    // ─────────────────────────────────────────────────────────────────
    private void generateCallbackInterface(
            TypeElement classElement,
            ExecutableElement method,
            TypeMirror targetClassMirror
    ) {
        String originalPackage = processingEnv.getElementUtils()
                .getPackageOf(classElement).getQualifiedName().toString();

        String      targetSimpleName = getSimpleName(targetClassMirror);
        HandleEvent handleEvent      = method.getAnnotation(HandleEvent.class);
        String      callbackName     = targetSimpleName + capitalize(handleEvent.nameMethod()) + "Callback";

        ClassName eventClass        = ClassName.get("net.fabricmc.fabric.api.event", "Event");
        ClassName eventFactoryClass = ClassName.get("net.fabricmc.fabric.api.event", "EventFactory");
        ClassName actionResult      = ClassName.get("net.minecraft.util", "ActionResult");

        List<ParameterSpec> params     = new ArrayList<>();
        List<String>        paramNames = new ArrayList<>();

        for (VariableElement param : method.getParameters()) {
            params.add(ParameterSpec.builder(
                    TypeName.get(param.asType()),
                    param.getSimpleName().toString()
            ).build());
            paramNames.add(param.getSimpleName().toString());
        }

        MethodSpec interfaceMethod = MethodSpec.methodBuilder("handle")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .returns(actionResult)
                .addParameters(params)
                .build();

        ClassName selfClass  = ClassName.get(originalPackage + ".callback", callbackName);
        TypeName  eventType  = ParameterizedTypeName.get(eventClass, selfClass);
        String    argsJoined = String.join(", ", paramNames);

        CodeBlock listenerLoop = CodeBlock.builder()
                .beginControlFlow("for ($T listener : listeners)", selfClass)
                .addStatement("$T result = listener.handle($L)", actionResult, argsJoined)
                .beginControlFlow("if (result != $T.PASS)", actionResult)
                .addStatement("return result")
                .endControlFlow()
                .endControlFlow()
                .addStatement("return $T.PASS", actionResult)
                .build();

        CodeBlock eventInitializer = CodeBlock.builder()
                .add("$T.createArrayBacked($T.class,\n", eventFactoryClass, selfClass)
                .indent()
                .add("(listeners) -> ($L) -> {\n", argsJoined)
                .indent()
                .add(listenerLoop)
                .unindent()
                .add("})")
                .unindent()
                .build();

        FieldSpec eventField = FieldSpec.builder(eventType, "EVENT")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
                .initializer(eventInitializer)
                .build();

        TypeSpec callbackInterface = TypeSpec.interfaceBuilder(callbackName)
                .addModifiers(Modifier.PUBLIC)
                .addField(eventField)
                .addMethod(interfaceMethod)
                .build();

        writeFileToClientSources(JavaFile.builder(originalPackage + ".callback", callbackInterface)
                .indent("    ").skipJavaLangImports(true).build());
    }

    // ─────────────────────────────────────────────────────────────────
    // 2. MIXIN CLASS GENERATION
    // ─────────────────────────────────────────────────────────────────
    private String generateMixinClass(
            TypeElement classElement,
            ExecutableElement method,
            TypeMirror targetClassMirror
    ) {
        String originalPackage = processingEnv.getElementUtils()
                .getPackageOf(classElement).getQualifiedName().toString();

        String      targetSimpleName  = getSimpleName(targetClassMirror);
        HandleEvent handleEvent       = method.getAnnotation(HandleEvent.class);
        String      targetMethodName  = handleEvent.nameMethod();
        String      position          = handleEvent.position().getValue();
        boolean     injectSelf        = handleEvent.injectSelf();

        // returnable=true means the target Minecraft method returns a non-void value.
        // Determines whether CallbackInfo or CallbackInfoReturnable is generated.
        boolean targetReturnsVoid = !handleEvent.returnable();

        String    callbackName   = targetSimpleName + capitalize(targetMethodName) + "Callback";
        String    mixinClassName = targetSimpleName + capitalize(targetMethodName) + "Mixin";
        String    mixinPackage   = originalPackage + ".mixin";
        ClassName callbackClass  = ClassName.get(originalPackage + ".callback", callbackName);

        AnnotationSpec atAnnotation = AnnotationSpec.builder(
                        ClassName.get("org.spongepowered.asm.mixin.injection", "At"))
                .addMember("value", "$S", position)
                .build();

        AnnotationSpec injectAnnotation = AnnotationSpec.builder(
                        ClassName.get("org.spongepowered.asm.mixin.injection", "Inject"))
                .addMember("method", "$S", targetMethodName)
                .addMember("at", "$L", atAnnotation)
                .addMember("cancellable", "$L", true)
                .build();

        AnnotationSpec mixinAnnotation = AnnotationSpec.builder(
                        ClassName.get("org.spongepowered.asm.mixin", "Mixin"))
                .addMember("value", "$T.class", targetClassMirror)
                .build();

        ClassName ciClass      = ClassName.get("org.spongepowered.asm.mixin.injection.callback", "CallbackInfo");
        ClassName cirClass     = ClassName.get("org.spongepowered.asm.mixin.injection.callback", "CallbackInfoReturnable");
        ClassName actionResult = ClassName.get("net.minecraft.util", "ActionResult");

        TypeName callbackType = targetReturnsVoid
                ? ClassName.get(ciClass.packageName(), ciClass.simpleName())
                : ParameterizedTypeName.get(cirClass, actionResult);

        List<String>       argNames      = new ArrayList<>();
        MethodSpec.Builder methodBuilder = MethodSpec
                .methodBuilder("on" + capitalize(targetMethodName))
                .addAnnotation(injectAnnotation)
                .addModifiers(Modifier.PRIVATE)
                .returns(void.class);

        List<VariableElement> params      = new ArrayList<>(method.getParameters());
        List<VariableElement> mixinParams = injectSelf ? params.subList(1, params.size()) : params;

        for (VariableElement param : mixinParams) {
            methodBuilder.addParameter(TypeName.get(param.asType()), param.getSimpleName().toString());
            argNames.add(param.getSimpleName().toString());
        }

        methodBuilder.addParameter(callbackType, "ci");

        String argsJoined;
        if (injectSelf && !params.isEmpty()) {
            TypeName selfType = TypeName.get(params.get(0).asType());
            String   selfCast = "(" + selfType + ")(Object) this";

            List<String> allArgs = new ArrayList<>();
            allArgs.add(selfCast);
            allArgs.addAll(argNames);
            argsJoined = String.join(", ", allArgs);
        } else {
            argsJoined = String.join(", ", argNames);
        }

        methodBuilder
                .addStatement("$T result = $T.EVENT.invoker().handle($L)",
                        actionResult, callbackClass, argsJoined)
                .beginControlFlow("if (result == $T.FAIL)", actionResult);

        if (targetReturnsVoid) {
            methodBuilder.addStatement("ci.cancel()");
        } else {
            methodBuilder.addStatement("ci.setReturnValue(result)");
        }

        methodBuilder.endControlFlow();

        TypeSpec mixinClass = TypeSpec.classBuilder(mixinClassName)
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addAnnotation(mixinAnnotation)
                .addMethod(methodBuilder.build())
                .build();

        writeFileToClientSources(JavaFile.builder(mixinPackage, mixinClass)
                .indent("    ").skipJavaLangImports(true).build());

        return mixinPackage + "." + mixinClassName;
    }

    // ─────────────────────────────────────────────────────────────────
    // 3. REGISTRAR CLASS GENERATION
    // One registrar per @FabricEvent class — contains all @HandleEvent methods.
    // ─────────────────────────────────────────────────────────────────
    private void generateRegistrar(
            TypeElement classElement,
            List<ExecutableElement> methods,
            TypeMirror targetClassMirror
    ) {
        String originalPackage   = processingEnv.getElementUtils()
                .getPackageOf(classElement).getQualifiedName().toString();
        String targetSimpleName  = getSimpleName(targetClassMirror);
        String originalClassName = classElement.getSimpleName().toString();
        ClassName actionResult   = ClassName.get("net.minecraft.util", "ActionResult");

        MethodSpec.Builder registerMethod = MethodSpec.methodBuilder("register")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(void.class);

        // Generate one EVENT.register(...) block per @HandleEvent method
        for (ExecutableElement method : methods) {
            HandleEvent handleEvent = method.getAnnotation(HandleEvent.class);
            String      methodName  = method.getSimpleName().toString();
            String      callbackName = targetSimpleName + capitalize(handleEvent.nameMethod()) + "Callback";
            ClassName   callbackClass = ClassName.get(originalPackage + ".callback", callbackName);

            List<String> argNames = new ArrayList<>();
            for (VariableElement param : method.getParameters()) {
                argNames.add(param.getSimpleName().toString());
            }
            String argsJoined = String.join(", ", argNames);

            CodeBlock registerBlock = CodeBlock.builder()
                    .add("$T.EVENT.register(($L) -> {\n", callbackClass, argsJoined)
                    .indent()
                    .addStatement("$T.$L($L)", classElement, methodName, argsJoined)
                    .addStatement("return $T.PASS", actionResult)
                    .unindent()
                    .add("});\n")
                    .build();

            registerMethod.addCode(registerBlock);
        }

        TypeSpec registrarClass = TypeSpec.classBuilder(originalClassName + "Registrar")
                .addModifiers(Modifier.PUBLIC)
                .addMethod(registerMethod.build())
                .build();

        writeFileToClientSources(JavaFile.builder(originalPackage + ".registrar", registrarClass)
                .indent("    ").skipJavaLangImports(true).build());
    }

    // ─────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────

    // Safely extract the TypeMirror from a Class<?> annotation value.
    // Direct access throws MirroredTypeException at compile time —
    // catching it is the standard APT workaround.
    private TypeMirror getTargetClassMirror(FabricEvent annotation) {
        try {
            annotation.value();
        } catch (MirroredTypeException mte) {
            return mte.getTypeMirror();
        }
        return null;
    }

    // Extract the simple class name from a TypeMirror
    private String getSimpleName(TypeMirror mirror) {
        String full = mirror.toString();
        return full.substring(full.lastIndexOf('.') + 1);
    }

    // Capitalizes the first letter of a string
    private String capitalize(String str) {
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }
}