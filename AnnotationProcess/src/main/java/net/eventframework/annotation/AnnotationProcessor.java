package net.eventframework.annotation;

import com.google.auto.service.AutoService;
import com.palantir.javapoet.*;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.type.TypeKind;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.*;
import java.util.*;

@AutoService(Processor.class)
@SupportedSourceVersion(SourceVersion.RELEASE_21)
@SupportedAnnotationTypes({
        "net.eventframework.annotation.FabricEvent",
        "net.eventframework.annotation.HandleEvent"
})
public class AnnotationProcessor extends AbstractProcessor {

    private final List<String> generatedMixinClassNames = new ArrayList<>();

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
            TypeElement classElement      = (TypeElement) element;
            FabricEvent fabricEvent       = classElement.getAnnotation(FabricEvent.class);
            TypeMirror  targetClassMirror = getTargetClassMirror(Objects.requireNonNull(fabricEvent));

            List<ExecutableElement> validMethods = new ArrayList<>();

            for (Element enclosed : classElement.getEnclosedElements()) {
                if (enclosed.getKind() != ElementKind.METHOD) continue;
                if (enclosed.getAnnotation(HandleEvent.class) == null) continue;

                ExecutableElement method = (ExecutableElement) enclosed;
                if (!validateHandleEventMethod(method, targetClassMirror)) continue;
                if (!validateReturnTypeIsActionResult(method)) continue;

                generateCallbackInterface(classElement, method, targetClassMirror);

                String mixinClassName =
                        generateMixinClass(classElement, method, targetClassMirror);
                if (mixinClassName != null) {
                    generatedMixinClassNames.add(mixinClassName);
                }

                validMethods.add(method);
            }

