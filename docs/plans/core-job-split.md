# 计划：拆分 core-common + core-job（独立跑批进程）

> 状态：草案，待评审
> 范围：**仅 core-common 与 core-job**。core-api（含包名已改为 `com.ifmix.core.api`）由用户自行处理。
> 目标：把匿名 Customer 清理任务从 core-api 抽成独立的 Spring Batch 进程 core-job（单实例部署，
> 与多实例 API 隔离，避免定时任务冲突）；core-common 只放两模块**真正必要**的最小共享代码。

---

## 0. 已敲定的决策

- **core-job 独立进程**：`com.ifmix.core.job`，独立 `@SpringBootApplication`，单实例部署。
- **core-job 不用 Jimmer / 不写 entity / 不用 KSP**：清理是「游标扫候选 + 批量 DELETE」，用 **Spring Batch + JdbcClient/裸 SQL**。
  - 用 Spring Batch 的 chunk/reader/writer 替代手写的 `drainBatches`（游标分批、断点、防死循环内建）。
  - 彻底规避 KSP 跨模块风险（core-common 也不含 entity、不跑 KSP）。
- **core-common 只放必要的**（见 §2），保持为**纯 Kotlin 库**（无 KSP、无 entity、无 web、无 Jimmer runtime 依赖）。
- **core-api** 包名已是 `com.ifmix.core.api`，用户负责让它依赖 core-common 并调整 import。

---

## 1. 现状事实（已核对）

- 单模块 `core-api`（settings.gradle.kts 仅 include core-api）。
- 清理相关代码现在在 core-api：
  - `infra/config/SchedulingConfig.kt`（@EnableScheduling + @EnableConfigurationProperties）
  - `modules/customer/AnonymousCleanupConfig.kt`（@ConfigurationProperties app.customer.cleanup）
  - `modules/customer/handler/AnonymousCleanupScheduler.kt`（@Scheduled，依赖 11 个 Jimmer repo + GlobalTxRunner + ModuleCtxFactory + ClusterRouter）
  - `modules/customer/handler/AnonymousCleanupDecision.kt`（纯函数，零依赖）
- 候选共享项依赖分析：
  - `UuidV7`：仅依赖 com.fasterxml.uuid。纯工具。
  - `ErrorCode`：依赖 `org.springframework.http.HttpStatus` → **web 关注点，不进 common**（job 无 HTTP 语义）。
  - `ClusterProperties`（app.datasource writer/reader）：数据源配置结构，**两模块都连同一 PG，可共享**。
  - `AnonymousCleanupDecision`：纯函数，但**是 job 专有清理判定，放 job**，不进 common。
  - `ClusterInitializer`（Flyway migrate）：见 §4 迁移归属决策。

---

## 2. core-common 内容（最小必要）

> 原则：只放 **core-api 与 core-job 都真正需要** 且**不拖入 web/Jimmer/KSP** 的东西。

**放入 core-common（`com.ifmix.core.common`）：**
- `UuidV7`（纯工具；job 理论上可能生成 id，api 已用）— 依赖 com.fasterxml.uuid。
- `ClusterProperties` 数据源配置 data class（`app.datasource` 结构）— job 连库用同一配置形状。
  - ⚠️ 只放 **配置 data class**，不放 Jimmer 的 ClusterRegistry/Router（那些绑 KSqlClient，属 api）。

**暂不放入（YAGNI / 关注点不符）：**
- `ErrorCode` / `ApiError`（web 语义，api 专有）。
- `ModuleCtx` / `ModuleCtxFactory` / `GlobalTxRunner` / `CrudRepoTemplate`（全绑 Jimmer KSqlClient，api 专有；job 不用 Jimmer）。
- entity（api 各自持有；job 用裸 SQL 无需 entity）。
- `AnonymousCleanupDecision`（job 专有）。

> 结论：core-common 极薄——本期实际只有 `UuidV7` + `ClusterProperties` 两个候选。
> **待确认（Q1）**：是否值得为这两个类建独立模块？还是先只建 core-job、把这两个类复制/内联，
> 等未来共享面变大再抽 common？（见 §6 决策点）

---

## 3. core-job 内容（`com.ifmix.core.job`）

**Gradle 模块**：独立 Spring Boot 应用。
- 依赖：`spring-boot-starter`（非 web）、`spring-boot-starter-batch`、`spring-boot-starter-jdbc`、`postgresql`、
  `com.fasterxml.uuid`（如需）、core-common（若建）。
- **不依赖**：spring-web、DGS、Jimmer、KSP。

**代码：**
- `JobApplication.kt`：`@SpringBootApplication`，独立 main。
- `AnonymousCleanupConfig`：`@ConfigurationProperties(app.customer.cleanup)`（cron/batchSize/tombstoneWindowDays）— 从 api 复制过来。
- `AnonymousCleanupDecision`：纯函数 — 从 api 复制过来（零依赖）。
- Spring Batch Job 定义 `AnonymousCleanupJob`：
  - **两个 step**（未合并僵尸 / 已合并 tombstone），或一个 step 两段逻辑。
  - `JdbcCursorItemReader<UUID>`（或 paging reader）：服务端游标流式读候选 id
    - 僵尸候选 SQL：`SELECT id FROM customer WHERE app_id=? AND anonymous=true AND merged_to IS NULL ...`
    - tombstone 候选 SQL：`... merged_to IS NOT NULL AND updated_at < :cutoff`
  - processor：对每个候选查 hasValidToken / hasActiveSubscription（裸 SQL count/exists），交 `AnonymousCleanupDecision.shouldDelete` 裁决。
  - writer（chunk）：对判定为删的 id 批量 `DELETE`（同事务先删资源表再删 customer，避免孤儿行）：
    - `ai_scan_collection_item`(按 collection) → `ai_scan_collection` → `ai_scan_record` → `media_upload_record` → `demo_todo` → `cms_feedback` → `pay_subscription` → `customer`。
  - 触发：`@Scheduled(cron)` 启动 JobLauncher，或用 Spring Batch 的调度。
  - **单实例保证**：见 §4。
