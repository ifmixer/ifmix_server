# Jimmer + PostgreSQL 迁移 — 第一阶段实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 将 ifmix_server 的 todo 和 feedback 模块从 MongoDB 迁移到 Jimmer + PostgreSQL，搭建多集群路由和读写分离基础设施。

**架构：** 每个集群（由 appId 路由）持有 writer + reader DataSource 对，通过 AbstractRoutingDataSource 做读写分离。ClusterRegistry 按 appId 返回对应集群的 JSqlClient。Entity 集中在 common/jimmer 作为 DAO 层，模块只有 Service + Controller。

**技术栈：** Kotlin 2.3.10, Spring Boot 4.1, Jimmer 0.11.5 (KSP), PostgreSQL, Flyway, HikariCP, Gradle 9.6.1, Testcontainers

**规格文件：** `docs/superpowers/specs/2026-07-29-jimmer-pg-migration-design.md`

**参考文档：**
- Jimmer 官方文档：https://babyfish-ct.github.io/jimmer-doc/
- Jimmer Kotlin 快速开始：https://babyfish-ct.github.io/jimmer-doc/docs/quick-view/get-started/get-started-kotlin
- Jimmer DTO 语言：https://babyfish-ct.github.io/jimmer-doc/docs/object/view/dto-language
- Jimmer 全局过滤器：https://babyfish-ct.github.io/jimmer-doc/docs/query/global-filter
- Jimmer Save Command：https://babyfish-ct.github.io/jimmer-doc/docs/mutation/save-command
- Spring AbstractRoutingDataSource：Spring Framework 官方文档

---

## 文件结构

### 新建文件

| 文件路径 | 职责 |
|---|---|
| `core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/entity/AppScopedProps.kt` | `@MappedSuperclass` 超类型接口，声明 `appId: UUID` |
| `core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/entity/todo/Todo.kt` | Todo 实体接口 |
| `core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/entity/todo/TodoItem.kt` | TodoItem 实体接口 |
| `core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/entity/feedback/Feedback.kt` | Feedback 实体接口 |
| `core-api/src/main/dto/com/ifmix/api/core/common/jimmer/entity/todo/Todo.dto` | Todo DTO 定义（View / CreateInput / UpdateInput） |
| `core-api/src/main/dto/com/ifmix/api/core/common/jimmer/entity/feedback/Feedback.dto` | Feedback DTO 定义 |
| `core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/filter/AppScopedFilter.kt` | 全局过滤器，自动注入 `WHERE app_id = ?` |
| `core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/cluster/ClusterProperties.kt` | `@ConfigurationProperties` 集群配置 |
| `core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/cluster/ReadWriteRoutingDataSource.kt` | 读写分离路由 DataSource |
| `core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/cluster/ClusterRegistry.kt` | 按 appId 返回 JSqlClient |
| `core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/cluster/ClusterInitializer.kt` | 启动时执行 Flyway migrate |
| `core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/cluster/JimmerConfig.kt` | Jimmer 相关 Spring 配置 |
| `core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/base/BaseCrudRepository.kt` | 通用 CRUD + 游标分页 |
| `core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/base/BaseAppCrudRepository.kt` | 多租户 CRUD |
| `core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/base/BaseCrudService.kt` | 通用 Service |
| `core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/base/BaseAppCrudService.kt` | 多租户 Service |
| `core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/repository/todo/TodoRepository.kt` | Todo 领域 Repository |
| `core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/repository/feedback/FeedbackRepository.kt` | Feedback 领域 Repository |
| `core-api/src/main/resources/db/migration/V1__baseline.sql` | 从现有 PG schema 导出的完整 DDL |
| `core-api/src/test/kotlin/com/ifmix/api/core/support/AbstractJimmerTest.kt` | Jimmer + PG Testcontainers 测试基类 |
| `core-api/src/test/kotlin/com/ifmix/api/core/common/jimmer/cluster/ClusterRegistryTest.kt` | ClusterRegistry 单元测试 |
| `core-api/src/test/kotlin/com/ifmix/api/core/common/jimmer/cluster/ReadWriteRoutingDataSourceTest.kt` | 读写分离路由测试 |
| `core-api/src/test/kotlin/com/ifmix/api/core/common/jimmer/base/BaseCrudRepositoryTest.kt` | BaseCrudRepository 集成测试 |
| `core-api/src/test/kotlin/com/ifmix/api/core/modules/todo/TodoServiceJimmerTest.kt` | 新 TodoService 集成测试 |
| `core-api/src/test/kotlin/com/ifmix/api/core/modules/feedback/FeedbackServiceJimmerTest.kt` | 新 FeedbackService 集成测试 |

### 修改文件

| 文件路径 | 变更 |
|---|---|
| `build.gradle.kts` | 添加 jimmer KSP 版本声明 |
| `core-api/build.gradle.kts` | 添加 Jimmer、PG、Flyway、HikariCP 依赖 |
| `core-api/src/main/resources/application.yml` | 添加集群配置 |
| `core-api/src/main/resources/application-local.yml` | 添加本地集群配置 |
| `core-api/src/main/kotlin/com/ifmix/api/core/common/http/RequestContext.kt` | 移除 `readPreference` 字段 |
| `core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/TodoService.kt` | 重写为继承 BaseAppCrudService |
| `core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/TodoConfig.kt` | 移除 MongoDB bean，简化为 @Component 扫描 |
| `core-api/src/main/kotlin/com/ifmix/api/core/modules/feedback/FeedbackService.kt` | 重写为继承 BaseAppCrudService |
| `core-api/src/main/kotlin/com/ifmix/api/core/modules/feedback/FeedbackConfig.kt` | 移除 MongoDB bean |
| `core-api/src/main/kotlin/com/ifmix/api/core/bff/customer/CustomerTodoController.kt` | 使用 Jimmer 生成的 DTO 类型 |
| `core-api/src/main/kotlin/com/ifmix/api/core/bff/customer/CustomerFeedbackController.kt` | 使用 Jimmer 生成的 DTO 类型 |

### 删除文件（迁移完成后）

| 文件路径 | 原因 |
|---|---|
| `core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/TodoDocument.kt` | 替换为 Jimmer Entity |
| `core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/TodoItem.kt` | 替换为 Jimmer Entity |
| `core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/TodoDtos.kt` | 替换为 Jimmer .dto 生成 |
| `core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/TodoMapper.kt` | Jimmer DTO 替代 Konvert |
| `core-api/src/main/kotlin/com/ifmix/api/core/modules/feedback/FeedbackDocument.kt` | 替换为 Jimmer Entity |
| `core-api/src/main/kotlin/com/ifmix/api/core/modules/feedback/FeedbackDtos.kt` | 替换为 Jimmer .dto 生成 |

---

## 任务 1：Gradle 依赖配置

**文件：**
- 修改：`build.gradle.kts`
- 修改：`core-api/build.gradle.kts`

- [ ] **步骤 1：修改根 build.gradle.kts 添加 Jimmer 版本管理**

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm") version "2.3.10" apply false
    kotlin("plugin.spring") version "2.3.10" apply false
    id("org.springframework.boot") version "4.1.0" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
    id("com.google.devtools.ksp") version "2.3.10" apply false
}

