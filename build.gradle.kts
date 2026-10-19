plugins {
    java
    id("org.springframework.boot") version "3.4.0" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

allprojects {
    group = "com.eia"
    version = "0.1.0-SNAPSHOT"
    repositories { mavenCentral() }
}

subprojects {
    apply(plugin = "java")
    java {
        toolchain { languageVersion = JavaLanguageVersion.of(22) }
    }
    tasks.withType<Test> { useJUnitPlatform() }
}
