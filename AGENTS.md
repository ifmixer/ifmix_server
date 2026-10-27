# AGENTS.md

## 项目上下文

本项目是 **ifmix_server** — 面向移动端的后端 API 服务（古物扫描 + AI 图像识别 + 收藏管理 + 社交登录 + IAP）。

三个 Gradle 模块：
- `core-common` — 纯 Kotlin 库（无 Spring）：`UuidV7`、`ClusterProperties` 等共享类。
- `core-api` — 主 Spring Boot Web 服务（GraphQL + REST + Webhook），业务全部在此；不依赖另两者。
- `core-job` — Spring Boot 非 web（Spring Batch）：匿名 Customer 清理等批处理；依赖 `core-common`。

跨模块只通过同一 PostgreSQL 协作（逻辑外键 UUID），不互相编译依赖。

## 技术栈

- Kotlin 2.3.10 / JDK 25 (Virtual Threads)
- Spring Boot 4.1.0 / Jimmer 0.11.5 (KSP) / PostgreSQL / Redis
- GraphQL: Netflix DGS 12.x (DGS codegen 8.6.0)
- Jackson 3 (`tools.jackson`) / Spring AI 2.0 / EdDSA JWT
- Gradle 9.6.1

## 文档索引

docs/ 分层：`guide/`（指南与约定）、`design/`（功能设计，唯一真相源）、`ops/`（运维与发布）、`testing/`（测试）。**改实现前先读对应设计，改完必须回写**；历史版本看 git（原 `docs/superpowers/` 已于 2026-10-05 迁入 design/ 或归档删除，作废文件不保留，在取代它的文档里留说明）。

### 运维文档约定（写新文档前先看这里）

1. **通用先行**：ssh / 连 PG / 连 Redis / 日志位置 / 重启回滚 这类「每回都要用」的内容，
   一律写在 [生产接入手册](docs/ops/PRODUCTION_ACCESS.md)，**不在具体文档里重复**——具体文档用一句链接引用即可。
2. **排查记录进 `docs/ops/issues/`**，文件名 `YYYY-MM-DD-标题.md`（日期小写横杠，标题英文短横线，
   如 `2026-10-09-ai-api-key-invalid-disabling.md`）。一次排查一份文件，内容 = 现象 + 生产证据 + 结论 + 修复 + 该组件的排障要点
   （SQL / 日志怎么读 / 恢复手册 / 坑）。
