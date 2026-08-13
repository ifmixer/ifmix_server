# core-api 模块拆分 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 将单体 core-api 拆分为 core-common + core-api + core-admin-api 三模块，同时重构数据源为多集群双 KSqlClient 路由。

**架构：** core-common 承载所有 Entity/Repository/共享 Service/Infra（纯 library），core-api 和 core-admin-api 各自作为 Spring Boot 应用，通过 RepoContext(clusterId, preferReader) 显式选择目标数据源，无 ThreadLocal。

**技术栈：** Kotlin 2.3.10 / Spring Boot 4.1.0 / Jimmer 0.11.5 / Gradle 9.6.1 / PostgreSQL / Redis

**设计文档：** `docs/superpowers/specs/2026-08-13-core-module-split-design.md`

---

## 文件结构总览

### 新建文件

| 文件 | 职责 |
|------|------|
| `settings.gradle.kts` | 注册三模块 |
| `core-common/build.gradle.kts` | 共享库 Gradle 配置 |
| `core-admin-api/build.gradle.kts` | Admin app Gradle 配置 |
| `core-admin-api/src/main/kotlin/.../AdminApplication.kt` | Admin 启动入口 |
| `core-admin-api/src/main/resources/application.yml` | Admin 配置 |
| `core-common/src/main/kotlin/.../infra/jimmer/Cluster.kt` | 单集群数据结构 |
| `core-common/src/main/kotlin/.../infra/jimmer/AppDataSourceProperties.kt` | 多集群配置模型 |
| `core-common/src/main/kotlin/.../infra/jimmer/ClusterRouter.kt` | 请求级路由决策 |

### 修改文件

| 文件 | 变更 |
|------|------|
| `build.gradle.kts` (root) | 保持不变（已正确） |
| `core-api/build.gradle.kts` | 改为依赖 core-common，移除重复依赖 |
| `core-common/.../infra/db/RepoContext.kt` | 加 clusterId + preferReader |
| `core-common/.../infra/http/RequestContext.kt` | OperationContext 加 clusterId/globalClusterId |
| `core-common/.../infra/jimmer/ClusterRegistry.kt` | 重写为多集群注册 |
| `core-common/.../infra/jimmer/JimmerConfig.kt` | 适配新 ClusterRegistry |
| `core-common/.../infra/repo/BaseCrudRepository.kt` | 注入 ClusterRegistry，按 ctx 选 client |
| `core-common/.../infra/repo/BaseAppCrudRepository.kt` | 同上 |
| 所有具体 Repository | 改构造器：KSqlClient → ClusterRegistry |

### 移动文件（core-api → core-common）

- `entity/` 全部
- `modules/` 全部（repo + service + dto）
- `infra/` 全部
- `src/main/resources/db/migration/` → 分拆为 global/ 和 tenant/
- `src/main/dto/` → 拆分（共享的留 core-common，customer 专有留 core-api）

### 移动文件（core-api → core-admin-api）

- `bff/webhooks/WebhookController.kt`
- `bff/app/AppConfigController.kt`

### 删除文件

- `core-common/.../infra/jimmer/ReadWriteRoutingDataSource.kt`（不再需要）
- `core-common/.../infra/jimmer/ClusterProperties.kt`（被 AppDataSourceProperties 替代）

---

## 任务 1：创建 core-common 模块 + Gradle 多模块结构

**文件：**
- 修改：`settings.gradle.kts`
- 创建：`core-common/build.gradle.kts`
- 修改：`core-api/build.gradle.kts`

- [ ] **步骤 1：修改 settings.gradle.kts**

```kotlin
rootProject.name = "ifmix-server"

include("core-common", "core-api", "core-admin-api")
```

- [ ] **步骤 2：创建 core-common/build.gradle.kts**