            if (!validMethods.isEmpty()) {
                methodsByClass.put(classElement, validMethods);
            }
        }

        for (Map.Entry<TypeElement,
                List<ExecutableElement>> entry : methodsByClass.entrySet()) {
            TypeMirror targetClassMirror = getTargetClassMirror(
                    Objects.requireNonNull(entry.getKey().getAnnotation(FabricEvent.class)));
            generateRegistrar(entry.getKey(), entry.getValue(), targetClassMirror);
        }

        if (roundEnv.processingOver() && !generatedMixinClassNames.isEmpty()) {
            patchMixinJson();
        }
        return true;
    }

    /* ----------------------------------------------------------------------- */
    private boolean validateHandleEventMethod(ExecutableElement method,
                                              TypeMirror     targetClassMirror) {
        Set<Modifier> modifiers = method.getModifiers();

        if (!modifiers.contains(Modifier.STATIC)) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR, "@HandleEvent methods must be static", method);
            return false;
        }

        if (!modifiers.contains(Modifier.PUBLIC)) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "@HandleEvent methods must be public",
                    method
            );
            return false;
        }

        HandleEvent handleEvent = method.getAnnotation(HandleEvent.class);
        if (handleEvent == null) return false;

        String targetMethodName =
                handleEvent.nameMethod();
        TypeElement targetClassElement =
                (TypeElement) processingEnv.getTypeUtils()
                        .asElement(targetClassMirror);

        if (targetClassElement == null) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Cannot find target class: " + targetClassMirror,
                    method
            );
            return false;
        }

        List<ExecutableElement> targetMethods = new ArrayList<>();
        for (Element enclosed : targetClassElement.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.METHOD &&
                    enclosed.getSimpleName().toString()
                            .equals(targetMethodName)) {
                targetMethods.add((ExecutableElement) enclosed);
            }
        }

        if (targetMethods.isEmpty()) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Target method '"
                            + targetMethodName
                            + "' was not found in "
                            + targetClassMirror,
                    method
            );
            return false;
        }

        if (targetMethods.size() > 1) {
            processingEnv.getMessager()
                    .printMessage(
                            Diagnostic.Kind.ERROR,
                            "Target method '"
                                    + targetMethodName
                                    + "' is overloaded. "
                                    + "Use method descriptor to specify which overload to use.",
                            method
                    );
            return false;
        }

        ExecutableElement targetMethod = targetMethods.getFirst();
        List<? extends VariableElement> handlerParams = method.getParameters();
        List<? extends VariableElement> targetParams  = targetMethod.getParameters();
        boolean returnValue = handleEvent.captureReturnValue();
        boolean injectSelf = handleEvent.injectSelf();

        /* ---- special error for injectSelf without params -------------------------------- */
        if (injectSelf && handlerParams.isEmpty()) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "@HandleEvent with injectSelf=true requires at least one parameter — "
                            + "the first parameter must be the target class type: "
                            + targetClassMirror.toString(),
                    method
            );
            return false;
        }
        if (returnValue && targetMethods.getFirst().getReturnType().getKind().equals(TypeKind.VOID)){
            processingEnv.getMessager()
                    .printMessage(Diagnostic.Kind.ERROR,
                            "captureReturnValue cannot be used with a void target method");
            return false;
        }
        /* ---- special error for static with selfInject ----------------------- */
        if (injectSelf && targetMethod.getModifiers().contains(Modifier.STATIC)){
            processingEnv.getMessager()
                    .printMessage(Diagnostic.Kind.ERROR,
                            "injectSelf cannot be used with static target methods");
            return false;
        }
        if (returnValue && !handleEvent.position().equals(InjectionPosition.RETURN)){
            processingEnv.getMessager()
                    .printMessage(Diagnostic.Kind.ERROR,
                            "captureReturnValue is only supported at RETURN");
            return false;
        }
        if (returnValue && handlerParams.isEmpty()){
            processingEnv.getMessager()
                    .printMessage(Diagnostic.Kind.ERROR,
                            "captureReturnValue requires the last handler parameter " +
                                    "to accept the target method's return value");
            return false;
        }
        var lastHandlerParameter = handlerParams.getLast();
        if (returnValue && !lastHandlerParameter.asType().equals(targetMethod.getReturnType())){
            processingEnv.getMessager()
                    .printMessage(Diagnostic.Kind.ERROR,
                            "captureReturnValue requires the last handler parameter " +
                                    "to accept the target method's return value");
            return false;
        }

        List<? extends VariableElement>
                handlerParamsToCompare =
                injectSelf && !handlerParams.isEmpty()
                        ? handlerParams.subList(1, handlerParams.size())
                        : handlerParams;

        if (handlerParamsToCompare.size() != targetParams.size()) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "@HandleEvent method parameters does not match target method '"
                            + targetMethodName
                            + "'. Expected "
                            + targetParams.size()
                            + " parameters, but found "
                            + handlerParamsToCompare.size(),
                    method);
            return false;
        }

        for (int i = 0; i < targetParams.size(); i++) {
            TypeMirror targetParamType = targetParams.get(i).asType();
            TypeMirror handlerParamType =
                    handlerParamsToCompare.get(i).asType();

            if (!processingEnv.getTypeUtils().isAssignable(targetParamType,
                    handlerParamType) &&
                    !processingEnv.getTypeUtils()
                            .isAssignable(handlerParamType, targetParamType)) {
                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.ERROR,
                        "@HandleEvent method parameter types does not match target method '"
                                + targetMethodName
                                + "'. Parameter "
                                + (i + 1)
                                + " expected "
                                + targetParamType
                                + " but found "
                                + handlerParamType,
                        method);
                return false;
            }
        }

        if (handleEvent.injectSelf()) {
            TypeMirror firstParamType = handlerParams.getFirst().asType();
            boolean isAssignable =
                    processingEnv.getTypeUtils()
                            .isAssignable(targetClassMirror, firstParamType);

            if (!isAssignable) {
                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.ERROR,
                        "@HandleEvent injectSelf=true — first parameter must be compatible with "
                                + "the target class '"
                                + targetClassMirror
                                + "'. Found '"
                                + firstParamType
                                + "' which is not a supertype of the target.",
                        handlerParams.getFirst());
                return false;
            }
        }

        return true;
    }

    /* ----------------------------------------------------------------------- */
    private boolean validateReturnTypeIsActionResult(ExecutableElement method) {
        TypeMirror returnType = method.getReturnType();

        if (returnType.getKind() == TypeKind.VOID) {
            // void handlers are allowed
            return true;
        }

        TypeElement actionResultElement =
                processingEnv.getElementUtils()
                        .getTypeElement("net.minecraft.util.ActionResult");

        if (actionResultElement == null) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Cannot find net.minecraft.util.ActionResult in classpath",
                    method);
            return false;
        }

        TypeMirror actionResultType = actionResultElement.asType();

        if (processingEnv.getTypeUtils().isSameType(returnType, actionResultType)) {
            return true;
        }

        // Not acceptable
        processingEnv.getMessager().printMessage(
                Diagnostic.Kind.ERROR,
                "@HandleEvent methods must return net.minecraft.util.ActionResult or boolean",
                method);
        return false;
    }

    /* ----------------------------------------------------------------------- */
    private void patchMixinJson() {
        cleanFrameworkMixinJson();

        File mixinJsonFile = findMixinJson();

        String existingContent = "";
        if (mixinJsonFile != null && mixinJsonFile.exists()) {
            try (BufferedReader reader = new BufferedReader(
                    new FileReader(mixinJsonFile))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line).append("\n");
                existingContent = sb.toString();
                processingEnv.getMessager()
                        .printMessage(Diagnostic.Kind.NOTE,
                                "mixin config found at: " + mixinJsonFile.getAbsolutePath());
            } catch (IOException e) {
                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.WARNING, "Could not read mixin config: " + e.getMessage());
            }
        } else {
            processingEnv.getMessager()
                    .printMessage(Diagnostic.Kind.NOTE,
                            "No mixin config found — will create from scratch.");
        }

        String updatedJson = existingContent.isBlank()
                ? buildMixinJsonFromScratch()
                : injectIntoExistingMixinJson(existingContent);

        if (mixinJsonFile != null) {
            writeToFileSystem(mixinJsonFile, updatedJson);
        }
    }

    /* ----------------------------------------------------------------------- */
    private void cleanFrameworkMixinJson() {
        try {
            String classOutputPath = getClassOutputPath();
            if (classOutputPath == null) return;

            File dir = new File(classOutputPath);

            for (int i = 0; i < 10; i++) {
                if (dir == null) break;

                boolean hasGradle =
                        new File(dir, "build.gradle").exists()
                                || new File(dir,
                                "build.gradle.kts")
                                .exists();
                boolean hasMaven  = new File(dir, "pom.xml").exists();
                boolean hasSrc    = new File(dir, "src").exists();

                if ((hasGradle || hasMaven) && hasSrc) {
                    if (!isMixinPackageBelongingToProject(dir)) {
                        File resourcesDir =
                                new File(dir, "src/main/resources");
                        File[] jsonFiles =
                                resourcesDir.listFiles(
                                        f -> f.getName()
                                                .endsWith(".mixins.json")
                                                || f.getName().equals("mixin.json"));
                        if (jsonFiles != null) {
                            for (File jsonFile : jsonFiles)
                                cleanGeneratedEntriesFrom(jsonFile);
                        }
                    }
                }

                dir = dir.getParentFile();
            }
        } catch (Exception e) {
            processingEnv.getMessager()
                    .printMessage(Diagnostic.Kind.WARNING,
                            "Could not clean framework mixin config: "
                                    + e.getMessage());
        }
    }

    /* ----------------------------------------------------------------------- */
    private void cleanGeneratedEntriesFrom(File jsonFile) {
        if (!jsonFile.exists()) return;

        try (BufferedReader reader = new BufferedReader(
                new FileReader(jsonFile))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null)
                sb.append(line).append("\n");
            String content = sb.toString();
            String cleaned = content;

            for (String fullName : generatedMixinClassNames) {
                String simpleName =
                        fullName.substring(fullName.lastIndexOf('.') + 1);
                if (!content.contains("\"" + simpleName + "\"")) continue;

                cleaned =
                        cleaned
                                .replaceAll(
                                        ",\\s*\"" + simpleName + "\"", "")
                                .replaceAll("\"" + simpleName + "\",",
                                        "")
                                .replaceAll("\"" + simpleName + "\"",
                                        "");

                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.NOTE,
                        "Removed stale entry '" + simpleName
                                + "' from "
                                + jsonFile.getName());
            }

            if (!cleaned.equals(content)) {
                try (Writer writer = new FileWriter(jsonFile)) {
                    writer.write(cleaned);
                }
            }
        } catch (IOException e) {
            processingEnv.getMessager()
                    .printMessage(
                            Diagnostic.Kind.WARNING,
                            "Could not clean stale entries from "
                                    + jsonFile.getName()
                                    + ": "
                                    + e.getMessage());
        }
    }

    /* ----------------------------------------------------------------------- */
    private File findMixinJson() {
        try {
            String classOutputPath = getClassOutputPath();
            if (classOutputPath == null) return null;

            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.NOTE, "CLASS_OUTPUT detected at: "
                            + classOutputPath);

            File dir = new File(classOutputPath);
            for (int i = 0; i < 10; i++) {
                if (dir == null) break;

                boolean hasGradle =
                        new File(dir, "build.gradle").exists()
                                || new File(dir,
                                "build.gradle.kts")
                                .exists();
                boolean hasMaven  = new File(dir, "pom.xml").exists();
                boolean hasSrc    = new File(dir, "src").exists();

                if ((hasGradle || hasMaven) && hasSrc) {
                    File resourcesDir =
                            new File(dir, "src/main/resources");
                    File fabricModJson =
                            new File(resourcesDir,
                                    "fabric.mod.json");

                    if (!fabricModJson.exists()) {
                        dir = dir.getParentFile();
                        continue;
                    }

                    if (!isMixinPackageBelongingToProject(
                            dir)) {
                        dir = dir.getParentFile();
                        continue;
                    }

                    String modId =
                            readModIdFromFabricJson(resourcesDir);
                    processingEnv.getMessager()
                            .printMessage(Diagnostic.Kind.NOTE,
                                    "Detected mod id: "
                                            + modId);

                    String[] candidates = {modId
                            + ".mixins.json",
                            modId
                                    + "-common.mixins.json",
                            "mixin.json"};

                    for (String candidate : candidates) {
                        File f =
                                new File(resourcesDir, candidate);
                        if (f.exists()) {
                            processingEnv.getMessager()
                                    .printMessage(
                                            Diagnostic.Kind.NOTE,
                                            "Found existing mixin config: "
                                                    + f.getName());
                            return f;
                        }
                    }

                    resourcesDir.mkdirs();
                    File newFile =
                            new File(resourcesDir, modId
                                    + ".mixins.json");
                    processingEnv.getMessager()
                            .printMessage(
                                    Diagnostic.Kind.NOTE,
                                    "Will create new mixin config: "
                                            + newFile.getName());
                    return newFile;
                }

                dir = dir.getParentFile();
            }

            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                    "Could not locate project root from: "
                            + classOutputPath);
            return null;

        } catch (Exception e) {
            processingEnv.getMessager()
                    .printMessage(
                            Diagnostic.Kind.WARNING,
                            "Could not resolve CLASS_OUTPUT path: "
                                    + e.getMessage());
            return null;
        }
    }

    /* ----------------------------------------------------------------------- */
    private File findClientSourcesDir() {
        if (cachedClientSourcesDir != null) return cachedClientSourcesDir;

        try {
            String classOutputPath = getClassOutputPath();
            if (classOutputPath == null) return null;

            File dir = new File(classOutputPath);

            for (int i = 0; i < 10; i++) {
                if (dir == null) break;

                boolean hasGradle =
                        new File(dir, "build.gradle").exists()
                                || new File(dir,
                                "build.gradle.kts")
                                .exists();
                boolean hasMaven  = new File(dir, "pom.xml").exists();
                boolean hasSrc    = new File(dir, "src").exists();

                if ((hasGradle || hasMaven) && hasSrc) {
                    File fabricModJson =
                            new File(dir,
                                    "src/main/resources/fabric.mod.json");

                    if (!fabricModJson.exists()) {
                        dir = dir.getParentFile();
                        continue;
                    }

                    if (isMixinPackageBelongingToProject(
                            dir)) {
                        File sourcesDir =
                                new File(dir, "src/main/java");
                        if (sourcesDir.exists()) {
                            processingEnv.getMessager()
                                    .printMessage(Diagnostic.Kind.NOTE,
                                            "Client sources dir found: "
                                                    + sourcesDir.getAbsolutePath());
                            cachedClientSourcesDir = sourcesDir;
                            return cachedClientSourcesDir;
                        }
                    }
                }

                dir = dir.getParentFile();
            }
        } catch (Exception e) {
            processingEnv.getMessager()
                    .printMessage(Diagnostic.Kind.WARNING,
                            "Could not find client sources dir: "
                                    + e.getMessage());
        }
        return null;
    }

    /* ----------------------------------------------------------------------- */
    private boolean isMixinPackageBelongingToProject(File projectRoot) {
        String classOutputPath = getClassOutputPath();
        if (classOutputPath == null) return false;

        String normalizedOutput =
                classOutputPath.replace('\\', '/');
        String normalizedProject =
                projectRoot.getAbsolutePath().replace('\\', '/');

        boolean belongs =
                normalizedOutput.startsWith(normalizedProject);

        processingEnv.getMessager()
                .printMessage(Diagnostic.Kind.NOTE,
                        "Checking project: "
                                + projectRoot.getAbsolutePath()
                                + " -> "
                                + (belongs ? "MATCH" : "no match"));

        return belongs;
    }

    /* ----------------------------------------------------------------------- */
    private String getClassOutputPath() {
        if (cachedClassOutputPath != null) return cachedClassOutputPath;

        try {
            FileObject dummy = processingEnv.getFiler()
                    .getResource(StandardLocation.CLASS_OUTPUT,
                            "", "dummy_probe.tmp");
            cachedClassOutputPath =
                    new File(dummy.toUri()).getParentFile().getAbsolutePath();
        } catch (IOException e) {
            processingEnv.getMessager()
                    .printMessage(
                            Diagnostic.Kind.WARNING,
                            "Could not resolve CLASS_OUTPUT: "
                                    + e.getMessage());
        }

        return cachedClassOutputPath;
    }

    /* ----------------------------------------------------------------------- */
    private String readModIdFromFabricJson(File resourcesDir) {
        File fabricModJson =
                new File(resourcesDir, "fabric.mod.json");

        if (!fabricModJson.exists()) {
            processingEnv.getMessager()
                    .printMessage(Diagnostic.Kind.WARNING,
                            "fabric.mod.json not found — falling back to package-derived mod id.");
            return deriveModIdFromPackage();
        }

        try (BufferedReader reader = new BufferedReader(
                new FileReader(fabricModJson))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            String json = sb.toString();

            int idIndex = json.indexOf("\"id\"");
            if (idIndex == -1) return deriveModIdFromPackage();

            int colonIndex =
                    json.indexOf(':', idIndex);
            int firstQuote =
                    json.indexOf('"', colonIndex);
            int secondQuote =
                    json.indexOf('"',
                            firstQuote + 1);

            if (firstQuote == -1 || secondQuote == -1) return deriveModIdFromPackage();

            String modId = json.substring(firstQuote + 1, secondQuote).trim();
            return modId.isBlank()
                    ? deriveModIdFromPackage()
                    : modId;

        } catch (IOException e) {
            processingEnv.getMessager()
                    .printMessage(
                            Diagnostic.Kind.WARNING,
                            "Could not read fabric.mod.json: "
                                    + e.getMessage());
            return deriveModIdFromPackage();
        }
    }

    /* ----------------------------------------------------------------------- */
    private String deriveModIdFromPackage() {
        if (!generatedMixinClassNames.isEmpty()) {
            String[] parts =
                    generatedMixinClassNames.getFirst().split("\\.");
            return parts.length >= 3
                    ? parts[2]
                    : (parts.length == 2 ? parts[1] : "mod");
        }
        return "mod";
    }

    /* ----------------------------------------------------------------------- */
    private void writeFileToClientSources(JavaFile javaFile) {
        File clientSourcesDir = findClientSourcesDir();

        if (clientSourcesDir != null) {
            try {
                javaFile.writeTo(clientSourcesDir);
                processingEnv.getMessager()
                        .printMessage(Diagnostic.Kind.NOTE,
                                "Generated file written to: "
                                        + clientSourcesDir.getAbsolutePath());
            } catch (IOException e) {
                processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.WARNING,
                        "Could not write to client sources, falling back to Filer: "
                                + e.getMessage());
                writeFileViaFiler(javaFile);
            }
        } else {
            writeFileViaFiler(javaFile);
        }
    }

    /* ----------------------------------------------------------------------- */
    private void writeFileViaFiler(JavaFile javaFile) {
        try {
            javaFile.writeTo(processingEnv.getFiler());
        } catch (IOException e) {
            processingEnv.getMessager()
                    .printMessage(Diagnostic.Kind.ERROR,
                            "Failed to write generated file: "
                                    + e.getMessage());
        }
    }

    /* ----------------------------------------------------------------------- */
    private void writeToFileSystem(File file, String content) {
        try (Writer writer = new FileWriter(file)) {
            writer.write(content);
            processingEnv.getMessager()
                    .printMessage(Diagnostic.Kind.NOTE,
                            "mixin config written to: "
                                    + file.getAbsolutePath());
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Failed to write mixin config: "
                            + e.getMessage());
        }
    }

    /* ----------------------------------------------------------------------- */
    private String injectIntoExistingMixinJson(String json) {
        List<String> toAdd = new ArrayList<>();

        for (String fullName : generatedMixinClassNames) {
            String simpleName =
                    fullName.substring(fullName.lastIndexOf('.') + 1);
            if (!json.contains("\"" + simpleName + "\"")) {
                toAdd.add("\"" + simpleName + "\"");
            }
        }

        if (toAdd.isEmpty()) return json;

        String entry = String.join(",\n       ", toAdd);

        if (json.contains("\"mixins\": []") || json.contains("\"mixins\":[]")) {
            return json
                    .replace(
                            "\"mixins\": []",
                            "\"mixins\": [\n       "
                                    + entry
                                    + "\n    ]")
                    .replace(
                            "\"mixins\":[]",
                            "\"mixins\": [\n       "
                                    + entry
                                    + "\n    ]");
        } else if (json.contains("\"mixins\"")) {
            int mixinsIndex = json.indexOf("\"mixins\"");
            int closingBracket =
                    json.indexOf(']', mixinsIndex);
            int lastQuote =
                    json.lastIndexOf('"', closingBracket);

            int lineStart = json.lastIndexOf('\n',
                    lastQuote);
            String indentation =
                    lineStart != -1
                            ? json.substring(lineStart + 1,
                            json.indexOf('"', lineStart + 1))
                            : "       ";

            return json.substring(0, lastQuote + 1)
                    + ",\n"
                    + indentation
                    + entry
                    + "\n    "
                    + json.substring(closingBracket);
        } else {
            String newArray = "\"mixins\": [\n       "
                    + entry
                    + "\n    ]";
            int lastBrace =
                    json.lastIndexOf('}');
            return json.substring(0, lastBrace)
                    + ",\n    "
                    + newArray
                    + "\n"
                    + json.substring(lastBrace);
        }
    }

    /* ----------------------------------------------------------------------- */
    private String buildMixinJsonFromScratch() {
        String firstFull = generatedMixinClassNames.getFirst();
        String mixinPackage =
                firstFull.substring(0,
                        firstFull.lastIndexOf('.'));

        StringBuilder mixinArray = new StringBuilder();
        for (int i = 0; i < generatedMixinClassNames.size(); i++) {
            String full   = generatedMixinClassNames.get(i);
            String simple = full.substring(full.lastIndexOf('.') + 1);
            if (i > 0) mixinArray.append(",\n       ");
            mixinArray.append("\"")
                    .append(simple)
                    .append("\"");
        }

        return "{\n"
                + "  \"required\": true,\n"
                + "  \"package\": \"" + mixinPackage
                + "\",\n"
                + "  \"compatibilityLevel\": \"JAVA_21\",\n"
                + "  \"mixins\": [\n"
                + "       "
                + mixinArray
                + "\n  ],\n"
                + "  \"injectors\": {\n"
                + "    \"defaultRequire\": 1\n"
                + "  }\n"
                + "}";
    }

    /* ----------------------------------------------------------------------- */
    /** Generates the callback interface used by the event system. */
    private void generateCallbackInterface(TypeElement classElement,
                                           ExecutableElement method,
                                           TypeMirror targetClassMirror) {
        String originalPackage =
                processingEnv.getElementUtils()
                        .getPackageOf(classElement).getQualifiedName().toString();

        String targetSimpleName = getSimpleName(targetClassMirror);
        HandleEvent handleEvent = method.getAnnotation(HandleEvent.class);
        String callbackName = targetSimpleName + capitalize(handleEvent.nameMethod())
                + capitalize(handleEvent.position().getValue()) + "Callback";

        ClassName eventClass = ClassName.get("net.fabricmc.fabric.api.event", "Event");
        ClassName eventFactoryClass =
                ClassName.get("net.fabricmc.fabric.api.event", "EventFactory");

        boolean returnsVoid = method.getReturnType().getKind() == TypeKind.VOID;
        ClassName actionResult = null;
        if (!returnsVoid) {
            actionResult = ClassName.get("net.minecraft.util", "ActionResult");
        }

        List<ParameterSpec> params = new ArrayList<>();
        List<String> paramNames = new ArrayList<>();

        for (VariableElement param : method.getParameters()) {
            params.add(ParameterSpec.builder(TypeName.get(param.asType()),
                    param.getSimpleName().toString()).build());
            paramNames.add(param.getSimpleName().toString());
        }

        TypeName resultReturnType;
        if (returnsVoid) {
            resultReturnType = TypeName.VOID;
        } else {
            resultReturnType = TypeName.get(method.getReturnType());
        }

        MethodSpec.Builder ifaceBuilder = MethodSpec.methodBuilder("handle")
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addParameters(params);

        ifaceBuilder.returns(resultReturnType);

        MethodSpec interfaceMethod = ifaceBuilder.build();

        ClassName selfClass = ClassName.get(originalPackage + ".callback", callbackName);
        TypeName eventType =
                ParameterizedTypeName.get(eventClass, selfClass);
        String argsJoined = String.join(", ", paramNames);

        CodeBlock listenerLoop;
        if (returnsVoid) {
            listenerLoop = CodeBlock.builder()
                    .beginControlFlow("for ($T listener : listeners)", selfClass)
                    .addStatement("listener.handle($L)", argsJoined)
                    .endControlFlow()
                    .build();
        } else {
            // For non‑void handlers
            if (resultReturnType.equals(actionResult)) {
                // ActionResult special handling – propagate PASS
                listenerLoop = CodeBlock.builder()
                        .beginControlFlow("for ($T listener : listeners)", selfClass)
                        .addStatement("$T result = listener.handle($L)",
                                actionResult, argsJoined)
                        .beginControlFlow("if (result != $T.PASS)", actionResult)
                        .addStatement("return result")
                        .endControlFlow()
                        .endControlFlow()
                        .addStatement("return $T.PASS", actionResult)
                        .build();
            } else {
                // Generic handler – return the last listener’s result
                CodeBlock.Builder loopBuilder = CodeBlock.builder()
                        .addStatement("$T result = $L",
                                resultReturnType, defaultLiteralFor(resultReturnType));
                loopBuilder.beginControlFlow("for ($T listener : listeners)", selfClass);
                loopBuilder.addStatement("result = listener.handle($L)", argsJoined);
                loopBuilder.endControlFlow()
                        .addStatement("return result");
                listenerLoop = loopBuilder.build();
            }
        }

        CodeBlock.Builder initializer = CodeBlock.builder()
                .add("$T.createArrayBacked($T.class,\n",
                        eventFactoryClass,
                        selfClass)
                .indent()
                .add("(listeners) -> ($L) -> {\n", argsJoined)
                .indent()
                .add(listenerLoop);

        /* No unconditional return added here – listenerLoop already returns PASS if needed */
        initializer
                .unindent()
                .add("})")
                .unindent();

        CodeBlock eventInitializer = initializer.build();

        FieldSpec eventField = FieldSpec.builder(eventType, "EVENT")
                .addModifiers(Modifier.PUBLIC,
                        Modifier.STATIC, Modifier.FINAL)
                .initializer(eventInitializer)
                .build();

        TypeSpec callbackInterface =
                TypeSpec.interfaceBuilder(callbackName)
                        .addModifiers(Modifier.PUBLIC)
                        .addField(eventField)
                        .addMethod(interfaceMethod)
                        .build();

        writeFileToClientSources(
                JavaFile.builder(originalPackage + ".callback",
                                callbackInterface)
                        .indent("    ")
                        .skipJavaLangImports(true).build());
    }

    /* ----------------------------------------------------------------------- */
    /** Generates the mixin class that injects into the target method. */
    private String generateMixinClass(TypeElement classElement,
                                      ExecutableElement method,
                                      TypeMirror targetClassMirror) {
        String originalPackage =
                processingEnv.getElementUtils()
                        .getPackageOf(classElement).getQualifiedName().toString();

        String targetSimpleName = getSimpleName(targetClassMirror);
        HandleEvent handleEvent = method.getAnnotation(HandleEvent.class);
        String targetMethodName = handleEvent.nameMethod();
        String position = handleEvent.position().getValue();
        boolean injectSelf = handleEvent.injectSelf();

        TypeElement targetClassElement =
                (TypeElement) processingEnv.getTypeUtils()
                        .asElement(targetClassMirror);

        ExecutableElement targetMethod = null;
        for (Element e : targetClassElement.getEnclosedElements()) {
            if (e.getKind() == ElementKind.METHOD &&
                    e.getSimpleName().contentEquals(targetMethodName)) {
                targetMethod = (ExecutableElement) e;
                break;
            }
        }
        if (targetMethod == null) return null; // already validated

        TypeMirror targetReturnTypeMirror =
                targetMethod.getReturnType();
        boolean targetReturnsVoid =
                targetReturnTypeMirror.getKind() == TypeKind.VOID;
        boolean targetReturnsBoolean =
                targetReturnTypeMirror.getKind() == TypeKind.BOOLEAN;

        String callbackName = targetSimpleName + capitalize(targetMethodName)
                + capitalize(position) + "Callback";
        String mixinClassName = targetSimpleName + capitalize(targetMethodName)
                + capitalize(position) + "Mixin";
        String mixinPackage = originalPackage + ".mixin";

        ClassName callbackClass =
                ClassName.get(originalPackage + ".callback",
                        callbackName);
        ClassName actionResultClass =
                ClassName.get("net.minecraft.util", "ActionResult");

        AnnotationSpec atAnnotation = AnnotationSpec.builder(
                        ClassName.get("org.spongepowered.asm.mixin.injection",
                                "At"))
                .addMember("value", "$S", position)
                .build();

        AnnotationSpec injectAnnotation = AnnotationSpec.builder(
                        ClassName.get("org.spongepowered.asm.mixin.injection",
                                "Inject"))
                .addMember("method", "$S", targetMethodName)
                .addMember("at", "$L", atAnnotation)
                .addMember("cancellable", "$L", true)
                .build();

        AnnotationSpec mixinAnnotation = AnnotationSpec.builder(
                        ClassName.get("org.spongepowered.asm.mixin",
                                "Mixin"))
                .addMember("value", "$T.class", targetClassMirror)
                .build();

        ClassName ciClass =
                ClassName.get("org.spongepowered.asm.mixin.injection.callback",
                        "CallbackInfo");
        ClassName cirClass =
                ClassName.get("org.spongepowered.asm.mixin.injection.callback",
                        "CallbackInfoReturnable");

        boolean handlerReturnsVoid = method.getReturnType().getKind() == TypeKind.VOID;
        TypeName handlerReturnTypeName = TypeName.get(method.getReturnType());
        boolean isActionResultHandler = method.getReturnType()
                .toString()
                .equals("net.minecraft.util.ActionResult");

        // The <T> in CallbackInfoReturnable<T> must reflect the TARGET method's
        // return type (boxed), not the handler's return type — the handler's
        // ActionResult gets translated into whatever the target actually returns.
        TypeName ciReturnBoxed =
                targetReturnsVoid
                        ? null
                        : boxedType(TypeName.get(targetReturnTypeMirror));

        TypeName callbackCiType =
                targetReturnsVoid
                        ? ClassName.get(ciClass.packageName(),
                        ciClass.simpleName())
                        : ParameterizedTypeName.get(cirClass, ciReturnBoxed);

        List<VariableElement> params =
                new ArrayList<>(method.getParameters());
        List<VariableElement> mixinParams = injectSelf ? params.subList(1, params.size()) : params;

        MethodSpec.Builder methodBuilder = MethodSpec
                .methodBuilder("on" + capitalize(targetMethodName))
                .addAnnotation(injectAnnotation)
                .addModifiers(Modifier.PRIVATE)
                .returns(void.class);

        for (VariableElement param : mixinParams) {
            methodBuilder.addParameter(TypeName.get(param.asType()),
                    param.getSimpleName().toString());
        }

        // add ci parameter
        methodBuilder.addParameter(callbackCiType, "ci");

        List<String> argNames = new ArrayList<>();
        for (VariableElement param : mixinParams) {
            argNames.add(param.getSimpleName().toString());
        }

        String argsJoined;
        if (injectSelf && !params.isEmpty()) {
            TypeName selfType = TypeName.get(params.get(0).asType());
            String selfCast =
                    "(" + selfType + ")(Object) this";

            List<String> allArgs = new ArrayList<>();
            allArgs.add(selfCast);
            allArgs.addAll(argNames);
            argsJoined = String.join(", ", allArgs);
        } else {
            argsJoined = String.join(", ", argNames);
        }

        if (handlerReturnsVoid) {
            methodBuilder.addStatement("$T.EVENT.invoker().handle($L)",
                    callbackClass, argsJoined);
            if (targetReturnsVoid && isActionResultHandler) {
                // cancel on FAIL
                methodBuilder.beginControlFlow("if ($T.FAIL == $T.result)", actionResultClass, callbackClass)
                        .addStatement("ci.cancel()")
                        .endControlFlow();
            }
        } else {
            methodBuilder.addStatement("$T result = $T.EVENT.invoker().handle($L)",
                    handlerReturnTypeName, callbackClass, argsJoined);
            if (targetReturnsVoid) {
                if (isActionResultHandler) {
                    methodBuilder.beginControlFlow("if (result == $T.FAIL)", actionResultClass)
                            .addStatement("ci.cancel()")
                            .endControlFlow();
                }
                // No return value to set for void target
            } else if (targetReturnsBoolean && isActionResultHandler) {
                // Translate ActionResult -> boolean for boolean-returning targets
                methodBuilder.beginControlFlow("if (result == $T.SUCCESS)", actionResultClass)
                        .addStatement("ci.setReturnValue(true)")
                        .nextControlFlow("else if (result == $T.FAIL)", actionResultClass)
                        .addStatement("ci.setReturnValue(false)")
                        .endControlFlow();
            } else {
                methodBuilder.addStatement("ci.setReturnValue(result)");
            }
        }

        TypeSpec mixinClass = TypeSpec.classBuilder(mixinClassName)
                .addModifiers(Modifier.PUBLIC, Modifier.ABSTRACT)
                .addAnnotation(mixinAnnotation)
                .addMethod(methodBuilder.build())
                .build();

        writeFileToClientSources(
                JavaFile.builder(mixinPackage,
                                mixinClass)
                        .indent("    ")
                        .skipJavaLangImports(true).build());

        return mixinPackage + "." + mixinClassName;
    }

    /* ----------------------------------------------------------------------- */
    /** Generates the registrar class that registers the callbacks. */
    private void generateRegistrar(TypeElement classElement,
                                   List<ExecutableElement> methods,
                                   TypeMirror targetClassMirror) {
        String originalPackage =
                processingEnv.getElementUtils()
                        .getPackageOf(classElement).getQualifiedName().toString();
        String targetSimpleName = getSimpleName(targetClassMirror);
        String originalClassName =
                classElement.getSimpleName().toString();

        MethodSpec.Builder registerMethod = MethodSpec.methodBuilder("register")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .returns(void.class);

        for (ExecutableElement method : methods) {
            HandleEvent handleEvent  = method.getAnnotation(HandleEvent.class);
            String      methodName   = method.getSimpleName().toString();
            String      callbackName = targetSimpleName + capitalize(handleEvent.nameMethod())
                    + capitalize(handleEvent.position().getValue()) + "Callback";
            ClassName   callbackClass = ClassName.get(originalPackage + ".callback", callbackName);

            List<String> argNames = new ArrayList<>();
            for (VariableElement param : method.getParameters()) {
                argNames.add(param.getSimpleName().toString());
            }
            String argsJoined = String.join(", ", argNames);

            CodeBlock.Builder registerBuilder = CodeBlock.builder()
                    .add("$T.EVENT.register(($L) -> {\n",
                            callbackClass,
                            argsJoined)
                    .indent();

            if (method.getReturnType().getKind() == TypeKind.VOID) {
                registerBuilder
                        .addStatement("$T.$L($L)",
                                classElement,
                                methodName,
                                argsJoined);
            } else {
                registerBuilder
                        .addStatement("return $T.$L($L)",
                                classElement,
                                methodName,
                                argsJoined);
            }

            registerBuilder.unindent()
                    .add("});\n");

            CodeBlock registerBlock = registerBuilder.build();

            registerMethod.addCode(registerBlock);
        }

        TypeSpec registrarClass = TypeSpec.classBuilder(originalClassName + "Registrar")
                .addModifiers(Modifier.PUBLIC)
                .addMethod(registerMethod.build())
                .build();

        writeFileToClientSources(
                JavaFile.builder(originalPackage + ".registrar",
                                registrarClass)
                        .indent("    ")
                        .skipJavaLangImports(true).build());
    }

    /* ----------------------------------------------------------------------- */
    private TypeMirror getTargetClassMirror(FabricEvent annotation) {
        try {
            annotation.value();
        } catch (MirroredTypeException mte) {
            return mte.getTypeMirror();
        }
        return null;
    }

    /* ----------------------------------------------------------------------- */
    private String getSimpleName(TypeMirror mirror) {
        String full = mirror.toString();
        return full.substring(full.lastIndexOf('.') + 1);
    }

    /* ----------------------------------------------------------------------- */
    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    /* ----------------------------------------------------------------------- */
    /** Returns the default literal for a given type. */
    private String defaultLiteralFor(TypeName type) {
        if (type.equals(TypeName.BOOLEAN)) return "false";
        if (type.equals(TypeName.INT)) return "0";
        if (type.equals(TypeName.LONG)) return "0L";
        if (type.equals(TypeName.FLOAT)) return "0.0f";
        if (type.equals(TypeName.DOUBLE)) return "0.0d";
        if (type.equals(TypeName.BYTE)) return "(byte)0";
        if (type.equals(TypeName.SHORT)) return "(short)0";
        if (type.equals(TypeName.CHAR)) return "'\\u0000'";
        // For objects and wrapper types, null is a safe default
        return "null";
    }

    /** Returns the boxed type for primitives. */
    private TypeName boxedType(TypeName type) {
        if (type.equals(TypeName.BOOLEAN))
            return ClassName.get("java.lang", "Boolean");
        if (type.equals(TypeName.INT))
            return ClassName.get("java.lang", "Integer");
        if (type.equals(TypeName.LONG))
            return ClassName.get("java.lang", "Long");
        if (type.equals(TypeName.FLOAT))
            return ClassName.get("java.lang", "Float");
        if (type.equals(TypeName.DOUBLE))
            return ClassName.get("java.lang", "Double");
        if (type.equals(TypeName.BYTE))
            return ClassName.get("java.lang", "Byte");
        if (type.equals(TypeName.SHORT))
            return ClassName.get("java.lang", "Short");
        if (type.equals(TypeName.CHAR))
            return ClassName.get("java.lang", "Character");
        // For other types, keep as is
        return type;
    }
}