- `application.yml`（job）：`spring.batch.jdbc.initialize-schema`（Batch 元数据表）、数据源指向同一 PG、
  `spring.main.web-application-type=none`（非 web 进程）。

**删除安全性（沿用阶段6结论，绝不误删正常用户）：**
候选 SQL 已排除 `anonymous=false AND merged_to IS NULL`；`shouldDelete` 对该组合恒 false；active 订阅一律跳过。

---

## 4. 需要拍板的决策点

### Q1. core-common 是否本期就建？
core-common 本期实际只有 `UuidV7` + `ClusterProperties`（2 个类）。选项：
- **(a) 建 core-common**：即使只有 2 个类，为将来更多 job/共享预留结构。
- **(b) 暂不建**：core-job 内联这 2 个（UuidV7 复制一份、数据源配置 job 自己写 yaml 绑定），
  等共享面 > 阈值再抽 common。更 YAGNI。
- 用户已倾向「保留 core-common，只放必要的」→ 按 **(a)** 执行，但请确认接受「common 现在很小」。

### Q2. Flyway 迁移归属（重要，防冲突）
api（多实例）和 job（单实例）连**同一个库**。谁执行 migrate？
- **(推荐) 只 api migrate，job 不 migrate**：job 启动时库已就绪；job 端关闭 Flyway（或 `spring.flyway.enabled=false`）。
  但 job 用 Spring Batch，需要 Batch 元数据表（BATCH_JOB_*）—— 用 `spring.batch.jdbc.initialize-schema=always`（Batch 自建自己的表，与业务 Flyway 无关）。
- 或：job 也 migrate 但加 Flyway lock（Flyway 默认有表锁，理论安全，但两套 migrate 来源要一致）。
- **待确认**：采用「只 api migrate + job 关 Flyway + Batch 自建元数据表」？（推荐）

### Q3. Batch 元数据表放哪个库？
Spring Batch 需要 BATCH_JOB_INSTANCE 等元数据表记录 job 执行历史/断点。
- 放业务库（同一 PG，`initialize-schema=always` 自建）——简单，推荐。
- 或独立库——过度。
- **待确认**：放业务库？（推荐）

### Q4. 读写分离
job 单实例跑批，是否需要 reader/writer 路由？
- 清理是写操作为主，**直接连 writer 即可**，不需要 api 那套 ReadWriteRoutingDataSource。
- **待确认**：job 只连 writer？（推荐，简化）

---

## 5. 实施阶段（仅 common + job）

> 每阶段 `./gradlew :core-common:compileKotlin` / `:core-job:compileKotlin` 通过；job 建好后能 `bootRun` 启动。

### 阶段 A：建 core-common（若 Q1=a）
1. settings.gradle.kts include("core-common")；建 core-common/build.gradle.kts（纯 kotlin-jvm 库，无 spring-boot plugin、无 KSP）。
2. 放入 `UuidV7`（`com.ifmix.core.common.UuidV7`）、`ClusterProperties`（`com.ifmix.core.common.config`）。
3. 编译通过。
4. （core-api 改用 common 的这两个类——**归用户**，计划不动 core-api。）

### 阶段 B：建 core-job 骨架
1. settings.gradle.kts include("core-job")；core-job/build.gradle.kts（spring-boot + batch + jdbc + postgresql，非 web）。
2. `JobApplication`（`spring.main.web-application-type=none`）+ application.yml（数据源、batch initialize-schema、Flyway 关闭）。
3. 空启动验证：能连库、Batch 元数据表就绪、进程起来（无 job 触发也可）。

### 阶段 C：实现清理 Job
1. 复制 `AnonymousCleanupConfig` + `AnonymousCleanupDecision` 到 core-job。
2. 写 `AnonymousCleanupJob`（cursor reader + processor + chunk delete writer，裸 SQL）。
3. @Scheduled 触发 JobLauncher。
4. 用 ifmix_core_test 库跑一次，验证：正常用户不删、僵尸删、active 跳过、tombstone 超窗删。
5. 一个断言测试覆盖 `AnonymousCleanupDecision`（纯函数）。

### 阶段 D：从 core-api 移除清理代码（归用户或协作）
- core-api 删除 `AnonymousCleanupScheduler`/`Config`/`Decision`/`SchedulingConfig`（清理任务已移到 job）。
- **此步涉及 core-api，需与用户协调**：我可提供要删的文件清单，由用户执行；或用户授权我删。

---

## 6. 风险与说明

- **R1 双进程 migrate 冲突**：见 Q2，job 不做业务 Flyway migrate。
- **R2 单实例保证**：@Scheduled 在单实例进程内不会并发；若 job 进程被误部署多实例，Spring Batch 的
  JobRepository 会阻止同名 JobInstance 并发运行（同参数 job 不能重复跑）——双重保险。部署上确保 job 单副本。
- **R3 core-common 很小**：本期只 2 个类，接受（Q1）。
- **R4 清理 SQL 表名硬编码**：job 用裸 SQL，表名（customer/ai_scan_record/...）直接写在 SQL 里。
  需与迁移后的真实表名一致（已知：customer、ai_scan_record、ai_scan_collection、ai_scan_collection_item、
  media_upload_record、demo_todo、cms_feedback、pay_subscription、auth_appuser_refreshtoken）。
- **R5 删除顺序**：先删资源表再删 customer，同一 chunk 事务内，避免孤儿行。