```kotlin
plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("io.spring.dependency-management")
    id("com.google.devtools.ksp")
}

dependencies {
    api("org.springframework.boot:spring-boot-starter-web")
    api("org.springframework.boot:spring-boot-starter-data-redis")
    api("org.springframework.boot:spring-boot-starter-validation")
    api("tools.jackson.module:jackson-module-kotlin")
    api("org.jetbrains.kotlin:kotlin-reflect")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    val jimmerVersion = rootProject.extra["jimmerVersion"] as String
    api("org.babyfish.jimmer:jimmer-spring-boot-starter:$jimmerVersion")
    api("org.babyfish.jimmer:jimmer-sql-kotlin:$jimmerVersion")
    ksp("org.babyfish.jimmer:jimmer-ksp:$jimmerVersion")

    api("org.postgresql:postgresql")
    api("org.flywaydb:flyway-core")
    api("org.flywaydb:flyway-database-postgresql")

    api("org.springframework.security:spring-security-oauth2-jose")
    api("com.nimbusds:nimbus-jose-jwt:9.40")
    api("com.google.crypto.tink:tink:1.15.0")

    api(platform("software.amazon.awssdk:bom:2.31.7"))
    api("software.amazon.awssdk:s3")

    api(platform("org.springframework.ai:spring-ai-bom:2.0.0"))
    api("org.springframework.ai:spring-ai-starter-model-openai") {
        exclude(group = "io.swagger.core.v3", module = "swagger-annotations")
    }
    api("com.openai:openai-java-client-okhttp:4.39.1") {
        exclude(group = "io.swagger.core.v3", module = "swagger-annotations")
    }

    api("io.netty:netty-resolver-dns-native-macos::osx-aarch_64")
    api("com.fasterxml.uuid:java-uuid-generator:5.1.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:0.28.1")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.testcontainers:postgresql:1.20.6")
    testImplementation("org.testcontainers:junit-jupiter:1.20.6")
    testImplementation("com.redis:testcontainers-redis:2.2.4")
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

ksp {
    arg("jimmer.language", "kotlin")
}
```

- [ ] **步骤 3：修改 core-api/build.gradle.kts**

移除所有已在 core-common 中声明的依赖，改为 `implementation(project(":core-common"))`：

```kotlin
plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("com.google.devtools.ksp")
}

dependencies {
    implementation(project(":core-common"))

    val jimmerVersion = rootProject.extra["jimmerVersion"] as String
    ksp("org.babyfish.jimmer:jimmer-ksp:$jimmerVersion")

    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:0.28.1")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.testcontainers:postgresql:1.20.6")
    testImplementation("org.testcontainers:junit-jupiter:1.20.6")
    testImplementation("com.redis:testcontainers-redis:2.2.4")
    testImplementation("org.wiremock:wiremock-standalone:3.12.1")
    testImplementation("com.h2database:h2")
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

ksp {
    arg("jimmer.language", "kotlin")
    arg("jimmer.dto.dirs", "src/main/dto")
    arg("jimmer.dto.defaultNullableInputModifier", "fuzzy")
}
```

- [ ] **步骤 4：验证 Gradle sync 通过**

运行：`./gradlew projects`
预期：输出包含 `:core-common`, `:core-api`, `:core-admin-api`

- [ ] **步骤 5：Commit**

```bash
git add settings.gradle.kts core-common/build.gradle.kts core-api/build.gradle.kts
git commit -m "build: create core-common module, establish multi-module structure"
```

---

## 任务 2：移动 Entity + Infra + Modules 到 core-common

**文件：**
- 移动：`core-api/src/main/kotlin/com/ifmix/api/core/entity/` → `core-common/src/main/kotlin/com/ifmix/api/core/entity/`
- 移动：`core-api/src/main/kotlin/com/ifmix/api/core/infra/` → `core-common/src/main/kotlin/com/ifmix/api/core/infra/`
- 移动：`core-api/src/main/kotlin/com/ifmix/api/core/modules/` → `core-common/src/main/kotlin/com/ifmix/api/core/modules/`
- 移动：`core-api/src/main/resources/db/migration/` → `core-common/src/main/resources/db/migration/`
- 移动：`core-api/src/main/resources/application.yml` 中的共享配置 → core-common 的 `application-common.yml`

- [ ] **步骤 1：创建 core-common 目录结构并移动源码**

```bash
mkdir -p core-common/src/main/kotlin/com/ifmix/api/core
mkdir -p core-common/src/main/resources
mkdir -p core-common/src/test/kotlin

# 移动 entity, infra, modules
mv core-api/src/main/kotlin/com/ifmix/api/core/entity core-common/src/main/kotlin/com/ifmix/api/core/
mv core-api/src/main/kotlin/com/ifmix/api/core/infra core-common/src/main/kotlin/com/ifmix/api/core/
mv core-api/src/main/kotlin/com/ifmix/api/core/modules core-common/src/main/kotlin/com/ifmix/api/core/

# 移动 migration
mv core-api/src/main/resources/db core-common/src/main/resources/
```

- [ ] **步骤 2：移动 Jimmer DTO 源文件到 core-common（暂时全部，后续任务再拆分）**

```bash
# DTO 暂全部留在 core-api（KSP 在 core-api 处理），此步骤不移动
# .dto 文件需要依赖 core-common 编译产物才能生成，保持在 core-api/src/main/dto/
```