3. **组件常驻知识也归 issues/**：按组件排障的手册不是 docs/design 的事（design 讲设计与取舍），
   放 issues/ 下那份记录里，下次同模块出问题就继续在那份文件里追加。
4. **禁止把凭据写进仓库**（含 issue 文档）。口令只在 `.env` 与服务器 env 文件里，命令用变量引用。
5. 新增/移动 ops 文档后**同步更新下面这张索引表**。
6. **`docs/ops/release.md` 已不存在**——发布计划在 `docs/version/v1.0.6/release plan v1.0.6.md`，
   未发布变更清单在 `docs/version/v1.0.7/todo.md`。看到历史文档里残留的 `ops/release.md` 引用，按这两份替换。
7. **改生产机器（系统配置 / 包管理 / systemd）也要留痕**：先备份再改，改完在对应 issue 记录里追加「处置」章节
   （写了什么、为什么这么选、代价是什么、怎么回退），并在 [生产接入手册](docs/ops/PRODUCTION_ACCESS.md) 里同步现状
   ——否则下次排障会以为配置还是初始值。
8. **生产已关闭全部自动更新**（2026-10-10，原因见 PG 重启那条 issue）。别再提议「让它自动打补丁」；
   需要更新时走维护窗口手工 `apt update && apt upgrade` + `needrestart -r a`。

| 文档 | 内容 |
|------|------|
| [架构总览](docs/guide/ARCHITECTURE.md) | 分层、模块职责、设计决策、API 约定 |
| [编码指南](docs/guide/CODING_GUIDE.md) | 每层怎么写、Context 模型、事务、示例代码 |
| [认证设计](docs/guide/AUTH_DESIGN.md) | IDP + AuthIdentity 模型、登录判定表、refresh token 有效期决策 |
| [数据库约定](docs/guide/DATABASE.md) | 表清单、命名规则、UUID、枚举、FilterGroup |
| [Changelog](docs/ops/Changelog.md) | 面向客户端/数据库的变更时间线 |
| **[生产接入手册](docs/ops/PRODUCTION_ACCESS.md)** | **通用入口：ssh 别名与隧道、连 PG/Redis、日志位置与 JSON 解析、服务重启/发布/回滚、目录地图、常见坑** |
| [部署](docs/ops/DEPLOY.md) | 服务器初始化、蓝绿增量发布、systemd、Nginx、env 与凭据管理 |
| [Ops issues](docs/ops/issues/) | 具体问题的排查记录（`YYYY-MM-DD-标题.md`），清单见下 |
| [E2E 测试](docs/testing/E2E_TESTING.md) / [Trusted Documents](docs/testing/GRAPHQL_TRUSTED_DOCUMENTS.md) | 测试约定、persisted query 契约 |

ops issues 现存记录：

| 文件 | 主题 |
|------|------|
| [2026-10-09-ai-api-key-invalid-disabling](docs/ops/issues/2026-10-09-ai-api-key-invalid-disabling.md) | AI API key 401 自动禁用：`enabled=true` 条件没漏，是 300s 列表缓存窗口（已修）；含 key 池 SQL / 日志配对 / 误杀恢复 / key 池相关坑 |
| [2026-10-10-pg-connection-closed-by-unattended-upgrade](docs/ops/issues/2026-10-10-pg-connection-closed-by-unattended-upgrade.md) | Hikari `Failed to validate connection … closed` WARN：根因是 **unattended-upgrades 升级 libxml2 → needrestart 自动重启 postgresql**（不是 maxLifetime）；已处置（自动更新全关 + needrestart `override_rc` 禁重启 PG/Redis），含证据链、代价与回退方式 |
| [2026-10-10-ai-image-fetch-timeout-retry](docs/ops/issues/2026-10-10-ai-image-fetch-timeout-retry.md) | agnes-3.0-flash 常报「图片加载失败」：provider 回源下载超时被它自己返回成 **400**，原逻辑把 400/422 一律当不可重试；已加有限换 key 重试 |
| [2026-10-10-redis-aof-persistence](docs/ops/issues/2026-10-10-redis-aof-persistence.md) | Redis 只有 RDB 快照（`appendonly=no`）→ 崩溃/`kill -9` 最多丢近 1h 写；已开 **AOF（everysec）+ 保留 RDB** 并重启验证。同为 **Redis 组件常驻手册**：持久化健康度判定、AOF 目录、`redis-cli -u` 必失败要用 `-a`、写入失败行为、遗留 `vm.overcommit_memory` 警告 |
| [2026-10-27-tcg-project-bootstrap](docs/ops/issues/2026-10-27-tcg-project-bootstrap.md) | **⏳ 未执行**：TCG 扫描器（`projectId=tcg`）的项目数据初始化施工单 —— `core_project_info` / `core_project_serverconfig.fcm_config` 写入 SQL、attest 配置、冒烟步骤、回滚与排障。项目短期搁置，恢复时照此执行 |
| [2026-10-27-toyscan-project-bootstrap](docs/ops/issues/2026-10-27-toyscan-project-bootstrap.md) | **⏳ 未执行**：玩具扫描器（`projectId=toyscan`，bundleId `com.ifmix.toy`）的项目数据初始化施工单 —— 两张表写入 SQL、冒烟、回滚与排障。与 tcg 施工单的差异见文首对照表 |
| [2026-10-27-coin-project-bootstrap](docs/ops/issues/2026-10-27-coin-project-bootstrap.md) | **✅ 本地库已执行 / 生产待执行**：硬币扫描器（`projectId=coin`，bundleId `com.ifmix.coin`）—— 两张表写入 SQL、`psql -c` 不支持变量替换的坑、回滚与排障（含「回落 antique prompt 是静默的」） |

### 设计文档（docs/design/，按模块分目录）

| 模块 | 文档（状态） | 内容 |
|------|------|------|
| install | [install-tracking](docs/design/install/install-tracking.md)（✅ 已实现） | install 表、install↔customer 关系、install token（type=5/iid）、绑定判定矩阵 |
| install | [install-customer-hardening](docs/design/install/install-customer-hardening.md)（✅ 已实现） | 强关系与客户端容错跨端修改清单（含错误码收敛） |
| attest | [install-attestation](docs/design/attest/install-attestation.md)（✅ 服务端已实现（v6：createInstall 按平台拆分），剩真机 fixture / TestFlight 冒烟 / Apple 端点实测；P1 五项核对未落地见 release.md） | App Attest 一期 1a：challenge/attestation/assertion、限流、core-job 回填、灰度切 ENFORCE；createInstall 拆 createIosInstall/createAndroidInstall（底层复用，x-client-platform 与 provider 入口强校验）；Android (Play Integrity) 为 1b |
| ai | [api-key-pool](docs/design/ai/api-key-pool.md)（✅ 已实现） | key 池轮询/冷却/降级 + `core_ai_api_key` 表结构 + provider 通用化 |
| ai | [api-key-disable-and-probe-skip](docs/design/ai/api-key-disable-and-probe-skip.md)（✅ 已实现） | 401/403 永久禁用、pick 全冷却跳窗口（key 池演进） |
| ai | [deep-research-async](docs/design/ai/deep-research-async.md)（✅ 已实现） | DR 异步化 + PG premium_result 存储 + 历史版本 + latest 权威指针 |
| ai | [agnes-key-import](docs/design/ai/agnes-key-import.md)（✅ 已实现） | key 导入脚本（`scripts/agnes_keys/`）用法、配对/去重逻辑 |
| ai | [tcg-prompt-per-project](docs/design/ai/tcg-prompt-per-project.md)（✅ 代码已实现，分支 `feature/tcgcard-prompt` 未合 main；项目数据入库见 ops issue） | 提示词按 projectId 查表选择（`app.ai.prompt.*`）+ 单次扫描内联 premium（复用 `core_ai_deepresearch`，不耗深研配额）+ 正反面告知模型；antique 行为逐字不变 |
| notification | [scan-async-notification-push](docs/design/notification/scan-async-notification-push.md)（✅ 已实现） | scan 异步化 + notification 模块基座（FCM 寻址、深链、配额预留） |
| notification | [deep-research-push](docs/design/notification/deep-research-push.md)（✅ 已实现） | DR 完成 push（对 scan push 的增量） |
| notification | [push-feature-flag](docs/design/notification/push-feature-flag.md)（✅ 已实现） | 前端 hard code flag + mutation 传参（非后端 kill switch） |
| infra | [wire-encryption](docs/design/infra/wire-encryption.md)（v2 已实现未发布；§10 v3 HPKE 提案已定稿，v1.0.6 前实施） | wire 加密：v2 → v3（RFC 9180 HPKE）、降级模型、安全模型与演进决策 |
| infra | [idempotency](docs/design/infra/idempotency.md)（✅ 决策完成） | 创建接口幂等性评估：不建通用幂等，语义幂等 + 业务唯一键 |

已归档删除（git 历史可查）：`superpowers/specs/2026-10-02-…r2-versioning`（作废，理由在 deep-research-async §1.1）、`…install-attestation-impl-plan`（任务全 done，遗留项在设计文档头部）、`superpowers/plans/2026-09-29-install-tracking`（结论已沉淀）、`design/api-key-table`（并入 api-key-pool「表结构」）。

## 代码约定速查

### 分层规则

| 层 | 包路径 | 注解 | 职责 |
|----|--------|------|------|
| DataFetcher | `bff/graphql/customer/` | `@DgsComponent` | GraphQL 路由、GlobalTxRunner 包事务 |
| Facade | `modules/*/XxxFacade.kt` | `@Service` | 构造 ModuleCtx + 转发 |
| Handler | `modules/*/handler/` | `@Component` | 纯业务逻辑，接收 ModuleCtx |
| Repository | `modules/*/repo/` | `@Repository` | 纯数据访问、CrudRepoTemplate 组合 |
| Entity | `entity/` | 无 | Jimmer interface entity |
| Infra | `infra/` | `@Component`/`@Configuration` | 横切关注点 |

### 禁止跨级

- DataFetcher 不能 import handler/repo
- Facade 不能 import repo
- Handler 不能 import facade（可注入其他模块 Facade）
- DataLoader/Resolver 通过 Facade 调用，不直接注入 repo

### 关键约定

- GraphQL input 全链路透传（不逐字段粘贴）
- `@Service`/`@Component` 直注册，不在 Config 里 `@Bean`
- CrudRepoTemplate 分两类：全局 / App 级
- 单条操作返回 Boolean，batch 返回 Int
- AuthInterceptor 非阻塞（无效 token 不拦截）
- DateTime 统一 ISO-8601 字符串
- 枚举全链路 Int 透传
- 跨模块用逻辑外键 UUID，不用 `@ManyToOne`

### Jimmer 注意事项

1. 普通查询优先使用 Jimmer Kotlin SQL DSL
2. PostgreSQL-specific expression：使用 `sql(...) + %e/%v`
3. 不要在 native expression 中硬编码表名/字段名
4. 如果 SQL 特性影响 FROM/JOIN 结构且 Jimmer 无法表达：使用 JdbcClient
5. 不要自行创建复杂 Jimmer extension DSL

## 常用命令

```bash
./gradlew :core-api:compileKotlin    # 编译 core-api（含 KSP）
./gradlew :core-job:compileKotlin    # 编译 core-job（Spring Batch）
./gradlew :core-api:test              # 测试
./gradlew :core-api:flywayMigrate     # 手动执行 DB 迁移（不再随启动 migrate）
./gradlew :core-api:flywayRepair      # 修正 flyway 历史 checksum（不改表结构）
./gradlew :core-api:bootRun           # 运行主服务 (需 PG + Redis)
./gradlew :core-job:bootRun           # 运行批处理任务 (需 PG)
```

## 工作方式

- **写计划 vs 直接做**: 改动明确、文件数少时优先直接做。不确定时问用户。

