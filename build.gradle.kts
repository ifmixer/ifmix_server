plugins {
    kotlin("plugin.spring") version "2.3.10" apply false
    id("org.springframework.boot") version "4.1.0" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("com.netflix.dgs.codegen") version "8.6.0" apply false
    id("com.google.devtools.ksp") version "2.3.10" apply false
    kotlin("jvm") version "2.3.10"
}

allprojects {
    group = "com.ifmix"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

extra["jimmerVersion"] = "0.11.5"
dependencies {
    implementation(kotlin("stdlib-jdk8"))
}
repositories {
    mavenCentral()
}
kotlin {
    jvmToolchain(8)
}