注意：`.dto` 文件**保留在 core-api**，因为 KSP 需要在同一模块生成 DTO class。core-common 只生成 Entity 元数据（Fetcher/Draft/Table/Props）。

- [ ] **步骤 3：验证 core-common 编译通过**

运行：`./gradlew :core-common:compileKotlin`
预期：BUILD SUCCESSFUL

- [ ] **步骤 4：验证 core-api 编译通过（依赖 core-common）**

运行：`./gradlew :core-api:compileKotlin`
预期：BUILD SUCCESSFUL

如果有 import 路径问题，修复 core-api 中 BFF 层对 modules/infra 的引用（路径不变，只是源码位置从 core-api 移到了 core-common，但 package 名不变，所以 import 不需要改）。

- [ ] **步骤 5：运行全部测试**

运行：`./gradlew :core-api:test`
预期：全部通过（测试仍在 core-api，依赖 core-common 编译产物）

- [ ] **步骤 6：Commit**

```bash
git add -A
git commit -m "refactor: move entity/infra/modules to core-common module"
```

---

## 任务 3：重构 RepoContext + OperationContext 支持多集群

**文件：**
- 修改：`core-common/src/main/kotlin/com/ifmix/api/core/infra/db/RepoContext.kt`
- 修改：`core-common/src/main/kotlin/com/ifmix/api/core/infra/http/RequestContext.kt`

- [ ] **步骤 1：改造 RepoContext**

将 `core-common/src/main/kotlin/com/ifmix/api/core/infra/db/RepoContext.kt` 改为：

```kotlin
package com.ifmix.api.core.infra.db

/**
 * Repository 层基础设施上下文 — 决定数据路由。
 *
 * - clusterId: 目标 PG 集群
 * - preferReader: true = reader 节点（autoCommit，无事务）; false = writer 节点
 */
data class RepoContext(
    val clusterId: String,
    val preferReader: Boolean = false,
) {
    companion object {
        /** 向后兼容：初期所有请求走 default cluster writer */
        val DEFAULT = RepoContext(clusterId = "default", preferReader = false)
    }
}
```

- [ ] **步骤 2：改造 OperationContext**

修改 `core-common/src/main/kotlin/com/ifmix/api/core/infra/http/RequestContext.kt` 中的 `OperationContext`：

```kotlin
data class OperationContext(
    val appId: UUID? = null,
    val installId: UUID? = null,
    val lang: String? = null,
    val currency: String? = null,
    val country: String? = null,
    val clientPlatform: ClientPlatform? = null,
    val userId: UUID? = null,
    val clientIp: String? = null,
    /** 业务数据目标集群 ID（请求入口由 ClusterRouter 解析） */
    val clusterId: String = "default",
    /** 全局集群 ID（auth 等共享数据，初期 = clusterId） */
    val globalClusterId: String = "default",
    /** true = 读操作走 reader 节点 */
    val readFromReplica: Boolean = false,
) {
    /** 业务数据 RepoContext */
    val repoCtx: RepoContext
        get() = RepoContext(clusterId = clusterId, preferReader = readFromReplica)

    /** Auth / 全局数据 RepoContext */
    val globalRepoCtx: RepoContext
        get() = RepoContext(clusterId = globalClusterId, preferReader = readFromReplica)
}
```

- [ ] **步骤 3：修复所有使用 `RepoContext.DEFAULT` 的地方**

全局搜索 `RepoContext.DEFAULT` 和 `ctx.repoCtx`，确保编译通过。`RepoContext.DEFAULT` 仍然可用（向后兼容），但含义变为 `clusterId = "default", preferReader = false`。

运行：`./gradlew :core-common:compileKotlin`
预期：BUILD SUCCESSFUL

- [ ] **步骤 4：编译全模块验证**

运行：`./gradlew :core-api:compileKotlin`
预期：BUILD SUCCESSFUL

- [ ] **步骤 5：Commit**

```bash
git add -A
git commit -m "refactor: RepoContext adds clusterId+preferReader, OperationContext adds cluster routing"
```

---

## 任务 4：重构 ClusterRegistry 为多集群 + 双 KSqlClient

