plugins {
    kotlin("jvm")
}

dependencies {
    // 配置属性绑定用（ClusterProperties 用 @ConfigurationProperties），
    // 但 core-common 是纯库，不引 spring-boot，仅需 spring-context 注解可选。
    // 目前放入的类无需 Spring 注解即可编译；如需 @ConfigurationProperties 由使用方模块提供。
    implementation("com.fasterxml.uuid:java-uuid-generator:5.1.0")
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}