allprojects {
    group = "com.ifmix"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

// Jimmer 版本集中管理
extra["jimmerVersion"] = "0.11.5"
```

- [ ] **步骤 2：修改 core-api/build.gradle.kts 添加依赖**

在 `dependencies` 块中添加以下内容（保留现有 MongoDB 依赖不动）：

```kotlin
// === Jimmer + PostgreSQL ===
val jimmerVersion: String by rootProject.extra
implementation("org.babyfish.jimmer:jimmer-spring-boot-starter:$jimmerVersion")
implementation("org.babyfish.jimmer:jimmer-sql-kotlin:$jimmerVersion")
ksp("org.babyfish.jimmer:jimmer-ksp:$jimmerVersion")

// PostgreSQL JDBC
implementation("org.postgresql:postgresql")

// Flyway
implementation("org.flywaydb:flyway-core")
implementation("org.flywaydb:flyway-database-postgresql")

// Testcontainers PostgreSQL（测试）
testImplementation("org.testcontainers:postgresql:1.20.6")
```

在文件末尾（`tasks.withType<Test>` 之后）添加 Jimmer KSP 配置：

```kotlin
ksp {
    // Jimmer DTO 文件位置（相对于 project root）
    arg("jimmer.dto.dirs", "src/main/dto")
    // 生成 Kotlin 代码
    arg("jimmer.language", "kotlin")
}
```

- [ ] **步骤 3：运行 Gradle sync 验证依赖解析成功**

运行：`cd /Users/jason/ai/myprojects/ifmix_server && ./gradlew :core-api:dependencies --configuration compileClasspath 2>&1 | grep jimmer`
预期：输出中包含 `org.babyfish.jimmer:jimmer-spring-boot-starter:0.11.5`

- [ ] **步骤 4：Commit**

```bash
git add build.gradle.kts core-api/build.gradle.kts
git commit -m "build: add Jimmer 0.11.5, PostgreSQL, Flyway dependencies"
```

---

## 任务 2：Flyway 基线 Migration 文件

**文件：**
- 创建：`core-api/src/main/resources/db/migration/V1__baseline.sql`

- [ ] **步骤 1：导出现有 PG schema 为 V1 baseline**

从 `ifmix_core` 数据库导出 DDL。只导出第一阶段需要的表（todo + feedback），其余表后续阶段再加。

运行：
```bash
psql postgresql://postgres:postgres@localhost:5432/ifmix_core \
  -c "\d todos" -c "\d todo_items" -c "\d feedback"
```

- [ ] **步骤 2：创建 V1__baseline.sql**

```sql
-- V1__baseline.sql
-- Jimmer 迁移基线：从 drizzle 项目导出的完整 schema（第一阶段：todo + feedback）
-- 表名规范：单数 + snake_case

CREATE TABLE IF NOT EXISTS todo (
    id UUID NOT NULL PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    done BOOLEAN NOT NULL DEFAULT FALSE,
    app_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS todo_app_id_id_idx ON todo (app_id, id);

CREATE TABLE IF NOT EXISTS todo_item (
    id UUID NOT NULL PRIMARY KEY,
    todo_id UUID NOT NULL,
    app_id UUID NOT NULL,
    content VARCHAR(1000) NOT NULL,
    done BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS todo_item_app_id_id_idx ON todo_item (app_id, id);
CREATE INDEX IF NOT EXISTS todo_item_todo_id_idx ON todo_item (todo_id);

CREATE TABLE IF NOT EXISTS feedback (
    id UUID NOT NULL PRIMARY KEY,
    app_id UUID NOT NULL,
    install_id UUID NOT NULL,
    user_id UUID,
    scan_record_id UUID,
    category VARCHAR(32) NOT NULL,
    comment VARCHAR(1000),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS feedback_app_created_idx ON feedback (app_id, created_at);
```

注意：表名从 drizzle 的 `todos`/`todo_items` 改为单数 `todo`/`todo_item`。如果连接已有的 `ifmix_core` 数据库，需要先 rename 表或使用 V2 migration 处理。对于全新的 `ifmix_core_local`，直接从 V1 建表。

- [ ] **步骤 3：验证 SQL 语法正确**

运行：
```bash
psql postgresql://postgres:postgres@localhost:5432/postgres -c "CREATE DATABASE ifmix_core_local;" 2>&1 || true
psql postgresql://postgres:postgres@localhost:5432/ifmix_core_local -f core-api/src/main/resources/db/migration/V1__baseline.sql
```
预期：无错误，`CREATE TABLE` 和 `CREATE INDEX` 成功

- [ ] **步骤 4：Commit**

```bash
git add core-api/src/main/resources/db/migration/V1__baseline.sql
git commit -m "db: add V1 baseline migration for todo + feedback tables"
```

---

## 任务 3：多集群路由 + 读写分离基础设施

**文件：**
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/cluster/ClusterProperties.kt`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/cluster/ReadWriteRoutingDataSource.kt`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/cluster/ClusterRegistry.kt`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/cluster/ClusterInitializer.kt`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/cluster/JimmerConfig.kt`
- 修改：`core-api/src/main/resources/application.yml`
- 修改：`core-api/src/main/resources/application-local.yml`
- 测试：`core-api/src/test/kotlin/com/ifmix/api/core/common/jimmer/cluster/ClusterRegistryTest.kt`
- 测试：`core-api/src/test/kotlin/com/ifmix/api/core/common/jimmer/cluster/ReadWriteRoutingDataSourceTest.kt`

- [ ] **步骤 1：编写 ClusterRegistryTest（失败测试）**

```kotlin
// core-api/src/test/kotlin/com/ifmix/api/core/common/jimmer/cluster/ClusterRegistryTest.kt
package com.ifmix.api.core.common.jimmer.cluster

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import assertk.assertThat
import assertk.assertions.isNotNull
import assertk.assertions.isSameInstanceAs

class ClusterRegistryTest {

    private fun buildProps(): ClusterProperties {
        val ds = ClusterProperties.DataSourceProps(
            jdbcUrl = "jdbc:postgresql://localhost:5432/ifmix_core_local",
            username = "postgres",
            password = "postgres",
        )
        return ClusterProperties(
            clusters = mapOf("default" to ClusterProperties.ClusterProps(writer = ds, reader = ds)),
            clusterRouting = ClusterProperties.RoutingProps(mappings = mapOf("app-us" to "cluster-us")),
        )
    }

    @Test
    fun `forAppId returns default cluster when no mapping`() {
        val registry = ClusterRegistry(buildProps())
        registry.init()
        val client = registry.forAppId("unmapped-app")
        assertThat(client).isNotNull()
    }

    @Test
    fun `forAppId returns same client for same cluster`() {
        val registry = ClusterRegistry(buildProps())
        registry.init()
        val c1 = registry.forAppId("unmapped-1")
        val c2 = registry.forAppId("unmapped-2")
        assertThat(c1).isSameInstanceAs(c2)
    }

    @Test
    fun `forAppId throws when mapped cluster does not exist`() {
        val registry = ClusterRegistry(buildProps())
        registry.init()
        assertThrows<IllegalStateException> {
            registry.forAppId("app-us") // mapped to "cluster-us" which doesn't exist
        }
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`./gradlew :core-api:test --tests "*.ClusterRegistryTest" 2>&1 | tail -5`
预期：编译失败，`ClusterProperties` 和 `ClusterRegistry` 不存在

- [ ] **步骤 3：实现 ClusterProperties**

```kotlin
// core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/cluster/ClusterProperties.kt
package com.ifmix.api.core.common.jimmer.cluster

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app")
data class ClusterProperties(
    val clusters: Map<String, ClusterProps> = mapOf(),
    val clusterRouting: RoutingProps = RoutingProps(),
) {
    data class ClusterProps(
        val writer: DataSourceProps,
        val reader: DataSourceProps,
    )

    data class DataSourceProps(
        val jdbcUrl: String,
        val username: String = "",
        val password: String = "",
        val maximumPoolSize: Int = 10,
    )

    data class RoutingProps(
        val mappings: Map<String, String> = emptyMap(),
    )
}
```

- [ ] **步骤 4：实现 ReadWriteRoutingDataSource**

```kotlin
// core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/cluster/ReadWriteRoutingDataSource.kt
package com.ifmix.api.core.common.jimmer.cluster

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource
import org.springframework.transaction.support.TransactionSynchronizationManager
import javax.sql.DataSource

/**
 * 读写分离路由：
 * - @Transactional(readOnly = true) → reader
 * - 其他 → writer
 */
class ReadWriteRoutingDataSource(
    private val writerDs: DataSource,
    private val readerDs: DataSource,
) : AbstractRoutingDataSource() {

    companion object {
        private const val WRITER = "writer"
        private const val READER = "reader"
    }

    init {
        setTargetDataSources(mapOf<Any, Any>(WRITER to writerDs, READER to readerDs))
        setDefaultTargetDataSource(writerDs)
        afterPropertiesSet()
    }

    override fun determineCurrentLookupKey(): Any {
        val isReadOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly()
        return if (isReadOnly) READER else WRITER
    }
}
```

- [ ] **步骤 5：实现 ClusterRegistry**

```kotlin
// core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/cluster/ClusterRegistry.kt
package com.ifmix.api.core.common.jimmer.cluster

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.newKSqlClient
import org.babyfish.jimmer.sql.dialect.PostgresDialect
import org.springframework.stereotype.Component
import javax.sql.DataSource

@Component
class ClusterRegistry(
    private val props: ClusterProperties,
) {
    private lateinit var clients: Map<String, KSqlClient>
    private lateinit var dataSources: Map<String, Pair<HikariDataSource, HikariDataSource>>
    private lateinit var routingMap: Map<String, String> // appId → clusterName

    @PostConstruct
    fun init() {
        routingMap = props.clusterRouting.mappings

        val dsMap = mutableMapOf<String, Pair<HikariDataSource, HikariDataSource>>()
        val clientMap = mutableMapOf<String, KSqlClient>()

        props.clusters.forEach { (name, cluster) ->
            val writerDs = createDataSource(cluster.writer, "$name-writer")
            val readerDs = createDataSource(cluster.reader, "$name-reader")
            dsMap[name] = writerDs to readerDs

            val routingDs = ReadWriteRoutingDataSource(writerDs, readerDs)
            val sqlClient = newKSqlClient {
                setConnectionManager {
                    val con = routingDs.connection
                    try {
                        proceed(con)
                    } finally {
                        con.close()
                    }
                }
                setDialect(PostgresDialect())
            }
            clientMap[name] = sqlClient
        }

        dataSources = dsMap
        clients = clientMap
    }

    /** 按 appId 获取对应集群的 KSqlClient。未映射的走 default。 */
    fun forAppId(appId: String): KSqlClient {
        val clusterName = routingMap[appId] ?: "default"
        return clients[clusterName]
            ?: throw IllegalStateException("Cluster '$clusterName' not found (appId=$appId)")
    }

    /** 获取默认集群的 KSqlClient */
    fun primary(): KSqlClient =
        clients["default"] ?: throw IllegalStateException("No 'default' cluster configured")

    /** 获取所有集群的 writer DataSource（用于 Flyway） */
    fun allWriterDataSources(): Map<String, DataSource> =
        dataSources.mapValues { it.value.first }

    @PreDestroy
    fun destroy() {
        dataSources.values.forEach { (writer, reader) ->
            writer.close()
            reader.close()
        }
    }

    private fun createDataSource(props: ClusterProperties.DataSourceProps, poolName: String): HikariDataSource {
        val config = HikariConfig().apply {
            jdbcUrl = props.jdbcUrl
            username = props.username
            password = props.password
            maximumPoolSize = props.maximumPoolSize
            this.poolName = poolName
        }
        return HikariDataSource(config)
    }
}
```

- [ ] **步骤 6：实现 ClusterInitializer**

```kotlin
// core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/cluster/ClusterInitializer.kt
package com.ifmix.api.core.common.jimmer.cluster

import org.flywaydb.core.Flyway
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

/**
 * 启动时对所有集群的 writer DataSource 执行 Flyway migrate。
 */
@Component
class ClusterInitializer(
    private val clusterRegistry: ClusterRegistry,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        clusterRegistry.allWriterDataSources().forEach { (name, ds) ->
            Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .load()
                .migrate()
        }
    }
}
```

- [ ] **步骤 7：实现 JimmerConfig**

```kotlin
// core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/cluster/JimmerConfig.kt
package com.ifmix.api.core.common.jimmer.cluster

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(ClusterProperties::class)
class JimmerConfig
```

- [ ] **步骤 8：添加 application.yml 集群配置**

在 `application.yml` 的 `app:` 下添加：

```yaml
app:
  # ... 其他现有配置 ...
  clusters:
    default:
      writer:
        jdbc-url: ${PG_WRITER_URL:jdbc:postgresql://localhost:5432/ifmix_core_local}
        username: ${PG_USERNAME:postgres}
        password: ${PG_PASSWORD:postgres}
      reader:
        jdbc-url: ${PG_READER_URL:jdbc:postgresql://localhost:5432/ifmix_core_local}
        username: ${PG_USERNAME:postgres}
        password: ${PG_PASSWORD:postgres}

  cluster-routing:
    mappings: {}
```

在 `application-local.yml` 添加对应覆盖：

```yaml
app:
  clusters:
    default:
      writer:
        jdbc-url: jdbc:postgresql://localhost:5432/ifmix_core_local
        username: postgres
        password: postgres
      reader:
        jdbc-url: jdbc:postgresql://localhost:5432/ifmix_core_local
        username: postgres
        password: postgres
  cluster-routing:
    mappings: {}
```

- [ ] **步骤 9：运行 ClusterRegistryTest 验证通过**

运行：`./gradlew :core-api:test --tests "*.ClusterRegistryTest" 2>&1 | tail -10`
预期：3 个测试全部 PASS

- [ ] **步骤 10：编写 ReadWriteRoutingDataSourceTest**

```kotlin
// core-api/src/test/kotlin/com/ifmix/api/core/common/jimmer/cluster/ReadWriteRoutingDataSourceTest.kt
package com.ifmix.api.core.common.jimmer.cluster

import assertk.assertThat
import assertk.assertions.isEqualTo
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType
import org.springframework.transaction.support.TransactionSynchronizationManager

class ReadWriteRoutingDataSourceTest {

    @Test
    fun `routes to writer when not in read-only transaction`() {
        val writerDs = EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).setName("writer").build()
        val readerDs = EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).setName("reader").build()
        val routing = ReadWriteRoutingDataSource(writerDs, readerDs)

        // 不在事务中 → 默认走 writer
        val key = routing.determineCurrentLookupKey()
        assertThat(key).isEqualTo("writer")

        writerDs.shutdown()
        readerDs.shutdown()
    }

    @Test
    fun `routes to reader when in read-only transaction`() {
        val writerDs = EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).setName("writer2").build()
        val readerDs = EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).setName("reader2").build()
        val routing = ReadWriteRoutingDataSource(writerDs, readerDs)

        // 模拟 read-only 事务
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true)
        try {
            val key = routing.determineCurrentLookupKey()
            assertThat(key).isEqualTo("reader")
        } finally {
            TransactionSynchronizationManager.setCurrentTransactionReadOnly(false)
        }

        writerDs.shutdown()
        readerDs.shutdown()
    }
}
```

- [ ] **步骤 11：运行 ReadWriteRoutingDataSourceTest 验证通过**

运行：`./gradlew :core-api:test --tests "*.ReadWriteRoutingDataSourceTest" 2>&1 | tail -5`
预期：2 个测试 PASS

注意：需要在 `core-api/build.gradle.kts` 的 testImplementation 中添加 `com.h2database:h2` 作为内存数据库用于路由测试：
```kotlin
testImplementation("com.h2database:h2")
```

- [ ] **步骤 12：Commit**

```bash
git add -A
git commit -m "feat: implement multi-cluster routing + read-write split infrastructure"
```

---

## 任务 4：Jimmer Entity 定义 + 全局过滤器

**文件：**
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/entity/AppScopedProps.kt`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/entity/todo/Todo.kt`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/entity/todo/TodoItem.kt`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/entity/feedback/Feedback.kt`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/filter/AppScopedFilter.kt`