**文件：**
- 创建：`core-common/src/main/kotlin/com/ifmix/api/core/infra/jimmer/Cluster.kt`
- 创建：`core-common/src/main/kotlin/com/ifmix/api/core/infra/jimmer/AppDataSourceProperties.kt`
- 创建：`core-common/src/main/kotlin/com/ifmix/api/core/infra/jimmer/ClusterRouter.kt`
- 重写：`core-common/src/main/kotlin/com/ifmix/api/core/infra/jimmer/ClusterRegistry.kt`
- 修改：`core-common/src/main/kotlin/com/ifmix/api/core/infra/jimmer/JimmerConfig.kt`
- 删除：`core-common/src/main/kotlin/com/ifmix/api/core/infra/jimmer/ClusterProperties.kt`
- 删除：`core-common/src/main/kotlin/com/ifmix/api/core/infra/jimmer/ReadWriteRoutingDataSource.kt`

- [ ] **步骤 1：创建 AppDataSourceProperties**

```kotlin
package com.ifmix.api.core.infra.jimmer

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app")
data class AppDataSourceProperties(
    val clusters: Map<String, ClusterProps> = mapOf("default" to ClusterProps()),
    val routing: RoutingProps = RoutingProps(),
    val showSql: Boolean = false,
) {
    data class ClusterProps(
        val writer: DataSourceProps = DataSourceProps(),
        val reader: DataSourceProps = DataSourceProps(),
    )
    data class DataSourceProps(
        val jdbcUrl: String = "",
        val username: String = "",
        val password: String = "",
        val maximumPoolSize: Int = 10,
    )
    data class RoutingProps(
        val defaultCluster: String = "default",
        val globalCluster: String = "default",
    )
}
```

- [ ] **步骤 2：创建 Cluster 数据类**

```kotlin
package com.ifmix.api.core.infra.jimmer

import com.zaxxer.hikari.HikariDataSource
import org.babyfish.jimmer.sql.kt.KSqlClient

/**
 * 一个物理 PG 集群：持有 reader + writer 两个 KSqlClient。
 */
class Cluster(
    val id: String,
    val writerDataSource: HikariDataSource,
    val readerDataSource: HikariDataSource,
    val writerClient: KSqlClient,
    val readerClient: KSqlClient,
) {
    fun sql(preferReader: Boolean): KSqlClient =
        if (preferReader) readerClient else writerClient

    fun close() {
        writerDataSource.close()
        readerDataSource.close()
    }
}
```

- [ ] **步骤 3：创建 ClusterRouter**

```kotlin
package com.ifmix.api.core.infra.jimmer

import org.springframework.stereotype.Component
import java.util.UUID

@Component
class ClusterRouter(private val props: AppDataSourceProperties) {

    fun resolveCluster(appId: UUID?): String = props.routing.defaultCluster

    fun resolveGlobalCluster(): String = props.routing.globalCluster
}
```

- [ ] **步骤 4：重写 ClusterRegistry**

```kotlin
package com.ifmix.api.core.infra.jimmer

import com.ifmix.api.core.infra.db.RepoContext
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import jakarta.annotation.PreDestroy
import org.babyfish.jimmer.sql.DraftInterceptor
import org.babyfish.jimmer.sql.dialect.PostgresDialect
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.newKSqlClient
import org.babyfish.jimmer.sql.runtime.Executor
import org.springframework.stereotype.Component

@Component
class ClusterRegistry(
    private val props: AppDataSourceProperties,
    private val draftInterceptors: List<DraftInterceptor<*, *>>,
) {
    private val clusters: Map<String, Cluster> by lazy {
        props.clusters.mapValues { (id, clusterProps) ->
            val writerDs = createDataSource(clusterProps.writer, "pg-$id-writer")
            val readerDs = createDataSource(clusterProps.reader, "pg-$id-reader")
            Cluster(
                id = id,
                writerDataSource = writerDs,
                readerDataSource = readerDs,
                writerClient = buildClient(writerDs, autoCommit = false),
                readerClient = buildClient(readerDs, autoCommit = true),
            )
        }
    }

    fun getCluster(clusterId: String): Cluster =
        clusters[clusterId]
            ?: clusters[props.routing.defaultCluster]
            ?: error("Cluster not found: $clusterId")

    /** 根据 RepoContext 获取目标 KSqlClient */
    fun sql(ctx: RepoContext): KSqlClient =
        getCluster(ctx.clusterId).sql(ctx.preferReader)

    val defaultCluster: Cluster get() = getCluster(props.routing.defaultCluster)

    /** Flyway 用 */
    val flywayDataSource: HikariDataSource get() = defaultCluster.writerDataSource

    @PreDestroy
    fun destroy() {
        clusters.values.forEach { it.close() }
    }

    private fun buildClient(ds: HikariDataSource, autoCommit: Boolean): KSqlClient {
        return newKSqlClient {
            setConnectionManager { block ->
                ds.connection.use { conn ->
                    if (autoCommit) conn.autoCommit = true
                    block(conn)
                }
            }
            setDialect(PostgresDialect())
            if (props.showSql) {
                setExecutor(Executor.log())
                setExecutorContextPrefixes(listOf("com.ifmix.api.core"))
            }
            for (interceptor in draftInterceptors) {
                addDraftInterceptor(interceptor)
            }
        }
    }

    private fun createDataSource(
        dsProps: AppDataSourceProperties.DataSourceProps,
        poolName: String,
    ): HikariDataSource {
        return HikariDataSource(HikariConfig().apply {
            jdbcUrl = dsProps.jdbcUrl
            username = dsProps.username
            password = dsProps.password
            maximumPoolSize = dsProps.maximumPoolSize
            this.poolName = poolName
        })
    }
}
```

