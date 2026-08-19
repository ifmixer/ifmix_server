package com.ifmix.api.core.modules.app

import com.ifmix.api.core.modules.app.handler.AppConfigHandler
import com.ifmix.api.core.modules.app.repo.AppConfigRepo
import com.ifmix.api.core.modules.app.repo.AppInfoRepo
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class AppConfigConfig {

    @Bean
    fun appConfigHandler(appConfigRepo: AppConfigRepo): AppConfigHandler = AppConfigHandler(appConfigRepo)

    @Bean
    fun appFacade(handler: AppConfigHandler): AppFacade = AppFacade(handler)
}
