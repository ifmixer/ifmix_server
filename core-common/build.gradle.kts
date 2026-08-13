plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("com.google.devtools.ksp")
}

// core-common 是纯 library，不需要打 fat jar
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") { enabled = false }
tasks.named<Jar>("jar") { enabled = true }

dependencies {
    api("org.springframework.boot:spring-boot-starter-web")
    api("org.springframework.boot:spring-boot-starter-data-redis")
    api("org.springframework.boot:spring-boot-starter-validation")
    api("tools.jackson.module:jackson-module-kotlin")
    api("org.jetbrains.kotlin:kotlin-reflect")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    val jimmerVersion = rootProject.extra["jimmerVersion"] as String
    api("org.babyfish.jimmer:jimmer-spring-boot-starter:$jimmerVersion")
    api("org.babyfish.jimmer:jimmer-sql-kotlin:$jimmerVersion")
    ksp("org.babyfish.jimmer:jimmer-ksp:$jimmerVersion")

    api("org.postgresql:postgresql")
    api("org.flywaydb:flyway-core")
    api("org.flywaydb:flyway-database-postgresql")

    api("org.springframework.security:spring-security-oauth2-jose")
    api("com.nimbusds:nimbus-jose-jwt:9.40")
    api("com.google.crypto.tink:tink:1.15.0")

    api(platform("software.amazon.awssdk:bom:2.31.7"))
    api("software.amazon.awssdk:s3")

    api(platform("org.springframework.ai:spring-ai-bom:2.0.0"))
    api("org.springframework.ai:spring-ai-starter-model-openai") {
        exclude(group = "io.swagger.core.v3", module = "swagger-annotations")
    }
    api("com.openai:openai-java-client-okhttp:4.39.1") {
        exclude(group = "io.swagger.core.v3", module = "swagger-annotations")
    }

    api("io.netty:netty-resolver-dns-native-macos::osx-aarch_64")
    api("com.fasterxml.uuid:java-uuid-generator:5.1.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:0.28.1")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.testcontainers:postgresql:1.20.6")
    testImplementation("org.testcontainers:junit-jupiter:1.20.6")
    testImplementation("com.redis:testcontainers-redis:2.2.4")
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

ksp {
    arg("jimmer.language", "kotlin")
}