- [ ] **步骤 5：修改 JimmerConfig**

```kotlin
package com.ifmix.api.core.infra.jimmer

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement

@Configuration
@EnableConfigurationProperties(AppDataSourceProperties::class)
@EnableTransactionManagement
class JimmerConfig(private val clusterRegistry: ClusterRegistry) {

    /** Spring TransactionManager 绑定到 default cluster writer */
    @Bean
    fun transactionManager(): PlatformTransactionManager =
        DataSourceTransactionManager(clusterRegistry.defaultCluster.writerDataSource)
}
```

- [ ] **步骤 6：删除旧文件**

```bash
rm core-common/src/main/kotlin/com/ifmix/api/core/infra/jimmer/ClusterProperties.kt
rm core-common/src/main/kotlin/com/ifmix/api/core/infra/jimmer/ReadWriteRoutingDataSource.kt
```

- [ ] **步骤 7：更新 application.yml 配置格式**

修改 `core-api/src/main/resources/application.yml`，将旧的 `app.datasource.writer/reader` 格式改为新的 `app.clusters.default.writer/reader`：

```yaml
app:
  clusters:
    default:
      writer:
        jdbc-url: ${PG_WRITER_URL:jdbc:postgresql://localhost:5432/ifmix_core_local}
        username: ${PG_USERNAME:postgres}
        password: ${PG_PASSWORD:postgres}
        maximum-pool-size: 20
      reader:
        jdbc-url: ${PG_READER_URL:jdbc:postgresql://localhost:5432/ifmix_core_local}
        username: ${PG_USERNAME:postgres}
        password: ${PG_PASSWORD:postgres}
        maximum-pool-size: 30
  routing:
    default-cluster: default
    global-cluster: default
  show-sql: ${APP_SHOW_SQL:false}
```

- [ ] **步骤 8：编译验证**

运行：`./gradlew :core-common:compileKotlin`
预期：BUILD SUCCESSFUL

- [ ] **步骤 9：Commit**

```bash
git add -A
git commit -m "refactor: multi-cluster ClusterRegistry with reader/writer KSqlClient pairs"
```

---

## 任务 5：改造 BaseRepository 使用 ClusterRegistry

**文件：**
- 修改：`core-common/src/main/kotlin/com/ifmix/api/core/infra/repo/BaseCrudRepository.kt`
- 修改：`core-common/src/main/kotlin/com/ifmix/api/core/infra/repo/BaseAppCrudRepository.kt`
- 修改：所有具体 Repository（构造器参数从 `KSqlClient` 改为 `ClusterRegistry`）

- [ ] **步骤 1：改造 BaseCrudRepository**

