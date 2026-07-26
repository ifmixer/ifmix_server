package com.ifmix.api.core.modules.todo

/** 内嵌在 TodoDocument 里的子项。id 应用侧生成，供客户端引用。 */
data class TodoItem(
    var id: String? = null,
    var content: String? = null,
    var done: Boolean = false,
)
