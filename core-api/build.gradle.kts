plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("com.google.devtools.ksp")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
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

    testImplementation("com.willowtreeapps.assertk:assertk-jvm:0.28.1")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.mockito:mockito-core:5.14.2")
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
    // 2.0.x 才兼容 Spring Boot 4.0/4.1（1.0.x 面向 Boot 3.x，引用了已被移除的 RestClient AutoConfiguration 旧包名）
    implementation(platform("org.springframework.ai:spring-ai-bom:2.0.0"))
    implementation("org.springframework.ai:spring-ai-starter-model-openai")

    // Auth — social login + JWT + refresh token
    implementation("org.springframework.security:spring-security-oauth2-jose")
    implementation("com.nimbusds:nimbus-jose-jwt:9.40")
    implementation("com.google.crypto.tink:tink:1.15.0")

    // === Jimmer + PostgreSQL ===
    val jimmerVersion: String by rootProject.extra
    implementation("org.babyfish.jimmer:jimmer-spring-boot-starter:$jimmerVersion")
    implementation("org.babyfish.jimmer:jimmer-sql-kotlin:$jimmerVersion")
    ksp("org.babyfish.jimmer:jimmer-ksp:$jimmerVersion")

    // PostgreSQL JDBC
    implementation("org.postgresql:postgresql")

    // Flyway
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // Testcontainers PostgreSQL（测试）
    testImplementation("org.testcontainers:postgresql:1.20.6")

    // H2 for routing tests (needed in task 3)
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
}

// Jimmer KSP 配置
ksp {
    // Jimmer DTO 文件位置（相对于 project root）
    arg("jimmer.dto.dirs", "src/main/dto")
    // 生成 Kotlin 代码
    arg("jimmer.language", "kotlin")
}
