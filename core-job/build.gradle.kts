plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":core-common"))

    // 非 web：不引 starter-web。仅需 core + batch + jdbc。
    implementation("org.springframework.boot:spring-boot-starter")
    implementation("org.springframework.boot:spring-boot-starter-batch")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // install attestation（WP-E）：解析 app_attest_config JSONB 与 Apple 响应。
    // Spring Boot 4 用 Jackson 3（tools.jackson）；JsonMapper 手动构建，不引 starter-web。
    implementation("tools.jackson.module:jackson-module-kotlin")
    // DeviceCheck ES256 JWT（fraud metric 刷新）；版本与 core-api 对齐（core-api 已有 9.40 先例）。
    implementation("com.nimbusds:nimbus-jose-jwt:9.40")

    implementation("org.postgresql:postgresql")

    testImplementation("com.willowtreeapps.assertk:assertk-jvm:0.28.1")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.batch:spring-batch-test")
    // 单测用内存 PG 模式库（JdbcClient 真 SQL 行为，避免 mock fluent 链）
    testImplementation("com.h2database:h2")
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
