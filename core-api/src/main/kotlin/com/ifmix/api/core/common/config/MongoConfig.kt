package com.ifmix.api.core.common.config

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Configuration
import org.springframework.data.mongodb.core.convert.DefaultMongoTypeMapper
import org.springframework.data.mongodb.core.convert.MappingMongoConverter

/** 去掉文档里的 _class 类型提示，保持存储整洁。 */
@Configuration
class MongoConfig {

    @Autowired
    fun removeTypeHint(converter: MappingMongoConverter) {
        converter.setTypeMapper(DefaultMongoTypeMapper(null))
    }
}
