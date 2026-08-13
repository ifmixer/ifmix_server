package com.ifmix.api.core.customer

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(scanBasePackages = ["com.ifmix.api.core.common", "com.ifmix.api.core.customer"])
class CustomerApplication

fun main(args: Array<String>) {
    runApplication<CustomerApplication>(*args)
}