```kotlin
package com.ifmix.api.core.infra.repo

import com.ifmix.api.core.infra.db.RepoContext
import com.ifmix.api.core.infra.dto.CursorQueryInput
import com.ifmix.api.core.infra.dto.Page
import com.ifmix.api.core.infra.jimmer.ClusterRegistry
import org.babyfish.jimmer.Input
import org.babyfish.jimmer.View
import org.babyfish.jimmer.sql.ast.mutation.SaveMode
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.ast.expression.*
import java.util.UUID
import kotlin.reflect.KClass

/**
 * 通用 CRUD Repository。
 * 通过 ClusterRegistry 按 RepoContext 选择目标 KSqlClient。
 */
abstract class BaseCrudRepository<E : Any>(
    protected val clusterRegistry: ClusterRegistry,
    protected val entityType: KClass<E>,
) {
    /** 根据 ctx 选择目标集群 + 读/写 client */
    protected fun sql(ctx: RepoContext): KSqlClient = clusterRegistry.sql(ctx)

    /** 强制 writer（写操作） */
    protected fun writerSql(ctx: RepoContext): KSqlClient =
        clusterRegistry.getCluster(ctx.clusterId).sql(preferReader = false)

    open fun findById(ctx: RepoContext, id: UUID): E? =
        sql(ctx).entities.findById(entityType, id)

    open fun <V : View<E>> findById(ctx: RepoContext, id: UUID, viewType: KClass<V>): V? =
        sql(ctx).entities.findById(viewType, id)

    open fun findByIds(ctx: RepoContext, ids: List<UUID>): List<E> =
        if (ids.isEmpty()) emptyList() else sql(ctx).entities.findByIds(entityType, ids)

    open fun <V : View<E>> findByIds(ctx: RepoContext, ids: List<UUID>, viewType: KClass<V>): List<V> =
        if (ids.isEmpty()) emptyList() else sql(ctx).entities.findByIds(viewType, ids)

    open fun insert(ctx: RepoContext, input: Input<E>): E =
        writerSql(ctx).entities.save(input) { setMode(SaveMode.INSERT_ONLY) }.modifiedEntity

    open fun insertAll(ctx: RepoContext, inputs: List<Input<E>>): List<E> =
        if (inputs.isEmpty()) emptyList()
        else writerSql(ctx).entities.saveInputs(inputs) { setMode(SaveMode.INSERT_ONLY) }
            .items.map { it.modifiedEntity }

    open fun update(ctx: RepoContext, input: Input<E>): E =
        writerSql(ctx).entities.save(input) { setMode(SaveMode.UPDATE_ONLY) }.modifiedEntity

    open fun save(ctx: RepoContext, input: Input<E>): E =
        writerSql(ctx).entities.save(input).modifiedEntity

    open fun save(ctx: RepoContext, entity: E): E =
        writerSql(ctx).entities.save(entity).modifiedEntity

    open fun deleteById(ctx: RepoContext, id: UUID) {
        writerSql(ctx).entities.delete(entityType, id)
    }

    open fun findAll(ctx: RepoContext): List<E> =
        sql(ctx).entities.findAll(entityType)

    open fun findByCursor(ctx: RepoContext, input: CursorQueryInput = CursorQueryInput()): Page<E> {
        val limit = input.effectiveLimit()
        val cursor = input.cursor?.let {
            try { UUID.fromString(it) } catch (_: Exception) { null }
        }
        val items = sql(ctx).createQuery(entityType) {
            if (cursor != null) {
                where(table.getId<UUID>() lt cursor)
            }
            orderBy(table.getId<UUID>().desc())
            select(table)
        }.limit(limit + 1).execute()
        return Page.of(items, limit) { getEntityId(it)?.toString() }
    }

    protected fun getEntityId(entity: Any): UUID? {
        return try {
            entity.javaClass.getMethod("getId").invoke(entity) as? UUID
        } catch (_: Exception) { null }
    }
}
```

- [ ] **步骤 2：改造 BaseAppCrudRepository（同理，构造器改为 ClusterRegistry）**

将 `protected val sql: KSqlClient` 替换为 `protected val clusterRegistry: ClusterRegistry`，内部所有 `sql.xxx` 改为 `sql(ctx).xxx`（ctx 从方法参数传入）。

注意：`BaseAppCrudRepository` 的方法签名已经全部带 `ctx: RepoContext`，所以只需要改构造器和方法体中对 `sql` 的引用。

- [ ] **步骤 3：修改所有具体 Repository 的构造器**

全局搜索继承 `BaseCrudRepository` 和 `BaseAppCrudRepository` 的类，构造器参数从 `sql: KSqlClient` 改为 `clusterRegistry: ClusterRegistry`。

示例（`ScanRecordRepository`）：

```kotlin
// 之前
@Repository
class ScanRecordRepository(sql: KSqlClient) : BaseAppCrudRepository<ScanRecord>(sql, ScanRecord::class) {

// 之后
@Repository
class ScanRecordRepository(clusterRegistry: ClusterRegistry) : BaseAppCrudRepository<ScanRecord>(clusterRegistry, ScanRecord::class) {
```

所有 repository 需要同样修改：
- `ScanRecordRepository`
- `ScanCollectionRepository`
- `ScanCollectionItemRepository`
- `AppUserRepository`
- `AuthIdentityRepository`
- `AuthProviderIdentityRepository`
- `AuthDeviceSecretRepository`
- `AppRefreshTokenRepository`
- `AuthTenantRepository`
- `UserInstallBindingRepository`
- `SubscriptionRepository`
- `StoreNotificationRepository`
- `TodoRepository`
- `FeedbackRepository`
- `AgnesKeyRepository`
- `UploadRecordRepository`
- `AppInfoRepository`
- `AppConfigRevisionRepository`

