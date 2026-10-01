package com.ifmix.core.api.entity.customer

/**
 * 账号删除原因编码。
 *
 * 编码：1=USER_REQUESTED（用户主动申请注销）。
 *
 * 注意：不要与 @Entity 放同一个文件——Jimmer KSP 遇到同文件其他顶层声明会静默跳过实体代码生成。
 */
object DeletionReasons {
    const val USER_REQUESTED = 1
}
