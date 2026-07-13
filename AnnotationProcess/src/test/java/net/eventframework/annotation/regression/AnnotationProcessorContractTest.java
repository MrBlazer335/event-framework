package net.eventframework.annotation.regression;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import java.util.ArrayList;
import java.util.List;
import javax.tools.JavaFileObject;

import net.eventframework.annotation.AnnotationProcessor;
import org.junit.jupiter.api.Test;

/**
 * Public contract tests for Event Framework's annotation processor.
 *
 * <p>Several tests are intentionally red against the current alpha implementation. Make the
 * processor emit the specified diagnostics and they become regression tests for the fixes.
 */
final class AnnotationProcessorContractTest {

    @Test
    void validPublicStaticHandlerCompiles() {
        Compilation compilation = compile(handler("""
                package test;

                import net.eventframework.annotation.FabricEvent;
                import net.eventframework.annotation.HandleEvent;
                import net.eventframework.annotation.InjectionPosition;
                import net.minecraft.util.ActionResult;

                @FabricEvent(Target.class)
                public final class ValidEvents {
                    @HandleEvent(position = InjectionPosition.HEAD, nameMethod = "run")
                    public static ActionResult onRun(int amount) {
                        return ActionResult.PASS;
                    }
                }
                """, "test.ValidEvents"));

        assertThat(compilation).succeeded();
    }

    @Test
    void rejectsNonStaticHandler() {
        Compilation compilation = compile(handler("""
                package test;

                import net.eventframework.annotation.FabricEvent;
                import net.eventframework.annotation.HandleEvent;
                import net.eventframework.annotation.InjectionPosition;
                import net.minecraft.util.ActionResult;

                @FabricEvent(Target.class)
                public final class NonStaticEvents {
                    @HandleEvent(position = InjectionPosition.HEAD, nameMethod = "run")
                    public ActionResult onRun(int amount) {
                        return ActionResult.PASS;
                    }
                }
                """, "test.NonStaticEvents"));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("@HandleEvent methods must be static");
    }

    @Test
    void rejectsPrivateHandlerBecauseGeneratedRegistrarCannotCallIt() {
        Compilation compilation = compile(handler("""
                package test;

                import net.eventframework.annotation.FabricEvent;
                import net.eventframework.annotation.HandleEvent;
                import net.eventframework.annotation.InjectionPosition;
                import net.minecraft.util.ActionResult;

                @FabricEvent(Target.class)
                public final class PrivateEvents {
                    @HandleEvent(position = InjectionPosition.HEAD, nameMethod = "run")
                    private static ActionResult onRun(int amount) {
                        return ActionResult.PASS;
                    }
                }
                """, "test.PrivateEvents"));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("@HandleEvent methods must be public");
    }

    @Test
    void rejectsHandlerWithWrongReturnType() {
        Compilation compilation = compile(handler("""
                package test;

                import net.eventframework.annotation.FabricEvent;
                import net.eventframework.annotation.HandleEvent;
                import net.eventframework.annotation.InjectionPosition;

                @FabricEvent(Target.class)
                public final class WrongReturnEvents {
                    @HandleEvent(position = InjectionPosition.HEAD, nameMethod = "run")
                    public static boolean onRun(int amount) {
                        return true;
                    }
                }
                """, "test.WrongReturnEvents"));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("@HandleEvent methods must return net.minecraft.util.ActionResult");
    }

    @Test
    void rejectsInjectSelfWithoutParameters() {
        Compilation compilation = compile(handler("""
                package test;

                import net.eventframework.annotation.FabricEvent;
                import net.eventframework.annotation.HandleEvent;
                import net.eventframework.annotation.InjectionPosition;
                import net.minecraft.util.ActionResult;

                @FabricEvent(Target.class)
                public final class MissingSelfEvents {
                    @HandleEvent(position = InjectionPosition.HEAD, nameMethod = "run", injectSelf = true)
                    public static ActionResult onRun() {
                        return ActionResult.PASS;
                    }
                }
                """, "test.MissingSelfEvents"));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("injectSelf=true requires at least one parameter");
    }