- [ ] **步骤 1：创建 AppScopedProps 超类型接口**

```kotlin
// core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/entity/AppScopedProps.kt
package com.ifmix.api.core.common.jimmer.entity

import org.babyfish.jimmer.sql.MappedSuperclass
import java.util.UUID

/**
 * 所有多租户实体的超类型。
 * AppScopedFilter 全局过滤器会对实现此接口的实体自动注入 WHERE app_id = ?。
 */
@MappedSuperclass
interface AppScopedProps {
    val appId: UUID
}
```

- [ ] **步骤 2：创建 Todo Entity**

```kotlin
// core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/entity/todo/Todo.kt
package com.ifmix.api.core.common.jimmer.entity.todo

import com.ifmix.api.core.common.jimmer.entity.AppScopedProps
import org.babyfish.jimmer.sql.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "todo")
interface Todo : AppScopedProps {

    @Id
    @GeneratedValue(generatorType = UUIDIdGenerator::class)
    val id: UUID

    override val appId: UUID

    val title: String

    val done: Boolean

    @LogicalDeleted("now")
    val deletedAt: Instant?

    val createdAt: Instant

    val updatedAt: Instant

    @OneToMany(mappedBy = "todo")
    val items: List<TodoItem>
}
```

- [ ] **步骤 3：创建 TodoItem Entity**

