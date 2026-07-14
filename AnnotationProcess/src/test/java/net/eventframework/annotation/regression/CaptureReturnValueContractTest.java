package net.eventframework.annotation.regression;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.CompilationSubject.assertThat;
import static com.google.testing.compile.Compiler.javac;

import com.google.testing.compile.Compilation;
import com.google.testing.compile.JavaFileObjects;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import javax.tools.JavaFileObject;
import net.eventframework.annotation.AnnotationProcessor;
import net.eventframework.annotation.HandleEvent;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link HandleEvent#captureReturnValue()}.
 *
 * <p>When {@code captureReturnValue = true}, the final handler parameter receives the target
 * method's current return value. The captured parameter follows {@code self}, when requested, and
 * all ordinary target-method parameters.
 */
final class CaptureReturnValueContractTest {

    @Test
    void captureReturnValueDefaultsToFalse() throws Exception {
        Method option = HandleEvent.class.getDeclaredMethod("captureReturnValue");

        assertThat(option.getDefaultValue()).isEqualTo(false);
    }

    @Test
    void defaultFlagDoesNotAddACapturedParameter() {
        Compilation compilation = compile(
                attributeTarget(),
                JavaFileObjects.forSourceString("test.DefaultCaptureEvents", """
                        package test;

                        import net.eventframework.annotation.FabricEvent;
                        import net.eventframework.annotation.HandleEvent;
                        import net.eventframework.annotation.InjectionPosition;

                        @FabricEvent(AttributeTarget.class)
                        public final class DefaultCaptureEvents {
                            @HandleEvent(
                                    position = InjectionPosition.RETURN,
                                    nameMethod = "createPlayerAttributes"
                            )
                            public static void afterCreate() {}
                        }
                        """));

        assertThat(compilation).succeeded();
    }

    @Test
    void supportsMindInjectorHandlerShape() {
        Compilation compilation = compile(attributeTarget(), validMindEvents());

        assertThat(compilation).succeeded();
    }

    @Test
    void capturedResultMayFollowOrdinaryArgumentsAndUseASupertype() {
        Compilation compilation = compile(targetWithArgument(), argumentEvents());

        assertThat(compilation).succeeded();
    }

    @Test
    void generatedMixinHandlerIsStaticAndUsesCallbackInfoReturnable() {
        Compilation compilation = compile(attributeTarget(), validMindEvents());

        assertThat(compilation).succeeded();
        String mixin = generatedSourceEndingWith(
                compilation,
                "AttributeTargetCreatePlayerAttributesRETURNMixin.java");

        assertThat(mixin).containsMatch("private\\s+static\\s+void\\s+\\w+\\s*\\(");
        assertThat(mixin).contains("CallbackInfoReturnable<");
        assertThat(mixin).contains("Builder>");
    }

    @Test
    void generatedMixinPassesOrdinaryArgumentsBeforeTheCurrentResult() {
        Compilation compilation = compile(targetWithArgument(), argumentEvents());

        assertThat(compilation).succeeded();
        String mixin = generatedSourceEndingWith(
                compilation,
                "TargetWithArgumentCreateRETURNMixin.java");

        assertThat(mixin).contains("getReturnValue()");
        assertThat(mixin).containsMatch(
                "handle\\(id,\\s*\\w+\\.getReturnValue\\(\\)\\)");
    }

    @Test
    void generatedCallbackAndRegistrarForwardAllHandlerParameters() {
        Compilation compilation = compile(targetWithArgument(), argumentEvents());

        assertThat(compilation).succeeded();

        String callback = generatedSourceEndingWith(
                compilation,
                "TargetWithArgumentCreateRETURNCallback.java");
        assertThat(callback).contains("void handle(");
        assertThat(callback).contains("String id");
        assertThat(callback).contains("Object result");
        assertThat(callback).contains("listener.handle(id, result)");

        String registrar = generatedSourceEndingWith(compilation, "ArgumentEventsRegistrar.java");
        assertThat(registrar).contains("ArgumentEvents.afterCreate(id, result)");
    }