    @Test
    void rejectsInjectSelfWithIncompatibleFirstParameter() {
        Compilation compilation = compile(handler("""
                package test;

                import net.eventframework.annotation.FabricEvent;
                import net.eventframework.annotation.HandleEvent;
                import net.eventframework.annotation.InjectionPosition;
                import net.minecraft.util.ActionResult;

                @FabricEvent(Target.class)
                public final class WrongSelfEvents {
                    @HandleEvent(position = InjectionPosition.HEAD, nameMethod = "run", injectSelf = true)
                    public static ActionResult onRun(String self, int amount) {
                        return ActionResult.PASS;
                    }
                }
                """, "test.WrongSelfEvents"));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("first parameter");
        assertThat(compilation).hadErrorContaining("compatible with");
    }

    @Test
    void rejectsUnknownTargetMethod() {
        Compilation compilation = compile(handler("""
                package test;

                import net.eventframework.annotation.FabricEvent;
                import net.eventframework.annotation.HandleEvent;
                import net.eventframework.annotation.InjectionPosition;
                import net.minecraft.util.ActionResult;

                @FabricEvent(Target.class)
                public final class UnknownMethodEvents {
                    @HandleEvent(position = InjectionPosition.HEAD, nameMethod = "doesNotExist")
                    public static ActionResult onMissing(int amount) {
                        return ActionResult.PASS;
                    }
                }
                """, "test.UnknownMethodEvents"));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("Target method 'doesNotExist' was not found");
    }

    @Test
    void rejectsHandlerParametersThatDoNotMatchTargetMethod() {
        Compilation compilation = compile(handler("""
                package test;

                import net.eventframework.annotation.FabricEvent;
                import net.eventframework.annotation.HandleEvent;
                import net.eventframework.annotation.InjectionPosition;
                import net.minecraft.util.ActionResult;

                @FabricEvent(Target.class)
                public final class WrongParametersEvents {
                    @HandleEvent(position = InjectionPosition.HEAD, nameMethod = "run")
                    public static ActionResult onRun(String amount) {
                        return ActionResult.PASS;
                    }
                }
                """, "test.WrongParametersEvents"));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("does not match target method");
    }

    @Test
    void rejectsAmbiguousOverloadedTargetWithoutDescriptor() {
        Compilation compilation = compile(
                JavaFileObjects.forSourceString("test.OverloadedTarget", """
                    package test;
                    public class OverloadedTarget {
                        public void run(int amount) {}
                        public void run(String value) {}
                    }
                    """),
                JavaFileObjects.forSourceString("test.AmbiguousEvents", """
                    package test;

                    import net.eventframework.annotation.FabricEvent;
                    import net.eventframework.annotation.HandleEvent;
                    import net.eventframework.annotation.InjectionPosition;
                    import net.minecraft.util.ActionResult;

                    @FabricEvent(OverloadedTarget.class)
                    public final class AmbiguousEvents {
                        @HandleEvent(position = InjectionPosition.HEAD, nameMethod = "run")
                        public static ActionResult onRun(int amount) {
                            return ActionResult.PASS;
                        }
                    }
                    """));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining("Target method 'run' is overloaded");
    }

