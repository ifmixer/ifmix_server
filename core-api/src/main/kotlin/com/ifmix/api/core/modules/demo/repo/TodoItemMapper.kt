package com.ifmix.api.core.modules.demo.repo

import com.baomidou.mybatisplus.core.mapper.BaseMapper
import com.ifmix.api.core.entity.demo.TodoItem
import org.apache.ibatis.annotations.Mapper

/** TodoItem Mapper — 继承 BaseMapper 获得 20+ 通用 CRUD 方法。 */
@Mapper
interface TodoItemMapper : BaseMapper<TodoItem>
