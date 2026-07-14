# Event Framework

A Fabric annotation processor that automatically generates all the Mixin boilerplate
for events in a Minecraft mod. Instead of hand-writing callback interfaces, mixins,
and registrars — you just annotate a method, and the processor generates everything
else at compile time.

---

## Table of Contents

1. [How It Works](#how-it-works)
2. [Installation](#installation)
3. [Quick Start](#quick-start)
4. [Annotations in Detail](#annotations-in-detail)
5. [`injectSelf` — Accessing `this`](#injectself--accessing-this)
6. [Return Values](#return-values)
7. [Registering Events — the Most Common Gotcha](#registering-events--the-most-common-gotcha)
8. [Injection Positions (`InjectionPosition`)](#injection-positions-injectionposition)
9. [Compile-time Validation](#compile-time-validation)
10. [Full Examples](#full-examples)
11. [Troubleshooting (FAQ)](#troubleshooting-faq)
12. [Requirements](#requirements)

---

## How It Works

You write a single class with plain static methods:

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

At compile time, the processor generates three files:

```java
// LivingEntityFallHEADCallback.java — the event interface
public interface LivingEntityFallHEADCallback {
    Event<LivingEntityFallHEADCallback> EVENT = EventFactory.createArrayBacked(...);
    ActionResult handle(LivingEntity self, double distance, boolean onGround, BlockState state, BlockPos pos);
}

// LivingEntityFallHEADMixin.java — the actual mixin injected into the game
@Mixin(LivingEntity.class)
public abstract class LivingEntityFallHEADMixin {
    @Inject(method = "fall", at = @At("HEAD"), cancellable = true)
    private void onFall(double distance, boolean onGround, BlockState state, BlockPos pos, CallbackInfo ci) {
        ActionResult result = LivingEntityFallHEADCallback.EVENT.invoker()
            .handle((LivingEntity)(Object) this, distance, onGround, state, pos);
        if (result == ActionResult.FAIL) ci.cancel();
    }
}

// MyEventsRegistrar.java — the registrar that wires your method to the event
public class MyEventsRegistrar {
    public static void register() {
        LivingEntityFallHEADCallback.EVENT.register((self, distance, onGround, state, pos) -> {
            return MyEvents.onFall(self, distance, onGround, state, pos);
        });
    }
}
```

The processor also patches the generated mixin into your `*.mixins.json` automatically.

> ⚠️ The processor does **not** call `register()` for you — that's the one manual
> step you still need to do yourself. See [Registering Events](#registering-events--the-most-common-gotcha)
> for details.

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

### As a Maven dependency (via GitHub Packages)

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
### As a Maven dependency (via JitPack)

```groovy
repositories {
  mavenCentral()
  maven { url 'https://www.jitpack.io' }
}

dependencies {
  //Your dependencies
  implementation 'com.github.MrBlazer335.event-framework:AnnotationProcess:v1.0.0'
}
```

After adding the dependency, make sure to rebuild the project (`./gradlew clean build`)
so your IDE and Gradle both pick up the annotation processor.

---

## Quick Start

Step by step — from zero to a working event in 5 steps.

### Step 1. Create an events class

```java
package com.example.mymod.events;

import net.eventframework.annotation.*;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.block.BlockState;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;

@FabricEvent(LivingEntity.class)
public class FallEvents {

    @HandleEvent(position = InjectionPosition.HEAD, nameMethod = "fall", injectSelf = true)
    public static ActionResult onFall(LivingEntity self, double distance, boolean onGround, BlockState state, BlockPos pos) {
        if (!onGround) return ActionResult.PASS;
        if (!(self instanceof PlayerEntity player)) return ActionResult.PASS;
        if (self.fallDistance <= 3.0f) return ActionResult.PASS;

        player.addExperience(100);
        return ActionResult.PASS;
    }
}
```

### Step 2. Build the project

```
./gradlew build
```

The processor generates the `Callback`, `Mixin`, and `Registrar` classes and
patches `*.mixins.json`. You can confirm it worked by checking the build log —
you should see lines like:

```
Note: Detected mod id: your-mod-id
Note: Found existing mixin config: your-mod-id.mixins.json
Note: mixin config written to: ...
```

### Step 3. Register the event

In `onInitialize()` (or `onInitializeClient()` for a client-side event), call the
generated `Registrar`:

```java
public class MyMod implements ModInitializer {
    @Override
    public void onInitialize() {
        FallEventsRegistrar.register();
    }
}
```

### Step 4. Launch the game

```
./gradlew runClient
```

### Step 5. Verify

Join the game and fall from a height — `player.addExperience(100)` should fire.
If nothing happens, see [Troubleshooting](#troubleshooting-faq).

---

## Annotations in Detail

### `@FabricEvent`

Applied to a class. Declares which Minecraft class all the events inside this
class will inject into.

```java
@FabricEvent(LivingEntity.class)
public class MyEvents { ... }
```

| Parameter | Type | Description |
|-----------|------|--------------|
| `value` | `Class<?>` | The Minecraft class to inject into |

A single `@FabricEvent` class can hold any number of `@HandleEvent` methods —
they're all collected into one `Registrar` with a single `register()` method.

---

### `@HandleEvent`

Applied to a **public static** method inside a `@FabricEvent` class. Defines a
single event.

```java
@HandleEvent(
    position = InjectionPosition.HEAD,
    nameMethod = "fall",
    injectSelf = true
)
public static ActionResult onFall(LivingEntity self, ...) { ... }
```

| Parameter | Type | Default | Description |
|-----------|------|---------|--------------|
| `position` | `InjectionPosition` | — | Where to inject (`HEAD`, `TAIL`, `INVOKE`, `RETURN`) |
| `nameMethod` | `String` | — | Name of the target method in the Minecraft class |
| `injectSelf` | `boolean` | `false` | If `true`, injects `this` from the mixin as the first parameter |

**Method requirements:**

- must be `public static`;
- must return `void` or `ActionResult` (no other type — including `boolean` —
  is allowed; the compiler will reject it);
- its parameters (after `self`, if `injectSelf = true`) must match the target
  method's parameters in count and be type-compatible.

If the target method `nameMethod` is overloaded (multiple methods share that
name), the processor reports a compile error and asks you to disambiguate.

---

## `injectSelf` — Accessing `this`

When injecting into an instance method, you often need access to the instance
itself (the equivalent of `(TargetClass)(Object) this` in a plain mixin).

Set `injectSelf = true` and declare the **first parameter** as a type
compatible with the target class:

```java
// self = (LivingEntity)(Object) this, injected automatically
@HandleEvent(position = InjectionPosition.HEAD, nameMethod = "fall", injectSelf = true)
public static ActionResult onFall(LivingEntity self, double distance, ...) {
    if (self instanceof PlayerEntity player) {
        float totalFall = self.fallDistance;
    }
    return ActionResult.PASS;
}
```

**Important — parameter order:** `self` must be the **first** parameter of the
method; every other parameter follows it in the same order as the target method.

```java
// ✅ correct — self is first
public static ActionResult onFall(LivingEntity self, double distance, boolean onGround, BlockState state, BlockPos pos)

// ❌ incorrect — self is not first; the processor will try to match distance
//    (a double) against the target class LivingEntity and fail to compile
public static ActionResult onFall(double distance, boolean onGround, BlockState state, BlockPos pos, LivingEntity self)
```

The processor verifies that the first parameter's type is a supertype of the
target class. If it isn't, you get a compile error with a red underline in the
IDE — before you ever launch the game.

If `injectSelf = true` but the method has no parameters at all, that's also a
compile error: you need at least one parameter to hold `self`.

---

## Return Values

Handler methods must return either `void` or `ActionResult`.

| Value | Effect |
|-------|--------|
| `ActionResult.PASS` | Continue — vanilla logic runs normally |
| `ActionResult.SUCCESS` | Event handled — vanilla logic still runs |
| `ActionResult.FAIL` | Cancel — vanilla logic is skipped |

What exactly happens on `FAIL` depends on what the **target** method returns:

- **target method is `void`** → a `CallbackInfo` is generated; `FAIL` triggers `ci.cancel()`;
- **target method is `boolean`** → a `CallbackInfoReturnable<Boolean>` is generated;
  `ActionResult.SUCCESS` becomes `ci.setReturnValue(true)`, `ActionResult.FAIL`
  becomes `ci.setReturnValue(false)`, and `PASS` leaves the return value untouched.

You never have to think about `CallbackInfo` vs. `CallbackInfoReturnable` yourself
— the processor determines the correct type automatically from the target
method's signature.

If multiple handlers are subscribed to the same event, they run in order; as
soon as one returns a non-`PASS` value, the remaining listeners are skipped and
that result is returned immediately.

---

## Registering Events — the Most Common Gotcha

The processor generates a `<YourClassName>Registrar` class with a `register()`
method, but it **does not call it for you**. If you forget this step, the
project compiles without a single error, the mixin injects correctly into the
game, but the event's listener list stays empty and your handler simply never
runs. No exception, no warning — just silence, as if the event didn't exist.

Make sure to call `register()` in `onInitialize()` (for common/server-side
logic) or `onInitializeClient()` (for client-side logic):

```java
public class MyMod implements ModInitializer {
    @Override
    public void onInitialize() {
        FallEventsRegistrar.register();
        AnotherEventsRegistrar.register();
        // ...one call per @FabricEvent class
    }
}
```

If you have multiple `@FabricEvent` classes, each one gets its own `Registrar`,
and `register()` needs to be called on every single one of them.

---

## Injection Positions (`InjectionPosition`)

| Value | Mixin `@At` equivalent | Description |
|-------|--------------------------|--------------|
| `HEAD` | `@At("HEAD")` | Beginning of the method |
| `TAIL` | `@At("TAIL")` | End of the method, before return |
| `RETURN` | `@At("RETURN")` | Every return statement |
| `INVOKE` | `@At("INVOKE")` | Before a method call inside the target |

Use `HEAD` when you need to intercept before vanilla logic does anything (e.g.
to cancel an action). Use `TAIL`/`RETURN` when the result of vanilla logic
matters (e.g. a field's final value before the method exits).

---

## Compile-time Validation

The processor validates your annotations before the game ever runs and
surfaces errors directly in the IDE:

```java
// ❌ method must be static
@HandleEvent(position = InjectionPosition.HEAD, nameMethod = "fall")
public ActionResult onFall(int amount) { ... }

// ❌ method must be public
@HandleEvent(position = InjectionPosition.HEAD, nameMethod = "fall")
private static ActionResult onFall(int amount) { ... }

// ❌ String is not a supertype of LivingEntity
@HandleEvent(position = InjectionPosition.HEAD, nameMethod = "fall", injectSelf = true)
public static ActionResult onFall(String wrong, ...) { ... }

// ❌ injectSelf=true requires at least one parameter
@HandleEvent(position = InjectionPosition.HEAD, nameMethod = "fall", injectSelf = true)
public static ActionResult onFall() { ... }

// ❌ return type must be void or ActionResult
@HandleEvent(position = InjectionPosition.HEAD, nameMethod = "fall")
public static boolean onFall(int amount) { ... }

// ❌ method "fall" was not found in the target class
@HandleEvent(position = InjectionPosition.HEAD, nameMethod = "doesNotExist")
public static ActionResult onMissing(int amount) { ... }

// ❌ method "fall" is overloaded — needs disambiguation
@HandleEvent(position = InjectionPosition.HEAD, nameMethod = "run")
public static ActionResult onRun(int amount) { ... } // if both run(int) and run(String) exist
```

---

## Full Examples

### Example 1 — canceling fall damage

```java
@FabricEvent(LivingEntity.class)
public class FallDamageEvents {

    @HandleEvent(position = InjectionPosition.HEAD, nameMethod = "fall", injectSelf = true)
    public static ActionResult onFall(LivingEntity self, double distance, boolean onGround, BlockState state, BlockPos pos) {
        if (!onGround) return ActionResult.PASS;
        if (!(self instanceof PlayerEntity player)) return ActionResult.PASS;

        if (player.isCreative() || player.isSpectator()) {
            return ActionResult.FAIL; // cancel the fall entirely
        }
        return ActionResult.PASS;
    }
}
```

### Example 2 — a void handler with no cancellation (just a side effect)

```java
@FabricEvent(PlayerEntity.class)
public class PlayerJoinLogEvents {

    @HandleEvent(position = InjectionPosition.HEAD, nameMethod = "tick")
    public static void onTick() {
        // void handlers can't cancel anything, only run a side effect
        System.out.println("tick!");
    }
}
```

### Example 3 — an event whose vanilla target returns `boolean`

```java
@FabricEvent(LivingEntity.class)
public class CanUseEvents {

    @HandleEvent(position = InjectionPosition.HEAD, nameMethod = "canUse")
    public static ActionResult onCanUse() {
        return ActionResult.SUCCESS; // translated to ci.setReturnValue(true)
    }
}
```

Registration (don't forget!):

```java
public class MyMod implements ModInitializer {
    @Override
    public void onInitialize() {
        FallDamageEventsRegistrar.register();
        PlayerJoinLogEventsRegistrar.register();
        CanUseEventsRegistrar.register();
    }
}
```

---

## Troubleshooting (FAQ)

**The event doesn't fire, and there are no errors at all.**
Almost always — a missing call to `<Class>Registrar.register()` inside
`onInitialize()`. See [Registering Events](#registering-events--the-most-common-gotcha).

**Compile error: "Target method 'X' was not found".**
No method with that name exists in the class you passed to `@FabricEvent`, or
the mappings (Yarn/Mojmap) in your classpath don't match what you expect —
check the method's real name by decompiling / running `javap` against your
Minecraft version.

**Error: "Target method 'X' is overloaded".**
Multiple methods share that name in the target class — the processor doesn't
yet support matching by descriptor, so make sure the name unambiguously
identifies the method you want (or target a different, non-overloaded method).

**Error: "first parameter must be compatible with the target class".**
With `injectSelf = true`, the first parameter of your handler must actually be
first in the parameter list and have a type no narrower than the class passed
to `@FabricEvent`. This usually means your `self` parameter isn't in the first
position — move it there.

**Everything compiles, the build log is clean, but nothing happens in-game
after editing code.**
Check whether Gradle reported `UP-TO-DATE` — sometimes after small edits
Gradle decides a rebuild isn't needed and runs stale compiled code. Run
`./gradlew clean build`, then `runClient`.

**The mixin throws an error at game startup (`InvalidInjectionException` /
`Critical injection failure`).**
This means `@At(position)` couldn't find a matching injection point in the
target method's actual bytecode — either the method has a different
name/signature in your version/mappings than expected, or you're targeting
the wrong class (e.g. injecting into `LivingEntity` when the method you need
is only overridden, with a different signature, in `PlayerEntity`).

**I want to confirm the processor actually found my mixin config.**
Check the build log — you should see lines like `Detected mod id`, `Found
existing mixin config` (or `Will create new mixin config`), and `mixin
config written to`. If those lines are missing, the processor couldn't
locate your project root (no `build.gradle`/`pom.xml` next to `src`), and
`*.mixins.json` was never updated.

---

## Requirements

- Java 21
- Fabric Loader 0.16+
- Fabric API
- Minecraft 1.21.x (Yarn mappings)
