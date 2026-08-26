plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("com.netflix.dgs.codegen")
    id("com.google.devtools.ksp")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3")
    // Spring Boot 4 使用 Jackson 3（tools.jackson），需用 Jackson 3 的 Kotlin 模块，
    // 否则 data class 的 Kotlin 默认值（缺失字段）不会生效。
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")



    // Coroutines (needed for runBlocking in AntiqueService to call suspend ScanRunner)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation("com.willowtreeapps.assertk:assertk-jvm:0.28.1")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // WebTestClient for E2E testing (Spring Boot 4 removed TestRestTemplate)
    testImplementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation(platform("software.amazon.awssdk:bom:2.31.7"))
    implementation("software.amazon.awssdk:s3")

    // Spring AI — OpenAI-compatible model provider (Agnes AI backend)
    // 2.0.x 才兼容 Spring Boot 4.0/4.1（1.0.x 面向 Boot 3.x，引用了已被移除的 RestClient AutoConfiguration 旧包名）
    implementation(platform("org.springframework.ai:spring-ai-bom:2.0.0"))
    implementation("org.springframework.ai:spring-ai-starter-model-openai") {
        exclude(group = "io.swagger.core.v3", module = "swagger-annotations")
    }
    implementation("com.openai:openai-java-client-okhttp:4.39.1") {
        exclude(group = "io.swagger.core.v3", module = "swagger-annotations")
    }
    // Netty macOS DNS resolver — 防止 R2/S3 endpoint DNS 解析卡住
    implementation("io.netty:netty-resolver-dns-native-macos::osx-aarch_64")

    // Auth — social login + JWT + refresh token
    implementation("org.springframework.security:spring-security-oauth2-jose")
    implementation("com.nimbusds:nimbus-jose-jwt:9.40")
    implementation("com.google.crypto.tink:tink:1.15.0")

    // === Jimmer + PostgreSQL ===
    val jimmerVersion = rootProject.extra["jimmerVersion"] as String
    implementation("org.babyfish.jimmer:jimmer-spring-boot-starter:$jimmerVersion")
    implementation("org.babyfish.jimmer:jimmer-sql-kotlin:$jimmerVersion")
    ksp("org.babyfish.jimmer:jimmer-ksp:$jimmerVersion")

    // UUIDv7 generator (cursor pagination requires time-ordered IDs)
    implementation("com.fasterxml.uuid:java-uuid-generator:5.1.0")

    // PostgreSQL JDBC
    implementation("org.postgresql:postgresql")

    // Flyway
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // Testcontainers PostgreSQL（测试）
    testImplementation("org.testcontainers:postgresql:1.20.6")
    testImplementation("org.testcontainers:junit-jupiter:1.20.6")

    // Testcontainers Redis (E2E)
    testImplementation("com.redis:testcontainers-redis:2.2.4")

    // WireMock for external API mocking (wechat E2E tests)
    testImplementation("org.wiremock:wiremock-standalone:3.12.1")

    // H2 for routing tests (needed in task 3)
    testImplementation("com.h2database:h2")

    // GraphQL (Netflix DGS Framework 12.x)
    implementation(platform("com.netflix.graphql.dgs:graphql-dgs-platform-dependencies:12.0.1"))
    implementation("com.netflix.graphql.dgs:graphql-dgs-spring-graphql-starter")
    implementation("com.jayway.jsonpath:json-path:3.0.0")  // DGS 12 Jackson3 需要 json-path 3.x
    testImplementation("com.netflix.graphql.dgs:graphql-dgs-client")
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
    sourceSets {
        main {
            kotlin.srcDir("build/generated/ksp/main/kotlin")
        }
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8")
}

// Jimmer KSP 配置
ksp {
    // Jimmer DTO 文件位置（相对于 project root）
    arg("jimmer.dto.dirs", "src/main/dto")
    // 生成 Kotlin 代码
    arg("jimmer.language", "kotlin")
    // input DTO 中 nullable 属性默认使用 dynamic 修饰（不传=不修改）
    arg("jimmer.dto.defaultNullableInputModifier", "fuzzy")
}

// DGS Codegen — 从 .graphqls schema 生成 Kotlin input/payload/enum types
tasks.withType<com.netflix.graphql.dgs.codegen.gradle.GenerateJavaTask> {
    // 生成代码的包名
    packageName = "com.ifmix.api.core.generated"
    language = "kotlin"
    generateClient = true       // 生成类型安全 client（测试用）
    generateDataTypes = true    // 生成 input/type data classes
    snakeCaseConstantNames = true

    // schema 文件位置（包含 common + customer 目录）
    schemaPaths = mutableListOf(
        "${projectDir}/src/main/resources/schema/common",
        "${projectDir}/src/main/resources/schema/customer",
    )

    // 类型映射：GraphQL output type → Domain Model data class（不生成 data class）
    // input types / payload types / enums 不在此映射，由 codegen 生成
    typeMapping = mutableMapOf(
        // Scalars
        "UUID" to "java.util.UUID",
        "DateTime" to "java.time.Instant",
        "JSON" to "kotlin.Any",
        // Entity output types → Domain Model data classes
        "Todo" to "com.ifmix.api.core.entity.demo.Todo",
        "TodoItem" to "com.ifmix.api.core.entity.demo.TodoItem",
        "ScanRecord" to "com.ifmix.api.core.entity.ai.ScanRecord",
        "ScanCollection" to "com.ifmix.api.core.entity.ai.ScanCollection",
        "ScanCollectionItem" to "com.ifmix.api.core.entity.ai.ScanCollectionItem",
        "ImageRef" to "com.ifmix.api.core.entity.ai.ImageRef",
        // OperationResult: 手写类型，不再由 codegen 生成
        "OperationResult" to "com.ifmix.api.core.dto.common.OperationResult",
        // PageInfo: 手写类型
        "PageInfo" to "com.ifmix.api.core.dto.common.PageInfo",
        // Page types → 通用 Page<T>
        "TodoPage" to "com.ifmix.api.core.dto.common.Page",
        "ScanRecordPage" to "com.ifmix.api.core.dto.common.Page",
        "ScanCollectionItemPage" to "com.ifmix.api.core.dto.common.Page",
    )
}
