package com.ifmix.api.core.common.jimmer.cluster

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(ClusterProperties::class)
class JimmerConfig