- [ ] **步骤 4：编译验证**

运行：`./gradlew :core-common:compileKotlin`
预期：BUILD SUCCESSFUL

运行：`./gradlew :core-api:compileKotlin`
预期：BUILD SUCCESSFUL

- [ ] **步骤 5：运行测试**

运行：`./gradlew :core-api:test`
预期：全部通过

- [ ] **步骤 6：Commit**

```bash
git add -A
git commit -m "refactor: BaseRepository uses ClusterRegistry, route by RepoContext"
```

---

## 任务 6：Auth Repository 使用 globalRepoCtx

**文件：**
- 修改：所有 auth 模块 Service 中对 auth repo 的调用，确保传入 `ctx.globalRepoCtx`

- [ ] **步骤 1：检查 AuthService 中的 repo 调用**

在 `modules/auth/service/AuthService.kt` 中，所有对 auth repo 的调用应使用 `ctx.globalRepoCtx` 而非 `ctx.repoCtx`。

搜索 AuthService 中所有 `repo.findXxx(` / `repo.save(` 调用，将 `ctx.repoCtx`（或 `RepoContext.DEFAULT`）替换为 `ctx.globalRepoCtx`。

初期 globalRepoCtx 和 repoCtx 指向同一 cluster（都是 "default"），所以行为不变，但语义正确。

- [ ] **步骤 2：验证编译**

运行：`./gradlew :core-common:compileKotlin`
预期：BUILD SUCCESSFUL

- [ ] **步骤 3：运行 auth 相关测试**

运行：`./gradlew :core-api:test --tests "*Auth*"`
预期：通过

- [ ] **步骤 4：Commit**

```bash
git add -A
git commit -m "refactor: auth repositories use globalRepoCtx for future cluster isolation"
```

---

## 任务 7：创建 core-admin-api 骨架

**文件：**
- 创建：`core-admin-api/build.gradle.kts`
- 创建：`core-admin-api/src/main/kotlin/com/ifmix/api/admin/AdminApplication.kt`
- 创建：`core-admin-api/src/main/resources/application.yml`
- 移动：`core-api/.../bff/webhooks/WebhookController.kt` → `core-admin-api/.../bff/webhooks/`
- 移动：`core-api/.../bff/app/AppConfigController.kt` → `core-admin-api/.../bff/app/`

- [ ] **步骤 1：创建 core-admin-api/build.gradle.kts**

```kotlin
plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("com.google.devtools.ksp")
}

dependencies {
    implementation(project(":core-common"))

    val jimmerVersion = rootProject.extra["jimmerVersion"] as String
    ksp("org.babyfish.jimmer:jimmer-ksp:$jimmerVersion")

    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webflux")
    testImplementation("com.willowtreeapps.assertk:assertk-jvm:0.28.1")
    testImplementation("org.testcontainers:postgresql:1.20.6")
    testImplementation("org.testcontainers:junit-jupiter:1.20.6")
    testImplementation("com.redis:testcontainers-redis:2.2.4")
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

ksp {
    arg("jimmer.language", "kotlin")
    arg("jimmer.dto.dirs", "src/main/dto")
    arg("jimmer.dto.defaultNullableInputModifier", "fuzzy")
}
```

- [ ] **步骤 2：创建 AdminApplication.kt**

```kotlin
package com.ifmix.api.admin

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication(scanBasePackages = ["com.ifmix.api.core", "com.ifmix.api.admin"])
class AdminApplication

fun main(args: Array<String>) {
    runApplication<AdminApplication>(*args)
}
```

- [ ] **步骤 3：创建 application.yml**

```yaml
server:
  port: 3002

spring:
  flyway:
    enabled: false  # core-api 负责 migration

app:
  clusters:
    default:
      writer:
        jdbc-url: ${PG_WRITER_URL:jdbc:postgresql://localhost:5432/ifmix_core_local}
        username: ${PG_USERNAME:postgres}
        password: ${PG_PASSWORD:postgres}
        maximum-pool-size: 5
      reader:
        jdbc-url: ${PG_READER_URL:jdbc:postgresql://localhost:5432/ifmix_core_local}
        username: ${PG_USERNAME:postgres}
        password: ${PG_PASSWORD:postgres}
        maximum-pool-size: 10
  routing:
    default-cluster: default
    global-cluster: default
  show-sql: ${APP_SHOW_SQL:false}
```

- [ ] **步骤 4：移动 WebhookController 和 AppConfigController**