```kotlin
// core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/entity/todo/TodoItem.kt
package com.ifmix.api.core.common.jimmer.entity.todo

import com.ifmix.api.core.common.jimmer.entity.AppScopedProps
import org.babyfish.jimmer.sql.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "todo_item")
interface TodoItem : AppScopedProps {

    @Id
    @GeneratedValue(generatorType = UUIDIdGenerator::class)
    val id: UUID

    override val appId: UUID

    @ManyToOne
    @JoinColumn(name = "todo_id")
    val todo: Todo

    val content: String

    val done: Boolean

    @LogicalDeleted("now")
    val deletedAt: Instant?

    val createdAt: Instant

    val updatedAt: Instant
}
```

- [ ] **步骤 4：创建 Feedback Entity**

```kotlin
// core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/entity/feedback/Feedback.kt
package com.ifmix.api.core.common.jimmer.entity.feedback

import com.ifmix.api.core.common.jimmer.entity.AppScopedProps
import org.babyfish.jimmer.sql.*
import java.time.Instant
import java.util.UUID

/**
 * Feedback 实体。追加式写入，不软删（无 @LogicalDeleted）。
 */
@Entity
@Table(name = "feedback")
interface Feedback : AppScopedProps {

    @Id
    @GeneratedValue(generatorType = UUIDIdGenerator::class)
    val id: UUID

    override val appId: UUID

    val installId: UUID

    @Column(name = "user_id")
    val userId: UUID?

    val scanRecordId: UUID?

    val category: String

    val comment: String?

    val createdAt: Instant
}
```

- [ ] **步骤 5：创建 AppScopedFilter**

```kotlin
// core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/filter/AppScopedFilter.kt
package com.ifmix.api.core.common.jimmer.filter

import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.common.jimmer.entity.AppScopedProps
import org.babyfish.jimmer.sql.kt.filter.KFilter
import org.babyfish.jimmer.sql.kt.filter.KFilterArgs
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 全局过滤器：对所有实现 AppScopedProps 的实体自动注入 WHERE app_id = ?。
 * RequestContext 从 ThreadLocal（RequestContextHolder）获取。
 */
@Component
class AppScopedFilter : KFilter<AppScopedProps> {

    override fun filter(args: KFilterArgs<AppScopedProps>) {
        val ctx = RequestContextHolder.current()
        args.apply {
            where(table.appId eq UUID.fromString(ctx.appId))
        }
    }
}

/**
 * 持有当前线程的 RequestContext。
 * 由 RequestContextArgumentResolver 或拦截器在请求开始时设置。
 */
object RequestContextHolder {
    private val holder = ThreadLocal<RequestContext>()

    fun set(ctx: RequestContext) = holder.set(ctx)
    fun current(): RequestContext = holder.get()
        ?: throw IllegalStateException("No RequestContext in current thread")
    fun clear() = holder.remove()
}
```

- [ ] **步骤 6：验证 KSP 编译通过（生成 Draft / Fetcher / Table 类）**

运行：`./gradlew :core-api:kspKotlin 2>&1 | tail -10`
预期：编译成功，无错误。生成的代码在 `core-api/build/generated/ksp/main/kotlin/` 目录下。

- [ ] **步骤 7：Commit**

```bash
git add -A
git commit -m "feat: define Jimmer entities (Todo, TodoItem, Feedback) + AppScopedFilter"
```

---

## 任务 5：Jimmer DTO 文件

**文件：**
- 创建：`core-api/src/main/dto/com/ifmix/api/core/common/jimmer/entity/todo/Todo.dto`
- 创建：`core-api/src/main/dto/com/ifmix/api/core/common/jimmer/entity/feedback/Feedback.dto`

注意：Jimmer 的 `.dto` 文件路径必须对应实体的包路径，放在 `src/main/dto/` 下。

- [ ] **步骤 1：创建 Todo.dto**

```
// core-api/src/main/dto/com/ifmix/api/core/common/jimmer/entity/todo/Todo.dto
export com.ifmix.api.core.common.jimmer.entity.todo.Todo
    -> package com.ifmix.api.core.common.jimmer.dto.todo

TodoView {
    #allScalars
    items {
        #allScalars
    }
}

input TodoCreateInput {
    title
    done
}

input TodoUpdateInput {
    id!
    title
    done
}
```

- [ ] **步骤 2：创建 Feedback.dto**

```
// core-api/src/main/dto/com/ifmix/api/core/common/jimmer/entity/feedback/Feedback.dto
export com.ifmix.api.core.common.jimmer.entity.feedback.Feedback
    -> package com.ifmix.api.core.common.jimmer.dto.feedback

FeedbackView {
    #allScalars
}

input FeedbackCreateInput {
    installId
    userId
    scanRecordId
    category
    comment
}
```

- [ ] **步骤 3：验证 KSP 能正确生成 DTO 类**

运行：`./gradlew :core-api:kspKotlin 2>&1 | tail -5`
预期：编译成功。验证生成文件存在：
```bash
find core-api/build/generated/ksp -name "TodoView.kt" -o -name "TodoCreateInput.kt" -o -name "FeedbackView.kt"
```
预期：3 个文件都找到

- [ ] **步骤 4：Commit**

```bash
git add -A
git commit -m "feat: add Jimmer DTO definitions for Todo and Feedback"
```

---

## 任务 6：BaseCrudRepository + BaseAppCrudRepository

**文件：**
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/base/BaseCrudRepository.kt`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/base/BaseAppCrudRepository.kt`
- 测试：`core-api/src/test/kotlin/com/ifmix/api/core/common/jimmer/base/BaseCrudRepositoryTest.kt`
- 创建：`core-api/src/test/kotlin/com/ifmix/api/core/support/AbstractJimmerTest.kt`

- [ ] **步骤 1：创建 AbstractJimmerTest 测试基类**

```kotlin
// core-api/src/test/kotlin/com/ifmix/api/core/support/AbstractJimmerTest.kt
package com.ifmix.api.core.support

import com.ifmix.api.core.common.jimmer.cluster.ClusterProperties
import com.ifmix.api.core.common.jimmer.cluster.ClusterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Jimmer + PostgreSQL 集成测试基类。
 * 使用 Testcontainers 启动 PG，Flyway 自动建表。
 */
@Testcontainers
abstract class AbstractJimmerTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("ifmix_test")
            .withUsername("test")
            .withPassword("test")

        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("app.clusters.default.writer.jdbc-url") { postgres.jdbcUrl }
            registry.add("app.clusters.default.writer.username") { postgres.username }
            registry.add("app.clusters.default.writer.password") { postgres.password }
            registry.add("app.clusters.default.reader.jdbc-url") { postgres.jdbcUrl }
            registry.add("app.clusters.default.reader.username") { postgres.username }
            registry.add("app.clusters.default.reader.password") { postgres.password }
        }
    }

    protected fun createTestRegistry(): ClusterRegistry {
        val ds = ClusterProperties.DataSourceProps(
            jdbcUrl = postgres.jdbcUrl,
            username = postgres.username,
            password = postgres.password,
        )
        val props = ClusterProperties(
            clusters = mapOf("default" to ClusterProperties.ClusterProps(writer = ds, reader = ds)),
        )
        val registry = ClusterRegistry(props)
        registry.init()
        return registry
    }
}
```

- [ ] **步骤 2：创建 BaseCrudRepository**

