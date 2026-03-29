plugins {
    id("java")
}

group = "net.eventframework"
version = "1.0.0"

repositories {
    mavenCentral()
}
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    implementation("com.palantir.javapoet:javapoet:0.12.0")
    annotationProcessor ("com.google.auto.service:auto-service:1.0-rc5")
    compileOnly ("com.google.auto.service:auto-service:1.0-rc5")
}

tasks.test {
    useJUnitPlatform()
}