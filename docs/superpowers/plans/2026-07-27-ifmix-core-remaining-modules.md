# ifmix core-api 剩余模块 实现计划（索引）

> 承接 `2026-07-26-ifmix-core-foundation.md`（地基 + todo 切片已实现）。本篇是**索引**：把旧项目
> `ifmix_apps/server/core` 除 auth 外的其余模块移植到 Kotlin/Spring/MongoDB，每个模块/地基已拆成
> **独立详细计划**，供不同 agent 分别实现。
>
> 参考：`ifmix_apps/docs/superpowers/specs/`（`2026-07-26-postgres-to-mongodb-design.md §五` 逐模块
> 建模、`2026-07-22-soft-delete-config-versioning-design.md`、各模块设计）。对外 HTTP 契约沿用旧项目
> 形状（信封、URL、DTO）。

**目标：** 移植 appconfig、antique（扫描）、iap（内购）、collection（收藏）、feedback（反馈）；
antique 的真实 AI 单列专项计划；auth 暂缓。

---

## 子计划清单（按实现顺序）

| 顺序 | 计划文件 | 内容 | 依赖 |
|---|---|---|---|
| 0 | `2026-07-27-00-foundation.md` | 地基重构（`BaseDocument` + 能力接口 + 单一 `CRUDRepository`）+ 时间戳 `Instant`/全局序列化 | — |
| 1 | `2026-07-27-01-appconfig.md` | `app_info` + `app_config`（版本化配置） | 0 |
| 2 | `2026-07-27-02-antique.md` | 未登录扫描 + 限流 + R2 + `ScanRunner` 接缝（AI stub） | 0, 1 |
| 3 | `2026-07-27-03-iap.md` | 内购验证 + 订阅 + webhook + 档位联动 | 0, 1, 2 |
| 4 | `2026-07-27-04-collection.md` | 收藏（默认夹 + join + membership 端口） | 0, 2 |
| 5 | `2026-07-27-05-feedback.md` | 反馈（追加式提交） | 0 |
| 6 | `2026-07-27-06-ai.md` | 真实 AI：Spring AI `ChatClient` + Agnes 多 key 池（配额/冷却/fallback），替换 antique 的 `StubScanRunner` | 2 |
| — | auth（暂缓，单独立项） | EdDSA JWT + JWKS + SSO + refresh 轮换 | 全部之后 |

> **实现顺序：** 0（地基）→ 1（appconfig）→ 2（antique，AI 走 stub）→ 3（iap）→ 4（collection）→
> 5（feedback）→ 6（AI 专项，替换 stub）。2/3/4 之间有依赖（见上表），5 仅依赖地基可并行。

---

## 模块依赖层次（禁止环，单向向下）

```
common/  http、db(BaseDocument/能力接口/CRUDRepository/CRUDService)、tx、config、redis、ratelimit、storage   ← 基础设施
  ▲
modules/appconfig   app_info（身份）+ app_config（版本化配置）                                              ← 共享底座
  ▲          ▲          ▲
modules/antique   modules/iap    （antique 读 tier；iap 读商店凭证 + 注入 antique 的 TierResolver）
  ▲                    │
modules/collection ────┘        （collection 引用 antique 的 ScanRecordDocument + ScanDto + ScanMapper）
modules/feedback                （仅依赖 common）
```

- 功能模块**向下依赖** `common` 与 `appconfig`。
- `collection → antique`：唯一横向依赖，单向（编译期用其文档 + mapper）。
- `antique → collection` 与 `antique → iap`：仅经**接口注入**（`CollectionMembership`、`TierResolver`），
  无编译期 import，无环。容器接线。
- 可选后续加 ArchUnit 断言依赖方向。

---

## 已确认的全局决策（2026-07-27）

1. **地基能力接口：** `CRUDDocument` → `BaseDocument`（id + createdAt/updatedAt）；三个正交能力接口
   `AppScoped`/`SoftDeletable`/`Versioned`；便利基类 `BaseAppDocument`（= app 级 + 软删）。单一
   `CRUDRepository<T : BaseDocument>` 反射探测能力，自动注入 appId/软删；**保留** `extraCriteria`/
   `extraIdCriteria` 钩子供模块附加自定义过滤。`CRUDAppRepository`/`softDelete` 开关删除。
   `CRUDAppService` → `CRUDService`。详见 foundation 计划。