```kotlin
// core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/base/BaseCrudRepository.kt
package com.ifmix.api.core.common.jimmer.base

import com.ifmix.api.core.common.db.CursorQueryInput
import com.ifmix.api.core.common.db.Page
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.common.jimmer.cluster.ClusterRegistry
import org.babyfish.jimmer.Input
import org.babyfish.jimmer.View
import org.babyfish.jimmer.sql.ast.mutation.SaveMode
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.query.KMutableRootQuery
import java.util.UUID
import kotlin.reflect.KClass

/**
 * 通用 CRUD Repository。不假设 appId（AppScopedFilter 全局处理）。
 * 提供 insert / update / save / deleteById / findById / findByCursor。
 */
abstract class BaseCrudRepository<E : Any>(
    private val clusterRegistry: ClusterRegistry,
    protected val entityType: KClass<E>,
) {
    /** 获取当前 appId 对应集群的 SqlClient */
    protected fun sql(ctx: RequestContext): KSqlClient =
        clusterRegistry.forAppId(ctx.appId)

    fun findById(ctx: RequestContext, id: UUID): E? =
        sql(ctx).entities.findById(entityType, id)

    fun <V : View<E>> findById(ctx: RequestContext, id: UUID, viewType: KClass<V>): V? =
        sql(ctx).entities.findById(viewType, id)

    fun insert(ctx: RequestContext, input: Input<E>): E =
        sql(ctx).entities.save(input) {
            setMode(SaveMode.INSERT_ONLY)
        }.modifiedEntity

    fun update(ctx: RequestContext, input: Input<E>): E =
        sql(ctx).entities.save(input) {
            setMode(SaveMode.UPDATE_ONLY)
        }.modifiedEntity

    fun save(ctx: RequestContext, input: Input<E>): E =
        sql(ctx).entities.save(input).modifiedEntity

    fun save(ctx: RequestContext, entity: E): E =
        sql(ctx).entities.save(entity).modifiedEntity

    fun deleteById(ctx: RequestContext, id: UUID) {
        sql(ctx).entities.delete(entityType, id)
    }

    /**
     * 游标分页（keyset）。
     * 基于 (createdAt DESC, id DESC) 复合游标，与现有 CursorQueryInput 语义一致。
     * block 参数允许调用方添加额外 WHERE 条件。
     */
    fun findByCursor(
        ctx: RequestContext,
        input: CursorQueryInput = CursorQueryInput(),
        block: (KMutableRootQuery<E>.() -> Unit)? = null,
    ): Page<E> {
        val limit = input.effectiveLimit()
        val sql = sql(ctx)

        val rows = sql.createQuery(entityType) {
            block?.invoke(this)
            // 游标分页 — 简化实现：基于 id 的 keyset
            // 完整实现需处理自定义 sortBy，这里先用 id DESC
            val cursor = input.cursor
            if (!cursor.isNullOrBlank()) {
                try {
                    val cursorId = UUID.fromString(cursor)
                    where(table.getId<UUID>() lt cursorId)
                } catch (_: IllegalArgumentException) {
                    // 无效 cursor 忽略
                }
            }
            orderBy(table.getId<UUID>().desc())
            select(table)
        }.limit(limit + 1).execute()

        val hasMore = rows.size > limit
        val items = if (hasMore) rows.subList(0, limit) else rows
        val nextCursor = if (hasMore && items.isNotEmpty()) {
            // 获取最后一条的 id 作为下一页游标
            val lastEntity = items.last()
            // 通过反射获取 id（Jimmer 生成的 immutable 对象）
            val idProp = entityType.java.getMethod("id")
            idProp.invoke(lastEntity).toString()
        } else null

        return Page(items, nextCursor, hasMore)
    }

    fun <V : View<E>> findByCursor(
        ctx: RequestContext,
        input: CursorQueryInput = CursorQueryInput(),
        viewType: KClass<V>,
        block: (KMutableRootQuery<E>.() -> Unit)? = null,
    ): Page<V> {
        val limit = input.effectiveLimit()
        val sql = sql(ctx)

        val rows = sql.createQuery(entityType) {
            block?.invoke(this)
            val cursor = input.cursor
            if (!cursor.isNullOrBlank()) {
                try {
                    val cursorId = UUID.fromString(cursor)
                    where(table.getId<UUID>() lt cursorId)
                } catch (_: IllegalArgumentException) {}
            }
            orderBy(table.getId<UUID>().desc())
            select(table)
        }.limit(limit + 1).execute()

        val hasMore = rows.size > limit
        val items = if (hasMore) rows.subList(0, limit) else rows
        // Convert to view
        val views = sql.entities.findByIds(viewType, items.map {
            val idProp = entityType.java.getMethod("id")
            idProp.invoke(it) as UUID
        })
        val nextCursor = if (hasMore && items.isNotEmpty()) {
            val idProp = entityType.java.getMethod("id")
            idProp.invoke(items.last()).toString()
        } else null

        return Page(views, nextCursor, hasMore)
    }
}
```

- [ ] **步骤 3：创建 BaseAppCrudRepository**

```kotlin
// core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/base/BaseAppCrudRepository.kt
package com.ifmix.api.core.common.jimmer.base

import com.ifmix.api.core.common.jimmer.cluster.ClusterRegistry
import com.ifmix.api.core.common.jimmer.entity.AppScopedProps
import kotlin.reflect.KClass

/**
 * 面向多租户实体的 Repository。
 * 类型约束要求 E 实现 AppScopedProps，AppScopedFilter 全局自动注入租户过滤。
 */
abstract class BaseAppCrudRepository<E : AppScopedProps>(
    clusterRegistry: ClusterRegistry,
    entityType: KClass<E>,
) : BaseCrudRepository<E>(clusterRegistry, entityType)
```

- [ ] **步骤 4：编写 BaseCrudRepositoryTest（集成测试）**

```kotlin
// core-api/src/test/kotlin/com/ifmix/api/core/common/jimmer/base/BaseCrudRepositoryTest.kt
package com.ifmix.api.core.common.jimmer.base

import com.ifmix.api.core.common.db.CursorQueryInput
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.common.jimmer.cluster.ClusterRegistry
import com.ifmix.api.core.common.jimmer.entity.todo.Todo
import com.ifmix.api.core.common.jimmer.filter.RequestContextHolder
import com.ifmix.api.core.support.AbstractJimmerTest
import assertk.assertThat
import assertk.assertions.*
import org.babyfish.jimmer.kt.new
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class BaseCrudRepositoryTest : AbstractJimmerTest() {

    private lateinit var registry: ClusterRegistry
    private lateinit var repo: TestTodoRepository

    private val ctx = RequestContext(appId = "00000000-0000-0000-0000-000000000001")

    @BeforeEach
    fun setUp() {
        registry = createTestRegistry()
        // Run Flyway
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        repo = TestTodoRepository(registry)
        RequestContextHolder.set(ctx)
    }

    @AfterEach
    fun tearDown() {
        RequestContextHolder.clear()
        registry.destroy()
    }

    @Test
    fun `insert and findById round-trip`() {
        val now = Instant.now()
        val todo = new(Todo::class).by {
            appId = UUID.fromString(ctx.appId)
            title = "test todo"
            done = false
            createdAt = now
            updatedAt = now
        }
        val saved = repo.save(ctx, todo)
        val found = repo.findById(ctx, saved.id)
        assertThat(found).isNotNull()
        assertThat(found!!.title).isEqualTo("test todo")
        assertThat(found.done).isFalse()
    }

    @Test
    fun `deleteById soft-deletes`() {
        val now = Instant.now()
        val todo = new(Todo::class).by {
            appId = UUID.fromString(ctx.appId)
            title = "to delete"
            done = false
            createdAt = now
            updatedAt = now
        }
        val saved = repo.save(ctx, todo)
        repo.deleteById(ctx, saved.id)
        // 软删后 findById 应返回 null（@LogicalDeleted 过滤）
        val found = repo.findById(ctx, saved.id)
        assertThat(found).isNull()
    }

    @Test
    fun `findByCursor returns page with cursor`() {
        val now = Instant.now()
        repeat(5) { i ->
            val todo = new(Todo::class).by {
                appId = UUID.fromString(ctx.appId)
                title = "todo-$i"
                done = false
                createdAt = now
                updatedAt = now
            }
            repo.save(ctx, todo)
        }
        val page = repo.findByCursor(ctx, CursorQueryInput(limit = 3))
        assertThat(page.items).hasSize(3)
        assertThat(page.hasMore).isTrue()
        assertThat(page.nextCursor).isNotNull()

        // 第二页
        val page2 = repo.findByCursor(ctx, CursorQueryInput(cursor = page.nextCursor, limit = 3))
        assertThat(page2.items).hasSize(2)
        assertThat(page2.hasMore).isFalse()
    }
}

/** 测试用的具体 Repository 实现 */
private class TestTodoRepository(registry: ClusterRegistry) :
    BaseAppCrudRepository<Todo>(registry, Todo::class)
```

- [ ] **步骤 5：运行 BaseCrudRepositoryTest 验证通过**

运行：`./gradlew :core-api:test --tests "*.BaseCrudRepositoryTest" 2>&1 | tail -10`
预期：3 个测试 PASS

- [ ] **步骤 6：Commit**

```bash
git add -A
git commit -m "feat: implement BaseCrudRepository + BaseAppCrudRepository with cursor pagination"
```

---

