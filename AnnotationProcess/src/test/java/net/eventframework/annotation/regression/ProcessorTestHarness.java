package net.eventframework.annotation.regression;

import static com.google.testing.compile.Compiler.javac;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import net.eventframework.annotation.AnnotationProcessor;

import javax.tools.JavaFileObject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;

final class ProcessorTestHarness {

    private ProcessorTestHarness() {}

    static Compilation compile(JavaFileObject... userSources) {
        List<JavaFileObject> sources = new ArrayList<>(stubs());
        sources.addAll(Arrays.asList(userSources));

        return javac()
                .withOptions("--release", "21", "-parameters")
                .withProcessors(new AnnotationProcessor())
                .compile(sources);
    }

    static JavaFileObject src(String fqcn, String... lines) {
        return JavaFileObjects.forSourceLines(fqcn, lines);
    }

    static String generatedContaining(Compilation c, String... fragments) {
        return c.generatedSourceFiles().stream()
                .map(f -> {
                    try {
                        return f.getCharContent(false).toString();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })
                .filter(s -> Arrays.stream(fragments).allMatch(s::contains))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No generated source matched fragments"));
    }

    static int count(String s, String sub) {
        int i = 0, n = 0;
        while ((i = s.indexOf(sub, i)) != -1) {
            n++;
            i += sub.length();
        }
        return n;
    }

    private static List<JavaFileObject> stubs() {
        return List.of(
                // ActionResult
                src("net.minecraft.util.ActionResult",
                        "package net.minecraft.util;",
                        "public enum ActionResult { PASS, SUCCESS, FAIL; }"
                ),

                // Fabric Event
                src("net.fabricmc.fabric.api.event.Event",
                        "package net.fabricmc.fabric.api.event;",
                        "public interface Event<T> {",
                        "  T invoker();",
                        "  void register(T listener);",
                        "}"
                ),

                src("net.fabricmc.fabric.api.event.EventFactory",
                        "package net.fabricmc.fabric.api.event;",
                        "import java.util.function.Function;",
                        "public final class EventFactory {",
                        "  public static <T> Event<T> createArrayBacked(Class<T> t, Function<T[], T> f) {",
                        "    return null;",
                        "  }",
                        "}"
                ),

                // Mixin stubs
                src("org.spongepowered.asm.mixin.Mixin",
                        "package org.spongepowered.asm.mixin;",
                        "public @interface Mixin { Class<?>[] value(); }"
                ),

                src("org.spongepowered.asm.mixin.injection.At",
                        "package org.spongepowered.asm.mixin.injection;",
                        "public @interface At {",
                        "  String value();",
                        "  String target() default \"\";",
                        "  int ordinal() default -1;",
                        "}"
                ),

                src("org.spongepowered.asm.mixin.injection.Inject",
                        "package org.spongepowered.asm.mixin.injection;",
                        "public @interface Inject {",
                        "  String[] method();",
                        "  At at();",
                        "  boolean cancellable() default false;",
                        "}"
                ),

                src("org.spongepowered.asm.mixin.injection.callback.CallbackInfo",
                        "package org.spongepowered.asm.mixin.injection.callback;",
                        "public class CallbackInfo {",
                        "  public void cancel() {}",
                        "}"
                ),

                src("org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable",
                        "package org.spongepowered.asm.mixin.injection.callback;",
                        "public class CallbackInfoReturnable<T> extends CallbackInfo {",
                        "  public void setReturnValue(T v) {}",
                        "}"
                )
        );
    }
}