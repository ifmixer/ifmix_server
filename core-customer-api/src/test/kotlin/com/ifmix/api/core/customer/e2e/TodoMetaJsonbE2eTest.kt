package com.ifmix.api.core.customer.e2e

import com.ifmix.api.core.customer.e2e.support.E2eTestBase
import com.ifmix.api.core.common.entity.todo.Todo
import com.ifmix.api.core.common.entity.todo.by
import com.ifmix.api.core.common.infra.db.UuidV7
import org.assertj.core.api.Assertions.assertThat
import org.babyfish.jimmer.kt.new
import org.babyfish.jimmer.sql.ast.mutation.SaveMode
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.junit.jupiter.api.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/**
 * 测试 Jimmer 对 JSONB（@Serialized Map）字段的更新行为。
 *
 * 核心问题：Jimmer 能否像 MongoDB 那样做深层局部更新（如 meta.key1.key2[1].key4 = 6），
 * 还是说只能整列替换？
 *
 * 结论提前剧透：Jimmer 的 @Serialized 字段是**整列替换**语义——
 * 对 Jimmer 而言 jsonb 列就是一个"标量"值（与 varchar 无异），
 * save/update 时生成的 SQL 是 `SET meta = ?::jsonb`，会把整个 JSON 覆盖写入。
 * 它不能像 MongoDB $set 那样生成 jsonb_set() 局部路径更新。
 */
