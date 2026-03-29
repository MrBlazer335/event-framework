package net.eventframework.annotation;

public enum InjectionPosition {
    HEAD("HEAD"),
    TAIL("TAIL"),
    RETURN("RETURN"),
    INVOKE("INVOKE"),
    FIELD("FIELD"),
    CONSTANT("CONSTANT"),
    ;

    InjectionPosition(String s) {
        this.value = s;
    }

    private String value;

    public String getValue() {
        return value;
    }
}
