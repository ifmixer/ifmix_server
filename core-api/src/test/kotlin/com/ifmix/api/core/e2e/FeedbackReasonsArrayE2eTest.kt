package com.ifmix.core.api.e2e

import com.ifmix.core.api.e2e.support.E2eTestBase
import com.ifmix.core.api.entity.cs.Feedback
import com.ifmix.core.api.entity.cs.FeedbackReasons
import com.ifmix.core.api.entity.cs.FeedbackTopics
import com.ifmix.core.api.entity.cs.by
import com.ifmix.core.api.infra.db.UuidV7
import org.assertj.core.api.Assertions.assertThat
import org.babyfish.jimmer.kt.new
import org.babyfish.jimmer.sql.ast.mutation.SaveMode
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.util.UUID

/**
 * 验证 Jimmer 对 PG smallint[] 数组列（Feedback.reasons: Array<Int>）的 round-trip。
 *
 * 关键风险：PG smallint 经 JDBC 读回元素可能是 Short，若 Jimmer 不做 Short→Int
 * 窄化转换，findById 反序列化 Array<Int> 时会 ClassCastException。此测试坐实
 * 存入 [10,20] 能原样读回，无需自定义 ScalarProvider。
 */
@DisplayName("Jimmer smallint[] 数组列 round-trip 测试")
class FeedbackReasonsArrayE2eTest : E2eTestBase() {

    @Autowired
    lateinit var sql: KSqlClient

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    private val testProjectId = UUID.fromString(TEST_PROJECT_ID)

    @Test
    fun `smallint array round-trips through jimmer`() {
        val id = UuidV7.generate()

        val entity = new(Feedback::class).by {
            this.id = id
            this.projectId = testProjectId
            this.topic = FeedbackTopics.SCAN
            this.reasons = arrayOf(FeedbackReasons.PRICE_TOO_HIGH, FeedbackReasons.WRONG_IDENTIFICATION)
            this.createdAt = Instant.now()
        }
        sql.entities.save(entity) { setMode(SaveMode.INSERT_ONLY) }

        // 1. JDBC 直查 raw 值，确认 PG 存成 smallint[] 字面量 {20,30}
        val rawArray = jdbcTemplate.queryForObject(
            "SELECT reasons::text FROM cs_feedback WHERE id = ?::uuid",
            String::class.java,
            id.toString(),
        )
        assertThat(rawArray).isEqualTo("{20,30}")

        // 2. 关键：Jimmer 读回 Array<Int>，验证 smallint[]→Array<Int> 不 CCE 且元素正确
        val loaded = sql.entities.findById(Feedback::class, id)!!
        assertThat(loaded.reasons.toList()).containsExactly(20, 30)
    }

    @Test
    fun `empty smallint array round-trips`() {
        val id = UuidV7.generate()
        val entity = new(Feedback::class).by {
            this.id = id
            this.projectId = testProjectId
            this.topic = FeedbackTopics.APP
            this.reasons = emptyArray()
            this.createdAt = Instant.now()
        }
        sql.entities.save(entity) { setMode(SaveMode.INSERT_ONLY) }

        val loaded = sql.entities.findById(Feedback::class, id)!!
        assertThat(loaded.reasons).isEmpty()
    }
}