2. **版本号字段：** 统一 `revision`（`Versioned` 接口提供，版本化逻辑留各模块 repo）。
3. **时间戳：** 文档/BO/DTO 全 `Instant`（方案 C）；出站 JSON 用全局 Jackson 3 序列化器 →
   epoch 毫秒；OpenAPI 用 `replaceWithClass(Instant, Long)`。**不用** `spring.jackson.serialization.*`
   属性（Jackson 3 已改这些开关）。详见 foundation 计划任务 0。
4. **Redis：** `spring-boot-starter-data-redis`（Lettuce），连接自 `REDIS_URL` → `spring.data.redis.url`。
5. **对象存储 R2/S3：** `software.amazon.awssdk:s3` + `s3-request-presigner`，endpoint/bucket/密钥走 env。
6. **AI：** 用 **Spring AI `ChatClient`**（结构化输出 + 多模态），并**保留** Agnes 多 key 池（方案 B：
   key 选择/配额/冷却移植到 Kotlin + Redis，LLM 调用经 Spring AI 每请求用挑中的 key 现建 ChatClient）。
   详见 `2026-07-27-06-ai.md`（含退化成单 key 方案 A 的说明）。antique 先用 `StubScanRunner` 打通，
   AI 计划落地后 `@Primary` 覆盖。
7. **IAP：** Apple `app-store-server-library`（Java）+ Google `androidpublisher`；凭证从 `app_config`。
   凭证/dev build 就绪前用 stub verifier，真实实现延后（见 iap 计划任务 9）。
8. **事务：** config 版本化、iap upsert 用地基 `TxRunner`（副本集）。

---

## 共享约定（沿用地基经验，所有子计划遵循）

- **命名：** 持久化 `XxxDocument`（不用 Entity）、业务聚合 `XxxBO`/业务概念名、对外 `XxxDto`；版本号 `revision`。
- **module-first**（按功能分包），靠依赖层次 + 单向依赖管理跨模块引用。
- **HTTP：** `PUT` = query、`POST` = mutation；URL 含 `query`/`mutation` 段；信封 `{code,msg,data}` 由
  `EnvelopeResponseAdvice` 自动包装；错误由 `GlobalExceptionHandler` 统一。webhook 在 `/webhooks` 独立挂载
  （不经 header 校验，返回裸状态码）。
- **RequestContext** 显式传参（controller→service→repo），`readPreference` 默认 primaryPreferred，事务内强制主库。
- **DTO 映射：** Konvert（`@Konverter interface XxxMapper`，`Konverter.get()`）；运行时字段（presign URL、
  collected 等）在 service/controller 层 `.copy(...)` 补齐。
- **请求 DTO 必填字段：** 可空 + 默认 null + `@field:` 校验。
- **分片：** app 级集合分片键 `appId`；唯一索引必须以分片键为前缀（partial unique）。`auto-index-creation`
  建注解索引；`shardCollection` 属部署引导脚本（超出模块代码范围）。
- **环境约束：** 本机无 Docker/本地 Mongo/Redis。每步 `./gradlew :core-api:compileKotlin
  :core-api:compileTestKotlin` 编译验证；非 Mongo 单元测试（`@JsonTest`/Mockito/纯函数）用 `--tests` 过滤跑；
  集成测试用 Testcontainers（Mongo 副本集 + 隔离 Redis）**照写但只在 CI 跑**。
- **不自动 commit**，每模块结束交用户 review。

---

## 决策点（已定案 2026-07-27）

- **antique：** `r2SignUpload` URL 段用 **`storage`**（`/customer/core/mutation/storage/r2SignUpload`）。
- **iap：** `StoreNotificationDocument` **保留** `deletedAt`（继续用 `BaseAppDocument`）；多
  `PurchaseVerifier` bean 用 `@Qualifier`（apple/google）消歧义。
- **collection：** `collection_item.scanRecordId` 存 **`ObjectId`**（keyset 排序自然有序）。回填改动记于
  `2026-07-27-06-ai.md` 任务 0，随 AI 实现一起改。
- **feedback：** `category` 对外用**枚举名（大写）**，Jackson 3 默认，不加自定义命名。

> 先前模块（appconfig/antique/iap/collection/feedback）代码已实现完毕；上述决策中仅 collection 的
> ObjectId 需回填代码，已集中记录在 AI 计划任务 0，实现 AI 时一并修改。

---

## auth（暂缓，单独立项）

参考 `2026-07-20-auth-design.md`。EdDSA JWT + JWKS + Google/Apple SSO + 设备密钥交换 + refresh token
轮换（副本集事务）+ mergeOnLogin（回填 scan/subscription/collection/feedback 的 userId）。等前述模块
完成后单独出规格与计划。
