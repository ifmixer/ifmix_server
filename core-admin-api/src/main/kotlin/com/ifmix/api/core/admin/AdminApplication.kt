package com.ifmix.api.core.admin

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(scanBasePackages = ["com.ifmix.api.core.common", "com.ifmix.api.core.admin"])
class AdminApplication

fun main(args: Array<String>) {
    runApplication<AdminApplication>(*args)
}
