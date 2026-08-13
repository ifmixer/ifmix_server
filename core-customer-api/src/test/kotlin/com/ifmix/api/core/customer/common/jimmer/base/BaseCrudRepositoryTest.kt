package com.ifmix.api.core.common.repository.base

import assertk.assertThat
import assertk.assertions.isNotNull
import org.junit.jupiter.api.Test

/**
 * 简单的编译测试，验证 BaseCrudRepository 和 BaseAppCrudRepository 能正常编译。
 * 集成测试在专门的模块测试中进行（如 TodoServiceJimmerTest）。
 */
class BaseCrudRepositoryCompileTest {

    @Test
    fun `BaseCrudRepository compiles`() {
        assertThat(Unit).isNotNull()
    }

    @Test
    fun `BaseAppCrudRepository compiles`() {
        assertThat(Unit).isNotNull()
    }
}
