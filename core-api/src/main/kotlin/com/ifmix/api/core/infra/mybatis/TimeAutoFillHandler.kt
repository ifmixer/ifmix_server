package com.ifmix.api.core.infra.mybatis

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler
import org.apache.ibatis.reflection.MetaObject
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * 自动填充 createdAt / updatedAt 字段。
 *
 * - INSERT 时同时填充 createdAt 和 updatedAt
 * - UPDATE 时只更新 updatedAt
 *
 * 配合 entity 上的 @TableField(fill = FieldFill.INSERT) / @TableField(fill = FieldFill.INSERT_UPDATE) 注解使用。
 */
@Component
class TimeAutoFillHandler : MetaObjectHandler {

    override fun insertFill(metaObject: MetaObject) {
        val now = Instant.now()
        setFieldValByName("createdAt", now, metaObject)
        setFieldValByName("updatedAt", now, metaObject)
    }

    override fun updateFill(metaObject: MetaObject) {
        setFieldValByName("updatedAt", Instant.now(), metaObject)
    }
}