@DisplayName("Jimmer JSONB 局部更新行为测试")
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class TodoMetaJsonbE2eTest : E2eTestBase() {

    @Autowired
    lateinit var sql: KSqlClient

    @Autowired
    lateinit var jdbcTemplate: JdbcTemplate

    private val testAppId = UUID.fromString(TEST_APP_ID)

    /**
     * 场景 1：插入带嵌套 meta 的 Todo，验证存储正确。
     */
    @Test
    @Order(1)
    fun `insert todo with nested meta structure`() {
        val id = UuidV7.generate()
        val meta = mapOf(
            "key1" to mapOf(
                "key2" to listOf(
                    mapOf("key3" to 4),
                    5
                )
            )
        )

        val entity = new(Todo::class).by {
            this.id = id
            this.appId = testAppId
            this.title = "test-meta-insert"
            this.done = false
            this.meta = meta
        }

        sql.entities.save(entity) { setMode(SaveMode.INSERT_ONLY) }

        // 用 JDBC 直接查 raw JSON 验证
        val rawJson = jdbcTemplate.queryForObject(
            "SELECT meta::text FROM core_todo WHERE id = ?::uuid",
            String::class.java,
            id.toString()
        )
        assertThat(rawJson).isNotNull()
        assertThat(rawJson).contains("key1")
        assertThat(rawJson).contains("key3")

        // 通过 Jimmer 查回来验证反序列化
        val loaded = sql.entities.findById(Todo::class, id)!!
        @Suppress("UNCHECKED_CAST")
        val loadedMeta = loaded.meta as Map<String, Any?>
        assertThat(loadedMeta).containsKey("key1")
    }

    /**
     * 场景 2：Jimmer save 只设置 meta 中的部分字段 → 验证整列替换行为。
     *
     * 步骤：
     * 1. 先插入 meta = { key1: { key2: [{ key3: 4 }, 5] }, extra: "keep_me" }
     * 2. 用 Jimmer save 设 meta = { key1: { key2: [{ key3: 4 }, 6] } }（没有 extra）
     * 3. 验证 extra 字段被丢失（整列覆盖）
     */
    @Test
    @Order(2)
    fun `jimmer save replaces entire meta column - no partial update`() {
        val id = UuidV7.generate()
        val originalMeta = mapOf(
            "key1" to mapOf(
                "key2" to listOf(mapOf("key3" to 4), 5)
            ),
            "extra" to "keep_me"
        )

        // 1. 插入
        val entity = new(Todo::class).by {
            this.id = id
            this.appId = testAppId
            this.title = "test-meta-replace"
            this.done = false
            this.meta = originalMeta
        }
        sql.entities.save(entity) { setMode(SaveMode.INSERT_ONLY) }

        // 2. 用 Jimmer save 更新 meta（不包含 extra 字段）
        val updatedMeta = mapOf(
            "key1" to mapOf(
                "key2" to listOf(mapOf("key3" to 4), 6)  // 只改了 key2[1] 从 5 → 6
            )
            // 注意：没有 "extra" 了
        )
        val updateEntity = new(Todo::class).by {
            this.id = id
            this.appId = testAppId
            this.meta = updatedMeta
        }
        sql.entities.save(updateEntity) { setMode(SaveMode.UPDATE_ONLY) }

        // 3. 验证：extra 被丢失了 → 证明是整列替换
        val loaded = sql.entities.findById(Todo::class, id)!!
        @Suppress("UNCHECKED_CAST")
        val loadedMeta = loaded.meta as Map<String, Any?>
        assertThat(loadedMeta).doesNotContainKey("extra")
        assertThat(loadedMeta).containsKey("key1")
    }

    /**
     * 场景 3：如果只设置了 title（不设 meta），meta 是否保留？
     *
     * Jimmer 的动态属性机制——未赋值的属性在 update 时不会生成 SET 子句。
     * 所以如果 save 时没设 meta，原来的 meta 不会被清空。
     */
    @Test
    @Order(3)
    fun `unloaded meta property is not touched during save`() {
        val id = UuidV7.generate()
        val originalMeta = mapOf("preserve" to "this_value", "nested" to mapOf("a" to 1))

        // 1. 插入
        val entity = new(Todo::class).by {
            this.id = id
            this.appId = testAppId
            this.title = "test-unloaded-meta"
            this.done = false
            this.meta = originalMeta
        }
        sql.entities.save(entity) { setMode(SaveMode.INSERT_ONLY) }

        // 2. 只更新 title，不碰 meta
        val updateEntity = new(Todo::class).by {
            this.id = id
            this.appId = testAppId
            this.title = "updated-title"
        }
        sql.entities.save(updateEntity) { setMode(SaveMode.UPDATE_ONLY) }

        // 3. meta 应该完好无损
        val loaded = sql.entities.findById(Todo::class, id)!!
        assertThat(loaded.title).isEqualTo("updated-title")
        @Suppress("UNCHECKED_CAST")
        val loadedMeta = loaded.meta as Map<String, Any?>
        assertThat(loadedMeta).containsKey("preserve")
        assertThat(loadedMeta["preserve"]).isEqualTo("this_value")
    }

    /**
     * 场景 4：显式设置 meta = null → 验证 NULL 覆盖行为。
     */
    @Test
    @Order(4)
    fun `explicitly setting meta to null clears it`() {
        val id = UuidV7.generate()

        // 1. 插入
        val entity = new(Todo::class).by {
            this.id = id
            this.appId = testAppId
            this.title = "test-null-meta"
            this.done = false
            this.meta = mapOf("will_be_erased" to true)
        }
        sql.entities.save(entity) { setMode(SaveMode.INSERT_ONLY) }

        // 2. 显式设 meta = null
        val updateEntity = new(Todo::class).by {
            this.id = id
            this.appId = testAppId
            this.meta = null
        }
        sql.entities.save(updateEntity) { setMode(SaveMode.UPDATE_ONLY) }

        // 3. meta 应该是 null
        val loaded = sql.entities.findById(Todo::class, id)!!
        assertThat(loaded.meta).isNull()
    }

    /**
     * 场景 5：用原生 SQL 的 jsonb_set 做真正的深层局部更新（对比方案）。
     *
     * 这展示了如果需要 MongoDB 式的深层 path 更新，必须绕过 Jimmer 用原生 SQL。
     */
    @Test
    @Order(5)
    fun `native SQL jsonb_set achieves deep partial update`() {
        val id = UuidV7.generate()
        val originalMeta = mapOf(
            "key1" to mapOf(
                "key2" to listOf(mapOf("key3" to 4), 5)
            ),
            "extra" to "keep_me"
        )

        // 1. 插入
        val entity = new(Todo::class).by {
            this.id = id
            this.appId = testAppId
            this.title = "test-native-jsonb-set"
            this.done = false
            this.meta = originalMeta
        }
        sql.entities.save(entity) { setMode(SaveMode.INSERT_ONLY) }

        // 2. 用 PG jsonb_set 只修改 key1.key2[1]（从 5 → 6），保留 extra
        jdbcTemplate.update(
            """UPDATE core_todo
               SET meta = jsonb_set(meta, '{key1,key2,1}', '6'::jsonb)
               WHERE id = ?::uuid""",
            id.toString()
        )

        // 3. 验证：extra 依然存在，key1.key2[1] 变成了 6
        val loaded = sql.entities.findById(Todo::class, id)!!
        @Suppress("UNCHECKED_CAST")
        val loadedMeta = loaded.meta as Map<String, Any?>
        assertThat(loadedMeta).containsKey("extra")
        assertThat(loadedMeta["extra"]).isEqualTo("keep_me")

        @Suppress("UNCHECKED_CAST")
        val key1 = loadedMeta["key1"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val key2 = key1["key2"] as List<Any?>
        assertThat(key2[1]).isEqualTo(6)  // 从 5 改成了 6
    }

    /**
     * 场景 6（bonus）：应用层 read-modify-write 模式 —— 先读再改再存。
     *
     * 这是在 Jimmer 中实现"局部更新 JSON"的推荐做法（无并发时）。
     */
    @Test
    @Order(6)
    fun `application-level read-modify-write pattern for partial json update`() {
        val id = UuidV7.generate()
        val originalMeta = mapOf(
            "key1" to mapOf(
                "key2" to listOf(mapOf("key3" to 4), 5)
            ),
            "extra" to "keep_me"
        )

        // 1. 插入
        val entity = new(Todo::class).by {
            this.id = id
            this.appId = testAppId
            this.title = "test-read-modify-write"
            this.done = false
            this.meta = originalMeta
        }
        sql.entities.save(entity) { setMode(SaveMode.INSERT_ONLY) }

        // 2. 读出 → 修改 → 写回
        val loaded = sql.entities.findById(Todo::class, id)!!
        @Suppress("UNCHECKED_CAST")
        val currentMeta = loaded.meta as MutableMap<String, Any?>

        // 深层修改：key1.key2[1] = 6
        @Suppress("UNCHECKED_CAST")
        val key1 = (currentMeta["key1"] as Map<String, Any?>).toMutableMap()
        @Suppress("UNCHECKED_CAST")
        val key2 = (key1["key2"] as List<Any?>).toMutableList()
        key2[1] = 6
        key1["key2"] = key2
        currentMeta["key1"] = key1

        // 写回
        val updateEntity = new(Todo::class).by {
            this.id = id
            this.appId = testAppId
            this.meta = currentMeta
        }
        sql.entities.save(updateEntity) { setMode(SaveMode.UPDATE_ONLY) }

        // 3. 验证：extra 保留，key2[1] = 6
        val reloaded = sql.entities.findById(Todo::class, id)!!
        @Suppress("UNCHECKED_CAST")
        val reloadedMeta = reloaded.meta as Map<String, Any?>
        assertThat(reloadedMeta).containsKey("extra")
        assertThat(reloadedMeta["extra"]).isEqualTo("keep_me")

        @Suppress("UNCHECKED_CAST")
        val rKey1 = reloadedMeta["key1"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val rKey2 = rKey1["key2"] as List<Any?>
        assertThat(rKey2[1]).isEqualTo(6)
    }
}
