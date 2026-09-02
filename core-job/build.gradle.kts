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

    implementation("org.postgresql:postgresql")

    testImplementation("com.willowtreeapps.assertk:assertk-jvm:0.28.1")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.batch:spring-batch-test")
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