    @Test
    void twoHandlersForSameTargetDoNotGenerateTheSameClassName() {
        Compilation compilation = compile(
                handler("""
                    package first;

                    import net.eventframework.annotation.FabricEvent;
                    import net.eventframework.annotation.HandleEvent;
                    import net.eventframework.annotation.InjectionPosition;
                    import net.minecraft.util.ActionResult;
                    import test.Target;

                    @FabricEvent(Target.class)
                    public final class FirstEvents {
                        @HandleEvent(position = InjectionPosition.HEAD, nameMethod = "run")
                        public static ActionResult first(int amount) {
                            return ActionResult.PASS;
                        }
                    }
                    """, "first.FirstEvents"),
                handler("""
                    package second;

                    import net.eventframework.annotation.FabricEvent;
                    import net.eventframework.annotation.HandleEvent;
                    import net.eventframework.annotation.InjectionPosition;
                    import net.minecraft.util.ActionResult;
                    import test.Target;

                    @FabricEvent(Target.class)
                    public final class SecondEvents {
                        @HandleEvent(position = InjectionPosition.TAIL, nameMethod = "run")
                        public static ActionResult second(int amount) {
                            return ActionResult.PASS;
                        }
                    }
                    """, "second.SecondEvents"));

        assertThat(compilation).succeeded();

        long generatedMixins = compilation.generatedSourceFiles().stream()
                .map(file -> file.toUri().toString())
                .filter(path -> path.contains("mixin"))
                .filter(path -> path.endsWith("Mixin.java"))
                .count();

        assertThat(generatedMixins).isAtLeast(2);
    }

    private static Compilation compile(JavaFileObject... handlers) {
        List<JavaFileObject> sources = new ArrayList<>(stubs());
        sources.addAll(List.of(handlers));

        return javac()
                .withProcessors(new AnnotationProcessor())
                .compile(sources);
    }

    private static JavaFileObject handler(String source, String qualifiedName) {
        return JavaFileObjects.forSourceString(qualifiedName, source);
    }

    /** Minimal external API stubs so generated Fabric/Mixin code can be compiled. */
    private static List<JavaFileObject> stubs() {
        return List.of(
                JavaFileObjects.forSourceString("test.Target", """
                    package test;
                    public class Target {
                        public void run(int amount) {}
                    }
                    """),
                JavaFileObjects.forSourceString("net.minecraft.util.ActionResult", """
                    package net.minecraft.util;
                    public enum ActionResult { PASS, SUCCESS, FAIL }
                    """),
                JavaFileObjects.forSourceString("net.fabricmc.fabric.api.event.Event", """
                    package net.fabricmc.fabric.api.event;
                    public interface Event<T> {
                        void register(T listener);
                        T invoker();
                    }
                    """),
                JavaFileObjects.forSourceString("net.fabricmc.fabric.api.event.EventFactory", """
                    package net.fabricmc.fabric.api.event;
                    import java.util.function.Function;
                    public final class EventFactory {
                        private EventFactory() {}
                        public static <T> Event<T> createArrayBacked(Class<T> type, Function<T[], T> factory) {
                            return null;
                        }
                    }
                    """),
                JavaFileObjects.forSourceString("org.spongepowered.asm.mixin.Mixin", """
                    package org.spongepowered.asm.mixin;
                    import java.lang.annotation.ElementType;
                    import java.lang.annotation.Target;
                    @Target(ElementType.TYPE)
                    public @interface Mixin { Class<?> value(); }
                    """),
                JavaFileObjects.forSourceString("org.spongepowered.asm.mixin.injection.At", """
                    package org.spongepowered.asm.mixin.injection;
                    import java.lang.annotation.ElementType;
                    import java.lang.annotation.Target;
                    @Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
                    public @interface At {
                        String value();
                        String target() default "";
                    }
                    """),
                JavaFileObjects.forSourceString("org.spongepowered.asm.mixin.injection.Inject", """
                    package org.spongepowered.asm.mixin.injection;
                    import java.lang.annotation.ElementType;
                    import java.lang.annotation.Target;
                    @Target(ElementType.METHOD)
                    public @interface Inject {
                        String method();
                        At at();
                        boolean cancellable() default false;
                    }
                    """),
                JavaFileObjects.forSourceString("org.spongepowered.asm.mixin.injection.callback.CallbackInfo", """
                    package org.spongepowered.asm.mixin.injection.callback;
                    public class CallbackInfo { public void cancel() {} }
                    """),
                JavaFileObjects.forSourceString("org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable", """
                    package org.spongepowered.asm.mixin.injection.callback;
                    public class CallbackInfoReturnable<T> extends CallbackInfo {
                        public void setReturnValue(T value) {}
                    }
                    """));
    }
}
