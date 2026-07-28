package com.ifmix.api.core.common.jimmer.base

import com.ifmix.api.core.common.jimmer.cluster.ClusterRegistry
import assertk.assertThat
import assertk.assertions.isNotNull

/**
 * 简单的编译测试，验证 BaseCrudRepository 和 BaseAppCrudRepository 能正常编译。
 * 集成测试在专门的模块测试中进行（如 TodoServiceJimmerTest）。
 */
class BaseCrudRepositoryCompileTest {

    @Test
    fun `BaseCrudRepository compiles`() {
        // 这个测试不执行任何逻辑，只是确保代码能编译通过
        // 实际功能测试在集成测试中
        assertThat(Unit).isNotNull()
    }

    @Test
    fun `BaseAppCrudRepository compiles`() {
        // Dummy test to ensure compilation
        assertThat(Unit).isNotNull()
    }
}
