package net.eventframework.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.METHOD)
public @interface HandleEvent {
    public InjectionPosition position();
    public String nameMethod();
    // If true — the processor will inject `this` as the first parameter,
    // allowing the handler to cast it to the target class type.
    // Use when you need access to the mixin target instance (e.g. (Sheep)(Object) this)
    boolean injectSelf() default false;

    // The type to cast `this` to when injectSelf = true.
    // Defaults to Object if not specified — you'll need to cast manually.
    Class<?> selfType() default Object.class;

    boolean returnable() default false;

    Class<?> returnType() default Void.class;
}