    @Test
    void rejectsInjectSelfForStaticTargetMethod() {
        Compilation compilation = compile(
                attributeTarget(),
                JavaFileObjects.forSourceString("test.StaticSelfEvents", """
                        package test;

                        import net.eventframework.annotation.FabricEvent;
                        import net.eventframework.annotation.HandleEvent;
                        import net.eventframework.annotation.InjectionPosition;

                        @FabricEvent(AttributeTarget.class)
                        public final class StaticSelfEvents {
                            @HandleEvent(
                                    position = InjectionPosition.HEAD,
                                    nameMethod = "staticOperation",
                                    injectSelf = true
                            )
                            public static void handle(AttributeTarget self) {}
                        }
                        """));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining(
                "injectSelf cannot be used with static target methods");
    }

    @Test
    void rejectsCaptureOutsideReturnInjection() {
        Compilation compilation = compile(
                attributeTarget(),
                JavaFileObjects.forSourceString("test.HeadCaptureEvents", """
                        package test;

                        import net.eventframework.annotation.FabricEvent;
                        import net.eventframework.annotation.HandleEvent;
                        import net.eventframework.annotation.InjectionPosition;

                        @FabricEvent(AttributeTarget.class)
                        public final class HeadCaptureEvents {
                            @HandleEvent(
                                    position = InjectionPosition.HEAD,
                                    nameMethod = "createPlayerAttributes",
                                    captureReturnValue = true
                            )
                            public static void beforeCreate(
                                    AttributeTarget.Builder result
                            ) {}
                        }
                        """));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining(
                "captureReturnValue is only supported at RETURN");
    }

    @Test
    void rejectsCaptureForVoidTargetMethod() {
        Compilation compilation = compile(
                voidTarget(),
                JavaFileObjects.forSourceString("test.VoidCaptureEvents", """
                        package test;

                        import net.eventframework.annotation.FabricEvent;
                        import net.eventframework.annotation.HandleEvent;
                        import net.eventframework.annotation.InjectionPosition;

                        @FabricEvent(VoidTarget.class)
                        public final class VoidCaptureEvents {
                            @HandleEvent(
                                    position = InjectionPosition.RETURN,
                                    nameMethod = "initialize",
                                    captureReturnValue = true
                            )
                            public static void afterInitialize(Object result) {}
                        }
                        """));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining(
                "captureReturnValue cannot be used with a void target method");
    }

    @Test
    void rejectsIncompatibleFinalHandlerParameterType() {
        Compilation compilation = compile(
                attributeTarget(),
                JavaFileObjects.forSourceString("test.WrongCapturedTypeEvents", """
                        package test;

                        import net.eventframework.annotation.FabricEvent;
                        import net.eventframework.annotation.HandleEvent;
                        import net.eventframework.annotation.InjectionPosition;

                        @FabricEvent(AttributeTarget.class)
                        public final class WrongCapturedTypeEvents {
                            @HandleEvent(
                                    position = InjectionPosition.RETURN,
                                    nameMethod = "createPlayerAttributes",
                                    captureReturnValue = true
                            )
                            public static void addMind(String result) {}
                        }
                        """));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining(
                "captureReturnValue requires the last handler parameter "
                        + "to accept the target method's return value");
    }

    @Test
    void rejectsCaptureWhenTrailingReturnValueParameterIsMissing() {
        Compilation compilation = compile(
                attributeTarget(),
                JavaFileObjects.forSourceString("test.MissingCapturedValueEvents", """
                        package test;

                        import net.eventframework.annotation.FabricEvent;
                        import net.eventframework.annotation.HandleEvent;
                        import net.eventframework.annotation.InjectionPosition;

                        @FabricEvent(AttributeTarget.class)
                        public final class MissingCapturedValueEvents {
                            @HandleEvent(
                                    position = InjectionPosition.RETURN,
                                    nameMethod = "createPlayerAttributes",
                                    captureReturnValue = true
                            )
                            public static void addMind() {}
                        }
                        """));

        assertThat(compilation).failed();
        assertThat(compilation).hadErrorContaining(
                "captureReturnValue requires the last handler parameter "
                        + "to accept the target method's return value");
    }

    private static Compilation compile(JavaFileObject... projectSources) {
        List<JavaFileObject> sources = new ArrayList<>(externalApiStubs());
        sources.addAll(List.of(projectSources));

        return javac()
                .withProcessors(new AnnotationProcessor())
                .compile(sources);
    }

