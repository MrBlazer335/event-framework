# Event Framework

A Fabric annotation processor that automatically generates Mixin boilerplate for Minecraft mod events.
Instead of writing callbacks, mixins, and registrars by hand — just annotate a method.

---

## How It Works

You write this:

```java
@FabricEvent(LivingEntity.class)
public class MyEvents {

    @HandleEvent(position = InjectionPosition.HEAD, nameMethod = "fall", injectSelf = true)
    public static ActionResult onFall(LivingEntity self, double distance, boolean onGround, BlockState state, BlockPos pos) {
        if (!(self instanceof PlayerEntity player)) return ActionResult.PASS;
        if (self.fallDistance <= 3.0f) return ActionResult.PASS;

        player.addExperience(100);
        return ActionResult.PASS;
    }
}
```

The processor generates this automatically at compile time:

```java
// LivingEntityFallCallback.java
public interface LivingEntityFallCallback {
    Event<LivingEntityFallCallback> EVENT = EventFactory.createArrayBacked(...);
    ActionResult handle(LivingEntity self, double distance, boolean onGround, BlockState state, BlockPos pos);
}

// LivingEntityFallMixin.java
@Mixin(LivingEntity.class)
public abstract class LivingEntityFallMixin {
    @Inject(method = "fall", at = @At("HEAD"), cancellable = true)
    private void onFall(double distance, boolean onGround, BlockState state, BlockPos pos, CallbackInfo ci) {
        ActionResult result = LivingEntityFallCallback.EVENT.invoker()
            .handle((LivingEntity)(Object) this, distance, onGround, state, pos);
        if (result == ActionResult.FAIL) ci.cancel();
    }
}

// MyEventsRegistrar.java
public class MyEventsRegistrar {
    public static void register() {
        LivingEntityFallCallback.EVENT.register((self, distance, onGround, state, pos) -> {
            MyEvents.onFall(self, distance, onGround, state, pos);
            return ActionResult.PASS;
        });
    }
}
```

It also patches your `*.mixins.json` automatically.

---

## Installation

### Gradle (multi-module project)

`settings.gradle`:
```groovy
include ':EventFramework'
```

`build.gradle`:
```groovy
dependencies {
    compileOnly project(':EventFramework')
    annotationProcessor project(':EventFramework')
}
```

### As a Maven dependency (coming soon via GitHub Packages)

```groovy
repositories {
    maven {
        name = "GitHubPackages"
        url = "https://maven.pkg.github.com/YOUR_USERNAME/event-framework"
        credentials {
            username = System.getenv("GITHUB_ACTOR")
            password = System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    compileOnly 'io.github.YOUR_USERNAME:event-framework:VERSION'
    annotationProcessor 'io.github.YOUR_USERNAME:event-framework:VERSION'
}
```

---

## Annotations

### `@FabricEvent`

Applied to a class. Declares which Minecraft class this group of events targets.

```java
@FabricEvent(LivingEntity.class)
public class MyEvents { ... }
```

| Parameter | Type | Description |
|-----------|------|-------------|
| `value` | `Class<?>` | The Minecraft class to inject into |

---

### `@HandleEvent`

Applied to a static method inside a `@FabricEvent` class. Defines one event.

```java
@HandleEvent(
    position = InjectionPosition.HEAD,
    nameMethod = "fall",
    injectSelf = true
)
public static ActionResult onFall(LivingEntity self, ...) { ... }
```

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `position` | `InjectionPosition` | — | Where to inject (`HEAD`, `TAIL`, `INVOKE`, `RETURN`) |
| `nameMethod` | `String` | — | Name of the target method in the Minecraft class |
| `injectSelf` | `boolean` | `false` | If `true`, injects `this` from the Mixin as the first parameter |

---

### `injectSelf` — accessing `this`

When injecting into an instance method, you often need access to the object itself
(equivalent to `(TargetClass)(Object) this` in a regular Mixin).

Set `injectSelf = true` and declare the **first parameter** as the target type:

```java
// self = (LivingEntity)(Object) this, injected automatically
@HandleEvent(position = InjectionPosition.HEAD, nameMethod = "fall", injectSelf = true)
public static ActionResult onFall(LivingEntity self, double distance, ...) {
    if (self instanceof PlayerEntity player) {
        // access player fields and methods freely
        float totalFall = self.fallDistance;
    }
    return ActionResult.PASS;
}
```

The processor validates that the first parameter type is a supertype of the target class.
If it is not, you will get a **compile error with a red underline in the IDE**.

---

## Return Values

Event handler methods must return `ActionResult`:

| Value | Effect |
|-------|--------|
| `ActionResult.PASS` | Continue — vanilla logic runs normally |
| `ActionResult.SUCCESS` | Event handled — vanilla logic still runs |
| `ActionResult.FAIL` | Cancel — vanilla logic is skipped (`ci.cancel()` or `cir.setReturnValue(FAIL)`) |

The correct Mixin callback type (`CallbackInfo` vs `CallbackInfoReturnable`) is determined
automatically based on the return type of the target method.

---

## Registering Events

After the processor generates `YourClassRegistrar`, call `register()` in your `ModInitializer`:

```java
public class MyMod implements ModInitializer {
    @Override
    public void onInitialize() {
        MyEventsRegistrar.register();
    }
}
```

---

## Compile-time Validation

The processor checks your annotations at compile time and reports errors directly in the IDE:

```java
// ❌ Compile error — String is not a supertype of LivingEntity
@HandleEvent(position = InjectionPosition.HEAD, nameMethod = "fall", injectSelf = true)
public static ActionResult onFall(String wrong, ...) { ... }
//                                ^^^^^^ underlined red in IntelliJ

// ❌ Compile error — injectSelf=true requires at least one parameter
@HandleEvent(position = InjectionPosition.HEAD, nameMethod = "fall", injectSelf = true)
public static ActionResult onFall() { ... }
```

---

## InjectionPosition Values

| Value | Mixin `@At` equivalent | Description |
|-------|------------------------|-------------|
| `HEAD` | `@At("HEAD")` | Beginning of the method |
| `TAIL` | `@At("TAIL")` | End of the method, before return |
| `RETURN` | `@At("RETURN")` | Every return statement |
| `INVOKE` | `@At("INVOKE")` | Before a method call inside the target |

---

## Requirements

- Java 21
- Fabric Loader 0.16+
- Fabric API
- Minecraft 1.21.x (Yarn mappings)
