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

| 文档 | 内容 |
|------|------|
| [架构总览](docs/guide/ARCHITECTURE.md) | 分层、模块职责、设计决策、API 约定 |
| [编码指南](docs/guide/CODING_GUIDE.md) | 每层怎么写、Context 模型、事务、示例代码 |
| [认证设计](docs/guide/AUTH_DESIGN.md) | IDP + AuthIdentity 模型、登录判定表、refresh token 有效期决策 |
| [数据库约定](docs/guide/DATABASE.md) | 表清单、命名规则、UUID、枚举、FilterGroup |
| [发布记录](docs/ops/release.md) | 发布版本号、线上版本、未发布变更与发布计划 |
| [Changelog](docs/ops/Changelog.md) | 面向客户端/数据库的变更时间线 |
| [部署](docs/ops/DEPLOY.md) | 部署方式、增量发布、systemd、env 管理 |
| [E2E 测试](docs/testing/E2E_TESTING.md) / [Trusted Documents](docs/testing/GRAPHQL_TRUSTED_DOCUMENTS.md) | 测试约定、persisted query 契约 |

### 设计文档（docs/design/，按模块分目录）

| 模块 | 文档（状态） | 内容 |
|------|------|------|
| install | [install-tracking](docs/design/install/install-tracking.md)（✅ 已实现） | install 表、install↔customer 关系、install token（type=5/iid）、绑定判定矩阵 |
| install | [install-customer-hardening](docs/design/install/install-customer-hardening.md)（✅ 已实现） | 强关系与客户端容错跨端修改清单（含错误码收敛） |
| attest | [install-attestation](docs/design/attest/install-attestation.md)（✅ 服务端已实现，剩真机 fixture / TestFlight 冒烟 / Apple 端点实测） | App Attest 一期 1a：challenge/attestation/assertion、限流、core-job 回填、灰度切 ENFORCE；Android (Play Integrity) 为 1b |
| ai | [api-key-pool](docs/design/ai/api-key-pool.md)（✅ 已实现） | key 池轮询/冷却/降级 + `core_ai_api_key` 表结构 + provider 通用化 |
| ai | [api-key-disable-and-probe-skip](docs/design/ai/api-key-disable-and-probe-skip.md)（✅ 已实现） | 401/403 永久禁用、pick 全冷却跳窗口（key 池演进） |
| ai | [deep-research-async](docs/design/ai/deep-research-async.md)（✅ 已实现） | DR 异步化 + PG premium_result 存储 + 历史版本 + latest 权威指针 |
| ai | [agnes-key-import](docs/design/ai/agnes-key-import.md)（✅ 已实现） | key 导入脚本（`scripts/agnes_keys/`）用法、配对/去重逻辑 |
| notification | [scan-async-notification-push](docs/design/notification/scan-async-notification-push.md)（✅ 已实现） | scan 异步化 + notification 模块基座（FCM 寻址、深链、配额预留） |
| notification | [deep-research-push](docs/design/notification/deep-research-push.md)（✅ 已实现） | DR 完成 push（对 scan push 的增量） |
| notification | [push-feature-flag](docs/design/notification/push-feature-flag.md)（✅ 已实现） | 前端 hard code flag + mutation 传参（非后端 kill switch） |
| infra | [wire-encryption](docs/design/infra/wire-encryption.md)（✅ 已实现） | wire 加密 v2（X25519+HKDF+AES-256-GCM）、降级模型、安全模型与演进决策 |
| infra | [idempotency](docs/design/infra/idempotency.md)（✅ 决策完成） | 创建接口幂等性评估：不建通用幂等，语义幂等 + 业务唯一键 |
| proposals | [graphql-to-http-rpc-openapi](docs/design/proposals/graphql-to-http-rpc-openapi.md)（📝 未实施） | GraphQL → HTTP RPC + OpenAPI 迁移计划（移动端上线前执行） |

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