## 任务 7：BaseCrudService + BaseAppCrudService

**文件：**
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/base/BaseCrudService.kt`
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/base/BaseAppCrudService.kt`

- [ ] **步骤 1：创建 BaseCrudService**

```kotlin
// core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/base/BaseCrudService.kt
package com.ifmix.api.core.common.jimmer.base

import com.ifmix.api.core.common.db.CursorQueryInput
import com.ifmix.api.core.common.db.Page
import com.ifmix.api.core.common.http.ApiError
import com.ifmix.api.core.common.http.ErrorCode
import com.ifmix.api.core.common.http.RequestContext
import org.babyfish.jimmer.Input
import org.babyfish.jimmer.View
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.reflect.KClass

/**
 * 通用 Service 层。委托 BaseCrudRepository，标注事务。
 * 各模块 Service 继承后只需添加领域特有方法。
 */
open class BaseCrudService<E : Any>(
    protected val repo: BaseCrudRepository<E>,
) {
    @Transactional(readOnly = true)
    open fun findById(ctx: RequestContext, id: UUID): E? =
        repo.findById(ctx, id)

    @Transactional(readOnly = true)
    open fun getById(ctx: RequestContext, id: UUID): E =
        repo.findById(ctx, id) ?: throw ApiError(ErrorCode.NOT_FOUND)

    @Transactional(readOnly = true)
    open fun <V : View<E>> findById(ctx: RequestContext, id: UUID, viewType: KClass<V>): V? =
        repo.findById(ctx, id, viewType)

    @Transactional(readOnly = true)
    open fun <V : View<E>> getById(ctx: RequestContext, id: UUID, viewType: KClass<V>): V =
        repo.findById(ctx, id, viewType) ?: throw ApiError(ErrorCode.NOT_FOUND)

    @Transactional(readOnly = true)
    open fun findByCursor(ctx: RequestContext, input: CursorQueryInput = CursorQueryInput()): Page<E> =
        repo.findByCursor(ctx, input)

    @Transactional(readOnly = true)
    open fun <V : View<E>> findByCursor(
        ctx: RequestContext,
        input: CursorQueryInput = CursorQueryInput(),
        viewType: KClass<V>,
    ): Page<V> = repo.findByCursor(ctx, input, viewType)

    @Transactional
    open fun create(ctx: RequestContext, input: Input<E>): E =
        repo.insert(ctx, input)

    @Transactional
    open fun update(ctx: RequestContext, input: Input<E>): E =
        repo.update(ctx, input)

    @Transactional
    open fun save(ctx: RequestContext, input: Input<E>): E =
        repo.save(ctx, input)

    @Transactional
    open fun deleteById(ctx: RequestContext, id: UUID) =
        repo.deleteById(ctx, id)
}
```

- [ ] **步骤 2：创建 BaseAppCrudService**

```kotlin
// core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/base/BaseAppCrudService.kt
package com.ifmix.api.core.common.jimmer.base

import com.ifmix.api.core.common.jimmer.entity.AppScopedProps

/**
 * 面向多租户实体的 Service。
 * 类型约束要求 E 实现 AppScopedProps。
 */
open class BaseAppCrudService<E : AppScopedProps>(
    repo: BaseAppCrudRepository<E>,
) : BaseCrudService<E>(repo)
```

- [ ] **步骤 3：验证编译通过**

运行：`./gradlew :core-api:compileKotlin 2>&1 | tail -5`
预期：BUILD SUCCESSFUL

- [ ] **步骤 4：Commit**

```bash
git add -A
git commit -m "feat: implement BaseCrudService + BaseAppCrudService"
```

---

## 任务 8：迁移 Todo 模块

**文件：**
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/repository/todo/TodoRepository.kt`
- 修改：`core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/TodoService.kt`
- 修改：`core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/TodoConfig.kt`
- 修改：`core-api/src/main/kotlin/com/ifmix/api/core/bff/customer/CustomerTodoController.kt`
- 删除：`core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/TodoDocument.kt`
- 删除：`core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/TodoItem.kt`
- 删除：`core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/TodoDtos.kt`
- 删除：`core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/TodoMapper.kt`
- 测试：`core-api/src/test/kotlin/com/ifmix/api/core/modules/todo/TodoServiceJimmerTest.kt`

- [ ] **步骤 1：编写 TodoServiceJimmerTest（失败测试）**

```kotlin
// core-api/src/test/kotlin/com/ifmix/api/core/modules/todo/TodoServiceJimmerTest.kt
package com.ifmix.api.core.modules.todo

