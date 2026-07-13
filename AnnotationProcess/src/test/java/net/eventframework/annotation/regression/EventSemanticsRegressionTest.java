package net.eventframework.annotation.regression;

import static com.google.common.truth.Truth.assertThat;
import static com.google.testing.compile.CompilationSubject.assertThat;

import com.google.testing.compile.Compilation;
import org.junit.jupiter.api.Test;

class EventSemanticsRegressionTest {

    @Test
    void booleanTargetMustUseCallbackInfoReturnable() {
        var target = ProcessorTestHarness.src("example.Target",
                "package example;",
                "public class Target { public boolean canUse() { return false; } }"
        );

        var events = ProcessorTestHarness.src("example.Events",
                "package example;",
                "import net.eventframework.annotation.*;",
                "import net.minecraft.util.ActionResult;",
                "@FabricEvent(Target.class)",
                "public class Events {",
                "  @HandleEvent(position = InjectionPosition.HEAD, nameMethod = \"canUse\")",
                "  public static ActionResult on() { return ActionResult.PASS; }",
                "}"
        );

        Compilation c = ProcessorTestHarness.compile(target, events);
        assertThat(c).succeeded();

        String mixin = ProcessorTestHarness.generatedContaining(c, "on");
        assertThat(mixin).contains("CallbackInfoReturnable<Boolean>");
    }

    @Test
    void voidHandlerMustBeAllowed() {
        var target = ProcessorTestHarness.src("example.Target",
                "package example;",
                "public class Target { public void tick() {} }"
        );

        var events = ProcessorTestHarness.src("example.Events",
                "package example;",
                "import net.eventframework.annotation.*;",
                "@FabricEvent(Target.class)",
                "public class Events {",
                "  @HandleEvent(position = InjectionPosition.HEAD, nameMethod = \"tick\")",
                "  public static void onTick() {}",
                "}"
        );

        Compilation c = ProcessorTestHarness.compile(target, events);
        assertThat(c).succeeded();
    }

    @Test
    void booleanTargetMustSupportTrueAndFalse() {
        var target = ProcessorTestHarness.src("example.Target",
                "package example;",
                "public class Target { public boolean canUse() { return false; } }"
        );

        var events = ProcessorTestHarness.src("example.Events",
                "package example;",
                "import net.eventframework.annotation.*;",
                "import net.minecraft.util.ActionResult;",
                "@FabricEvent(Target.class)",
                "public class Events {",
                "  @HandleEvent(position = InjectionPosition.HEAD, nameMethod = \"canUse\")",
                "  public static ActionResult on() { return ActionResult.PASS; }",
                "}"
        );

        Compilation c = ProcessorTestHarness.compile(target, events);
        assertThat(c).succeeded();

        String mixin = ProcessorTestHarness.generatedContaining(c, "on");

        assertThat(mixin).contains("setReturnValue(true)");
        assertThat(mixin).contains("setReturnValue(false)");
        assertThat(ProcessorTestHarness.count(mixin, "setReturnValue(")).isEqualTo(2);
    }
}