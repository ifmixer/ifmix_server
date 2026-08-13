plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("com.google.devtools.ksp")
}

dependencies {
    implementation(project(":core-common"))
    testImplementation(project(":core-common"))

    val jimmerVersion = rootProject.extra["jimmerVersion"] as String
    ksp("org.babyfish.jimmer:jimmer-ksp:$jimmerVersion")

    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:0.28.1")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.testcontainers:postgresql:1.20.6")
    testImplementation("org.testcontainers:junit-jupiter:1.20.6")
    testImplementation("com.redis:testcontainers-redis:2.2.4")
    testImplementation("org.wiremock:wiremock-standalone:3.12.1")
    testImplementation("com.h2database:h2")
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

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

ksp {
    arg("jimmer.language", "kotlin")
    arg("jimmer.dto.dirs", "src/main/dto")
    arg("jimmer.dto.defaultNullableInputModifier", "fuzzy")
}