import com.ifmix.api.core.common.db.CursorQueryInput
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.common.jimmer.dto.todo.TodoCreateInput
import com.ifmix.api.core.common.jimmer.dto.todo.TodoView
import com.ifmix.api.core.common.jimmer.filter.RequestContextHolder
import com.ifmix.api.core.common.jimmer.repository.todo.TodoRepository
import com.ifmix.api.core.support.AbstractJimmerTest
import assertk.assertThat
import assertk.assertions.*
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TodoServiceJimmerTest : AbstractJimmerTest() {

    private lateinit var service: TodoService

    private val ctx = RequestContext(appId = "00000000-0000-0000-0000-000000000001")

    @BeforeEach
    fun setUp() {
        val registry = createTestRegistry()
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        val repo = TodoRepository(registry)
        service = TodoService(repo)
        RequestContextHolder.set(ctx)
    }

    @AfterEach
    fun tearDown() {
        RequestContextHolder.clear()
    }

    @Test
    fun `create and get round-trip`() {
        val input = TodoCreateInput(title = "buy milk", done = false)
        val created = service.create(ctx, input)
        assertThat(created.id).isNotNull()

        val found = service.getById(ctx, created.id, TodoView::class)
        assertThat(found.title).isEqualTo("buy milk")
        assertThat(found.done).isFalse()
    }

    @Test
    fun `delete soft-deletes and hides from queries`() {
        val input = TodoCreateInput(title = "to delete", done = false)
        val created = service.create(ctx, input)
        service.deleteById(ctx, created.id)
        val found = service.findById(ctx, created.id)
        assertThat(found).isNull()
    }

    @Test
    fun `findByCursor returns paginated results`() {
        repeat(5) { i ->
            service.create(ctx, TodoCreateInput(title = "todo-$i", done = false))
        }
        val page = service.findByCursor(ctx, CursorQueryInput(limit = 3))
        assertThat(page.items).hasSize(3)
        assertThat(page.hasMore).isTrue()
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`./gradlew :core-api:test --tests "*.TodoServiceJimmerTest" 2>&1 | tail -5`
预期：编译失败，`TodoRepository` 和新的 `TodoService` 不存在

- [ ] **步骤 3：创建 TodoRepository**

```kotlin
// core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/repository/todo/TodoRepository.kt
package com.ifmix.api.core.common.jimmer.repository.todo

import com.ifmix.api.core.common.jimmer.base.BaseAppCrudRepository
import com.ifmix.api.core.common.jimmer.cluster.ClusterRegistry
import com.ifmix.api.core.common.jimmer.entity.todo.Todo
import org.springframework.stereotype.Component

@Component
class TodoRepository(
    clusterRegistry: ClusterRegistry,
) : BaseAppCrudRepository<Todo>(clusterRegistry, Todo::class)
```

- [ ] **步骤 4：重写 TodoService**

```kotlin
// core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/TodoService.kt
package com.ifmix.api.core.modules.todo

import com.ifmix.api.core.common.jimmer.base.BaseAppCrudService
import com.ifmix.api.core.common.jimmer.entity.todo.Todo
import com.ifmix.api.core.common.jimmer.repository.todo.TodoRepository
import org.springframework.stereotype.Service

/**
 * Todo 业务逻辑。标准 CRUD 由 BaseAppCrudService 提供。
 * 领域特有方法在这里追加。
 */
@Service
class TodoService(
    todoRepo: TodoRepository,
) : BaseAppCrudService<Todo>(todoRepo)
```

- [ ] **步骤 5：重写 TodoConfig（简化）**

TodoService 现在是 `@Service`，TodoRepository 是 `@Component`，不再需要手动 `@Bean` 装配。
删除整个文件或替换为空 Config：

```kotlin
// core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/TodoConfig.kt
package com.ifmix.api.core.modules.todo

import org.springframework.context.annotation.Configuration

/** Todo 模块配置。Bean 由 @Service/@Component 自动扫描注册。 */
@Configuration
class TodoConfig
```

- [ ] **步骤 6：重写 CustomerTodoController**

```kotlin
// core-api/src/main/kotlin/com/ifmix/api/core/bff/customer/CustomerTodoController.kt
package com.ifmix.api.core.bff.customer

import com.ifmix.api.core.common.db.CursorQueryInput
import com.ifmix.api.core.common.db.Page
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.common.jimmer.dto.todo.TodoCreateInput
import com.ifmix.api.core.common.jimmer.dto.todo.TodoUpdateInput
import com.ifmix.api.core.common.jimmer.dto.todo.TodoView
import com.ifmix.api.core.common.jimmer.entity.todo.Todo
import com.ifmix.api.core.modules.todo.TodoService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*
import java.util.UUID

/** customer BFF 的 todo 路由。PUT=query，POST=mutation。返回值由信封 advice 自动包装。 */
@RestController
@RequestMapping("/customer/core")
class CustomerTodoController(private val todoService: TodoService) {

    @PutMapping("/query/todo/findByCursor")
    fun findByCursor(
        ctx: RequestContext,
        @RequestBody(required = false) input: CursorQueryInput?,
    ): Page<TodoView> =
        todoService.findByCursor(ctx, input ?: CursorQueryInput(), TodoView::class)

    @PutMapping("/query/todo/getById")
    fun getById(ctx: RequestContext, @Valid @RequestBody req: ByIdRequest): TodoView =
        todoService.getById(ctx, UUID.fromString(req.id!!), TodoView::class)

    @PostMapping("/mutation/todo/createOne")
    fun createOne(ctx: RequestContext, @Valid @RequestBody req: TodoCreateInput): TodoView {
        val created = todoService.create(ctx, req)
        return todoService.getById(ctx, created.id, TodoView::class)
    }

    @PostMapping("/mutation/todo/updateOne")
    fun updateOne(ctx: RequestContext, @Valid @RequestBody req: TodoUpdateInput): TodoView {
        val updated = todoService.update(ctx, req)
        return todoService.getById(ctx, updated.id, TodoView::class)
    }

    @PostMapping("/mutation/todo/deleteById")
    fun deleteById(ctx: RequestContext, @Valid @RequestBody req: ByIdRequest): DeleteResult {
        todoService.deleteById(ctx, UUID.fromString(req.id!!))
        return DeleteResult(deleted = true)
    }
}

/** 通用的 by-id 请求体（保留向后兼容） */
data class ByIdRequest(val id: String? = null)

data class DeleteResult(val deleted: Boolean)
```

- [ ] **步骤 7：删除旧 MongoDB 相关文件**

```bash
rm core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/TodoDocument.kt
rm core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/TodoItem.kt
rm core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/TodoDtos.kt
rm core-api/src/main/kotlin/com/ifmix/api/core/modules/todo/TodoMapper.kt
```

- [ ] **步骤 8：运行 TodoServiceJimmerTest 验证通过**

运行：`./gradlew :core-api:test --tests "*.TodoServiceJimmerTest" 2>&1 | tail -10`
预期：3 个测试 PASS

- [ ] **步骤 9：删除旧的 MongoDB 测试文件**

```bash
rm core-api/src/test/kotlin/com/ifmix/api/core/modules/todo/TodoServiceTest.kt
rm core-api/src/test/kotlin/com/ifmix/api/core/modules/todo/TodoMapperTest.kt
```

- [ ] **步骤 10：Commit**

```bash
git add -A
git commit -m "feat: migrate Todo module from MongoDB to Jimmer + PostgreSQL"
```

---

## 任务 9：迁移 Feedback 模块

**文件：**
- 创建：`core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/repository/feedback/FeedbackRepository.kt`
- 修改：`core-api/src/main/kotlin/com/ifmix/api/core/modules/feedback/FeedbackService.kt`
- 修改：`core-api/src/main/kotlin/com/ifmix/api/core/modules/feedback/FeedbackConfig.kt`
- 修改：`core-api/src/main/kotlin/com/ifmix/api/core/bff/customer/CustomerFeedbackController.kt`
- 删除：`core-api/src/main/kotlin/com/ifmix/api/core/modules/feedback/FeedbackDocument.kt`
- 删除：`core-api/src/main/kotlin/com/ifmix/api/core/modules/feedback/FeedbackDtos.kt`
- 测试：`core-api/src/test/kotlin/com/ifmix/api/core/modules/feedback/FeedbackServiceJimmerTest.kt`

- [ ] **步骤 1：编写 FeedbackServiceJimmerTest（失败测试）**

```kotlin
// core-api/src/test/kotlin/com/ifmix/api/core/modules/feedback/FeedbackServiceJimmerTest.kt
package com.ifmix.api.core.modules.feedback

import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.common.jimmer.dto.feedback.FeedbackCreateInput
import com.ifmix.api.core.common.jimmer.dto.feedback.FeedbackView
import com.ifmix.api.core.common.jimmer.filter.RequestContextHolder
import com.ifmix.api.core.common.jimmer.repository.feedback.FeedbackRepository
import com.ifmix.api.core.support.AbstractJimmerTest
import assertk.assertThat
import assertk.assertions.*
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

class FeedbackServiceJimmerTest : AbstractJimmerTest() {

    private lateinit var service: FeedbackService

    private val ctx = RequestContext(
        appId = "00000000-0000-0000-0000-000000000001",
        installId = "00000000-0000-0000-0000-000000000099",
        userId = "00000000-0000-0000-0000-000000000088",
    )

    @BeforeEach
    fun setUp() {
        val registry = createTestRegistry()
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        val repo = FeedbackRepository(registry)
        service = FeedbackService(repo)
        RequestContextHolder.set(ctx)
    }

    @AfterEach
    fun tearDown() {
        RequestContextHolder.clear()
    }

    @Test
    fun `submit feedback persists and is retrievable`() {
        val input = FeedbackCreateInput(
            installId = UUID.fromString(ctx.installId),
            userId = UUID.fromString(ctx.userId),
            scanRecordId = null,
            category = "PRICE_MISSING",
            comment = "No price shown",
        )
        val created = service.submit(ctx, input)
        assertThat(created.id).isNotNull()

        val found = service.getById(ctx, created.id, FeedbackView::class)
        assertThat(found.category).isEqualTo("PRICE_MISSING")
        assertThat(found.comment).isEqualTo("No price shown")
        assertThat(found.installId).isEqualTo(UUID.fromString(ctx.installId))
    }

    @Test
    fun `submit feedback without optional fields`() {
        val input = FeedbackCreateInput(
            installId = UUID.fromString(ctx.installId),
            userId = null,
            scanRecordId = null,
            category = "LIKED",
            comment = null,
        )
        val created = service.submit(ctx, input)
        assertThat(created.id).isNotNull()

        val found = service.getById(ctx, created.id, FeedbackView::class)
        assertThat(found.comment).isNull()
        assertThat(found.userId).isNull()
    }
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`./gradlew :core-api:test --tests "*.FeedbackServiceJimmerTest" 2>&1 | tail -5`
预期：编译失败

- [ ] **步骤 3：创建 FeedbackRepository**

```kotlin
// core-api/src/main/kotlin/com/ifmix/api/core/common/jimmer/repository/feedback/FeedbackRepository.kt
package com.ifmix.api.core.common.jimmer.repository.feedback

import com.ifmix.api.core.common.jimmer.base.BaseAppCrudRepository
import com.ifmix.api.core.common.jimmer.cluster.ClusterRegistry
import com.ifmix.api.core.common.jimmer.entity.feedback.Feedback
import org.springframework.stereotype.Component

@Component
class FeedbackRepository(
    clusterRegistry: ClusterRegistry,
) : BaseAppCrudRepository<Feedback>(clusterRegistry, Feedback::class)
```

- [ ] **步骤 4：重写 FeedbackService**

```kotlin
// core-api/src/main/kotlin/com/ifmix/api/core/modules/feedback/FeedbackService.kt
package com.ifmix.api.core.modules.feedback

import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.common.jimmer.base.BaseAppCrudService
import com.ifmix.api.core.common.jimmer.dto.feedback.FeedbackCreateInput
import com.ifmix.api.core.common.jimmer.entity.feedback.Feedback
import com.ifmix.api.core.common.jimmer.repository.feedback.FeedbackRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Feedback 业务逻辑。追加式写入，不软删。
 */
@Service
class FeedbackService(
    feedbackRepo: FeedbackRepository,
) : BaseAppCrudService<Feedback>(feedbackRepo) {

    /** 提交反馈，返回新建实体。 */
    @Transactional
    fun submit(ctx: RequestContext, input: FeedbackCreateInput): Feedback =
        create(ctx, input)
}
```

- [ ] **步骤 5：重写 FeedbackConfig（简化）**

```kotlin
// core-api/src/main/kotlin/com/ifmix/api/core/modules/feedback/FeedbackConfig.kt
package com.ifmix.api.core.modules.feedback

import org.springframework.context.annotation.Configuration

/** Feedback 模块配置。Bean 由 @Service/@Component 自动扫描注册。 */
@Configuration
class FeedbackConfig
```

- [ ] **步骤 6：重写 CustomerFeedbackController**

```kotlin
// core-api/src/main/kotlin/com/ifmix/api/core/bff/customer/CustomerFeedbackController.kt
package com.ifmix.api.core.bff.customer

import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.common.jimmer.dto.feedback.FeedbackCreateInput
import com.ifmix.api.core.common.jimmer.dto.feedback.FeedbackView
import com.ifmix.api.core.modules.feedback.FeedbackService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

/** customer BFF 的反馈路由。 */
@RestController
@RequestMapping("/customer/core")
class CustomerFeedbackController(private val feedbackService: FeedbackService) {

    /** 提交反馈。 */
    @PostMapping("/mutation/feedback/submit")
    fun submit(ctx: RequestContext, @Valid @RequestBody req: FeedbackCreateInput): FeedbackView {
        val created = feedbackService.submit(ctx, req)
        return feedbackService.getById(ctx, created.id, FeedbackView::class)
    }
}
```

- [ ] **步骤 7：删除旧 MongoDB 相关文件**

```bash
rm core-api/src/main/kotlin/com/ifmix/api/core/modules/feedback/FeedbackDocument.kt
rm core-api/src/main/kotlin/com/ifmix/api/core/modules/feedback/FeedbackDtos.kt
```

- [ ] **步骤 8：运行 FeedbackServiceJimmerTest 验证通过**

运行：`./gradlew :core-api:test --tests "*.FeedbackServiceJimmerTest" 2>&1 | tail -10`
预期：2 个测试 PASS

- [ ] **步骤 9：删除旧的 MongoDB 测试**

```bash
rm core-api/src/test/kotlin/com/ifmix/api/core/modules/feedback/FeedbackServiceTest.kt
rm core-api/src/test/kotlin/com/ifmix/api/core/modules/feedback/FeedbackIntegrationTest.kt
```

- [ ] **步骤 10：Commit**

```bash
git add -A
git commit -m "feat: migrate Feedback module from MongoDB to Jimmer + PostgreSQL"
```

---

## 任务 10：RequestContext 调整 + RequestContextHolder 集成

**文件：**
- 修改：`core-api/src/main/kotlin/com/ifmix/api/core/common/http/RequestContext.kt`
- 修改：`core-api/src/main/kotlin/com/ifmix/api/core/common/http/RequestContextArgumentResolver.kt`

- [ ] **步骤 1：从 RequestContext 移除 MongoDB readPreference**

将 `RequestContext.kt` 修改为：

```kotlin
package com.ifmix.api.core.common.http

/**
 * 不可变请求上下文，显式作为方法参数在 controller -> service -> repo 之间传递。
 * appId 非空（由请求头校验保证）；其余可空。
 *
 * 读写分离由 @Transactional(readOnly=true) + AbstractRoutingDataSource 自动处理。
 */
data class RequestContext(
    val appId: String,
    val installId: String? = null,
    val lang: String? = null,
    val currency: String? = null,
    val country: String? = null,
    val clientPlatform: ClientPlatform? = null,
    val userId: String? = null,
)
```

- [ ] **步骤 2：在 RequestContextArgumentResolver 中设置 RequestContextHolder**

在现有的 `resolveArgument()` 方法末尾（构造 `RequestContext` 后、return 前），添加：

```kotlin
import com.ifmix.api.core.common.jimmer.filter.RequestContextHolder

// 在 resolveArgument() 中：
val ctx = RequestContext(...)
RequestContextHolder.set(ctx)
return ctx
```

同时需要在请求结束时清理。在 `WebConfig` 中注册一个 `HandlerInterceptor.afterCompletion` 调用 `RequestContextHolder.clear()`。

或者直接在现有的 `HeaderValidationInterceptor` 的 `afterCompletion` 中添加：

```kotlin
override fun afterCompletion(request: HttpServletRequest, response: HttpServletResponse, handler: Any, ex: Exception?) {
    RequestContextHolder.clear()
}
```

- [ ] **步骤 3：验证现有测试中引用 readPreference 的地方已处理**

运行：`grep -r "readPreference" core-api/src/ --include="*.kt"`

对于所有引用处：
- `common/db/CRUDRepository.kt`：保留（MongoDB 模块仍在用）
- 测试中如果有用到 `ctx.readPreference`，确认不影响编译

- [ ] **步骤 4：验证编译通过**

运行：`./gradlew :core-api:compileKotlin 2>&1 | tail -5`
预期：BUILD SUCCESSFUL

- [ ] **步骤 5：Commit**

```bash
git add -A
git commit -m "refactor: remove readPreference from RequestContext, integrate RequestContextHolder"
```

---

## 任务 11：全量测试 + 最终验证

**文件：** 无新文件

- [ ] **步骤 1：运行全部测试**

运行：`./gradlew :core-api:test 2>&1 | tail -20`
预期：所有测试通过（Jimmer 新测试 + 未迁移模块的 MongoDB 测试并存）

- [ ] **步骤 2：验证应用能正常启动（需要本地 MongoDB + PG）**

运行：
```bash
cd /Users/jason/ai/myprojects/ifmix_server
SPRING_PROFILES_ACTIVE=local ./gradlew :core-api:bootRun 2>&1 | head -30
```
预期：应用成功启动，日志中可见 Flyway migration 执行和 Jimmer 初始化

- [ ] **步骤 3：验证 Swagger UI 中新 API 可用**

启动后访问 `http://localhost:3001/swagger-ui/index.html`
预期：todo 和 feedback 的端点仍然存在，参数类型已更新

- [ ] **步骤 4：最终 Commit**

```bash
git add -A
git commit -m "test: verify all tests pass after Jimmer migration phase 1" --allow-empty
```

---

## 注意事项与潜在问题

1. **Jimmer + Spring Boot 4 兼容性：** Jimmer 0.11.5 已修复 Spring Boot 4 的两个兼容性问题（#1264 DataSourceAutoConfiguration 包名变更，#1285 Jackson 3 序列化）。如果遇到 Jackson 3 相关问题，需要手动注册 `ImmutableModule`。

2. **KSP 配置：** Jimmer 的 `.dto` 文件需要通过 KSP 参数 `jimmer.dto.dirs` 指定位置。如果 DTO 生成失败，检查路径是否正确。

3. **ClusterRegistry 生命周期：** 测试中手动创建 `ClusterRegistry` 时，需要在 `@AfterEach` 中调用 `destroy()` 关闭连接池，否则 Testcontainers 可能报连接泄露。

4. **RequestContextHolder ThreadLocal：** 虚拟线程（`spring.threads.virtual.enabled=true`）下 ThreadLocal 仍然正常工作（虚拟线程有自己的 ThreadLocal）。但如果未来切换到响应式，需要改用 Context 传播。

5. **MongoDB 共存：** `spring-boot-starter-data-mongodb` 的自动配置不影响 Jimmer。两者的 bean 不冲突。但如果 Jimmer 的 `jimmer-spring-boot-starter` 尝试自动创建 DataSource，需要通过 `@SpringBootApplication(exclude = ...)` 排除其 DataSource 自动配置（因为我们在 ClusterRegistry 中手动创建）。

6. **表名重命名：** 设计规范是单数 `todo` / `todo_item`，但 drizzle 项目中已有的表是 `todos` / `todo_items`。V1 baseline 按新规范建表。如果需要连接已有 `ifmix_core` 数据库中的数据，需要一个额外的 migration 来 `ALTER TABLE todos RENAME TO todo`。