```bash
mkdir -p core-admin-api/src/main/kotlin/com/ifmix/api/admin/bff/webhooks
mkdir -p core-admin-api/src/main/kotlin/com/ifmix/api/admin/bff/app

mv core-api/src/main/kotlin/com/ifmix/api/core/bff/webhooks/WebhookController.kt \
   core-admin-api/src/main/kotlin/com/ifmix/api/admin/bff/webhooks/
mv core-api/src/main/kotlin/com/ifmix/api/core/bff/app/AppConfigController.kt \
   core-admin-api/src/main/kotlin/com/ifmix/api/admin/bff/app/
```

修改这两个文件的 `package` 声明：
- `WebhookController.kt`: `package com.ifmix.api.admin.bff.webhooks`
- `AppConfigController.kt`: `package com.ifmix.api.admin.bff.app`

import 路径无需修改（它们引用的 Service/Entity 都在 core-common 的 `com.ifmix.api.core` 下）。

- [ ] **步骤 5：删除 core-api 中已移走的目录**

```bash
rm -rf core-api/src/main/kotlin/com/ifmix/api/core/bff/webhooks
rm -rf core-api/src/main/kotlin/com/ifmix/api/core/bff/app
```

- [ ] **步骤 6：编译验证**

运行：`./gradlew :core-admin-api:compileKotlin`
预期：BUILD SUCCESSFUL

运行：`./gradlew :core-api:compileKotlin`
预期：BUILD SUCCESSFUL

- [ ] **步骤 7：Commit**

```bash
git add -A
git commit -m "feat: create core-admin-api module, move webhook+appconfig controllers"
```

---

## 任务 8：整体验证 + 清理

**文件：**
- 修改：删除 `.DS_Store` 和其他垃圾文件
- 修改：更新 `AGENTS.md` 和 `docs/ARCHITECTURE.md` 反映新结构

- [ ] **步骤 1：全模块编译**

运行：`./gradlew compileKotlin`
预期：三模块全部 BUILD SUCCESSFUL

- [ ] **步骤 2：全模块测试**

运行：`./gradlew test`
预期：core-common + core-api 测试通过（core-admin-api 暂无测试）

- [ ] **步骤 3：验证 core-api bootRun**

运行：`./gradlew :core-api:bootRun`（需本地 PG + Redis）
预期：启动成功，端口 3001

- [ ] **步骤 4：验证 core-admin-api bootRun**

运行：`./gradlew :core-admin-api:bootRun`（需本地 PG + Redis）
预期：启动成功，端口 3002

- [ ] **步骤 5：更新 AGENTS.md 常用命令**

```markdown
## 常用命令

```bash
# 编译
./gradlew compileKotlin                    # 全模块
./gradlew :core-common:compileKotlin       # 仅 common
./gradlew :core-api:compileKotlin          # 仅 customer api
./gradlew :core-admin-api:compileKotlin    # 仅 admin api

# 测试
./gradlew test                             # 全模块
./gradlew :core-api:test                   # Customer E2E
./gradlew :core-admin-api:test             # Admin E2E

# 运行 (需要 PostgreSQL + Redis)
./gradlew :core-api:bootRun                # Customer API (port 3001)
./gradlew :core-admin-api:bootRun          # Admin API (port 3002)
```
```

- [ ] **步骤 6：清理垃圾文件**

```bash
find . -name ".DS_Store" -delete
```

- [ ] **步骤 7：Commit**

```bash
git add -A
git commit -m "chore: update docs, clean up after module split"
```

---

## 注意事项

### Jimmer KSP 编译顺序
Gradle 自动处理模块依赖：先编译 core-common（生成 Entity 元数据），再编译 core-api/core-admin-api（生成 DTO class）。无需手动配置顺序。

### ClusterInitializer (Flyway)
现有的 `ClusterInitializer` 需要适配新的 `ClusterRegistry`——它从 `clusterRegistry.flywayDataSource` 获取 writer DataSource 执行 migration。确保只在 core-api 中启用。

### 测试中的 KSqlClient 注入
现有测试如果直接注入 `KSqlClient`，需要改为注入 `ClusterRegistry` 或提供 test bean。E2E 测试需要确保 `application.yml` (test) 中配置了 `app.clusters.default`。

### Auth 隔离规则（代码审查检查项）
- ❌ 业务 Entity 不得对 auth Entity 建立 `@ManyToOne` / `@OneToMany`
- ❌ 业务 Service 不得直接注入 auth Repository
- ✅ 业务 Service 可注入 AuthService（接口级依赖）
- ✅ Auth Repository 方法始终接收从 `ctx.globalRepoCtx` 构造的 RepoContext
