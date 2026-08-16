plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("com.google.devtools.ksp")
    id("com.netflix.dgs.codegen") version "8.4.0"
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-mongodb")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3")
    // Spring Boot 4 使用 Jackson 3（tools.jackson），需用 Jackson 3 的 Kotlin 模块，
    // 否则 data class 的 Kotlin 默认值（缺失字段）不会生效。
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Konvert：KSP 编译期生成 DTO<->document 映射（Kotlin 2.3.10 + KSP 2.3.10）
    implementation("io.mcarle:konvert-api:4.5.0")
    ksp("io.mcarle:konvert:4.5.0")
    // 自定义 Konvert TypeConverter 所需依赖（ObjectId↔String 转换）
    implementation("com.google.devtools.ksp:symbol-processing-api")
    implementation("com.squareup:kotlinpoet-jvm:2.2.0")
    implementation("io.mcarle:konvert-converter-api:4.5.0")
    implementation("io.mcarle:konvert-converter:4.5.0")

    testImplementation("com.willowtreeapps.assertk:assertk-jvm:0.28.1")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.mockito:mockito-core:5.14.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:mongodb:1.20.6")
    testImplementation("org.testcontainers:junit-jupiter:1.20.6")

    // AWS SDK v2 for S3 pre-signed URLs
    implementation(platform("software.amazon.awssdk:bom:2.31.7"))
    implementation("software.amazon.awssdk:s3")

    // Spring AI — OpenAI-compatible model provider (Agnes AI backend)
    // 2.0.x 才兼容 Spring Boot 4.0/4.1（1.0.x 面向 Boot 3.x，引用了已被移除的 RestClientAutoConfiguration 旧包名）
    implementation(platform("org.springframework.ai:spring-ai-bom:2.0.0"))
    implementation("org.springframework.ai:spring-ai-starter-model-openai")

    // Auth — social login + JWT + refresh token
    implementation("org.springframework.security:spring-security-oauth2-jose")
    implementation("com.nimbusds:nimbus-jose-jwt:9.40")
    implementation("com.google.crypto.tink:tink:1.15.0")

    // Netflix DGS 12 — GraphQL server (Spring Boot 4 MVC, DataLoader, Micrometer)
    implementation(platform("com.netflix.graphql.dgs:graphql-dgs-platform-dependencies:12.0.0"))
    implementation("com.netflix.graphql.dgs:graphql-dgs-spring-graphql-starter")
    implementation("com.netflix.graphql.dgs:graphql-dgs-extended-scalars")
    implementation("com.netflix.graphql.dgs:graphql-dgs-spring-boot-micrometer")
    // json-path 3.x — DGS 12 需要 Jackson3JsonProvider
    implementation("com.jayway.jsonpath:json-path:3.0.0")

    // DGS Codegen — 编译期从 schema 生成 Kotlin 类型（仅开发时运行）
    developmentOnly("com.netflix.graphql.dgs.codegen:graphql-dgs-codegen-core")
}

// DGS Codegen — 从 schema.graphqls 生成 Kotlin data class
tasks.named<com.netflix.graphql.dgs.codegen.gradle.GenerateJavaTask>("generateJava") {
    language = "KOTLIN"
    packageName = "com.ifmix.api.core.graphql.generated"
    generatedSourcesDir = file("build/generated/dgs").absolutePath
    @Suppress("UNCHECKED_CAST")
    // DGS Codegen — 扫描 schema/ 下所有 .graphqls 文件（自动合并）
    schemaPaths = mutableListOf(project.rootProject.projectDir.absolutePath + "/core-api/src/main/resources/schema")
    generateClient = false
    generateDataTypes = true
    @Suppress("UNCHECKED_AS")
    typeMapping = mutableMapOf(
        "DateTime" to "java.time.Instant",
        "JSON" to "Map<String, Any?>",
        "Long" to "kotlin.Long",
    )
    includeQueries = mutableListOf<String>()
    includeMutations = mutableListOf<String>()
}

// Ensure generated sources are compiled before Kotlin compilation
tasks.named("compileKotlin") {
    dependsOn("generateJava")
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }

    sourceSets {
        main {
            kotlin {
                srcDir("build/generated/dgs/generated/sources/dgs-codegen")
            }
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