    private static JavaFileObject attributeTarget() {
        return JavaFileObjects.forSourceString("test.AttributeTarget", """
                package test;

                public final class AttributeTarget {
                    private AttributeTarget() {}

                    public static void staticOperation() {}

                    public static Builder createPlayerAttributes() {
                        return new Builder();
                    }

                    public static final class Builder {
                        public Builder add(Object attribute) {
                            return this;
                        }
                    }
                }
                """);
    }

    private static JavaFileObject targetWithArgument() {
        return JavaFileObjects.forSourceString("test.TargetWithArgument", """
                package test;

                public final class TargetWithArgument {
                    private TargetWithArgument() {}

                    public static Builder create(String id) {
                        return new Builder();
                    }

                    public static final class Builder {}
                }
                """);
    }

    private static JavaFileObject voidTarget() {
        return JavaFileObjects.forSourceString("test.VoidTarget", """
                package test;

                public final class VoidTarget {
                    private VoidTarget() {}

                    public static void initialize() {}
                }
                """);
    }

    private static JavaFileObject argumentEvents() {
        return JavaFileObjects.forSourceString("test.ArgumentEvents", """
                package test;

                import net.eventframework.annotation.FabricEvent;
                import net.eventframework.annotation.HandleEvent;
                import net.eventframework.annotation.InjectionPosition;

                @FabricEvent(TargetWithArgument.class)
                public final class ArgumentEvents {
                    private ArgumentEvents() {}

                    @HandleEvent(
                            position = InjectionPosition.RETURN,
                            nameMethod = "create",
                            captureReturnValue = true
                    )
                    public static void afterCreate(
                            String id,
                            Object result
                    ) {}
                }
                """);
    }

    private static JavaFileObject validMindEvents() {
        return JavaFileObjects.forSourceString("test.MindEvents", """
                package test;

                import net.eventframework.annotation.FabricEvent;
                import net.eventframework.annotation.HandleEvent;
                import net.eventframework.annotation.InjectionPosition;

                @FabricEvent(AttributeTarget.class)
                public final class MindEvents {
                    private MindEvents() {}

                    @HandleEvent(
                            position = InjectionPosition.RETURN,
                            nameMethod = "createPlayerAttributes",
                            captureReturnValue = true
                    )
                    public static void addMind(
                            AttributeTarget.Builder result
                    ) {
                        result.add("mind");
                    }
                }
                """);
    }

    private static String generatedSourceEndingWith(
            Compilation compilation,
            String fileName) {
        JavaFileObject generated = compilation.generatedSourceFiles().stream()
                .filter(file -> file.toUri().toString().endsWith(fileName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Expected generated source ending with "
                                + fileName
                                + ", but generated: "
                                + compilation.generatedSourceFiles().stream()
                                        .map(file -> file.toUri().toString())
                                        .toList()));

        try {
            return generated.getCharContent(false).toString();
        } catch (IOException exception) {
            throw new AssertionError("Could not read generated source " + fileName, exception);
        }
    }

    /** Minimal Fabric and Mixin API stubs needed to compile generated sources. */
    private static List<JavaFileObject> externalApiStubs() {
        return List.of(
                JavaFileObjects.forSourceString("net.minecraft.util.ActionResult", """
                        package net.minecraft.util;

                        public enum ActionResult {
                            PASS,
                            SUCCESS,
                            FAIL
                        }
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

                            public static <T> Event<T> createArrayBacked(
                                    Class<T> type,
                                    Function<T[], T> factory
                            ) {
                                return null;
                            }
                        }
                        """),
                JavaFileObjects.forSourceString("org.spongepowered.asm.mixin.Mixin", """
                        package org.spongepowered.asm.mixin;

                        import java.lang.annotation.ElementType;
                        import java.lang.annotation.Target;

                        @Target(ElementType.TYPE)
                        public @interface Mixin {
                            Class<?> value();
                        }
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
                JavaFileObjects.forSourceString(
                        "org.spongepowered.asm.mixin.injection.callback.CallbackInfo",
                        """
                        package org.spongepowered.asm.mixin.injection.callback;

                        public class CallbackInfo {
                            public void cancel() {}
                        }
                        """),
                JavaFileObjects.forSourceString(
                        "org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable",
                        """
                        package org.spongepowered.asm.mixin.injection.callback;

                        public class CallbackInfoReturnable<T> extends CallbackInfo {
                            public T getReturnValue() {
                                return null;
                            }

                            public void setReturnValue(T value) {}
                        }
                        """));
    }
}
