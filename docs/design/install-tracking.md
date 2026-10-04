# 设计文档：Install 设备追踪 + Install↔Customer 关系 + Install Token

> 状态：强关系与客户端容错已实现并完成本地迁移/前后端联调；V7 最终 NOT NULL 锁定延期到 legacy 可信回填完成后
> 目标模块：`ifmix_server / core-api`
> 目标态收敛与跨端修改清单：`docs/design/install-customer-hardening.md`
> 现行前端契约：`antique/docs/install-tracking-frontend-api.md`；早期任务书差异见文末。

---

## 1. 目标

为 FCM 推送与运营分群保存设备（install）信息，并建立 install 与 customer 的绑定关系。相较最初任务书，本设计做了一个**核心安全升级**：

**install-id 从「客户端生成、可伪造、不鉴权」改为「服务端生成 + installToken 签发 + 后续只认 token 内的 iid」。**

---

## 2. Token 体系（核心变更）

### 2.1 新增 `type` claim

在现有 JWT（EdDSA/Ed25519）中新增一个 **`type`** claim，标识 **token 类型**，与 `act`（actorType）**正交**：

| type | 含义 |
|------|------|
| 5    | install token |
| 10   | customer token |
| 20   | manager token |

- **缺省兼容**：老 token 没有 `type` claim → 解析时**默认按 10（customer）** 处理，向后兼容不破坏现网 token。
- `act`（actorType）**保持原样**（10=customer / 20=manager），**不新增 act=5**。服务端**不引入 actor=install 概念**——install 不是 actor。

### 2.2 install 不是 actor，只是 `iid` claim

install 是请求的**横切上下文**（「从哪个设备来」），不是操作主体（actor）。因此：

- install 信息通过独立 claim **`iid`（installId）** 承载，不占用 `sub`/`act`。
- `Actor` 数据模型**保持现状**（actorId / actorType / anonymous / sessionId），**不加 installId 字段**。install 单独由 `RequestParser` 从 token 的 `iid` claim 解析。

### 2.3 两种 token 的 claim 对照

| claim | install token | customer token |
|-------|---------------|----------------|
| `type` | 5 | 10 |
| `sub`  | —（不设） | customerId |
| `aud`  | projectId | projectId |
| `act`  | —（不设 / 忽略） | 10 |
| `ano`  | —  | 现有语义（是否匿名） |
| `sid`  | —  | 现有语义（sessionId） |
| `iid`  | installId | installId（新签 customer token 必填） |

> install token **不设 `sub`**（install 不是 actor，无操作主体）。`RequestParser.parseActor(requireActorType=null)` 对已验签 type=5 token 返回 null actor，并缓存可信 iid/type；需要 Customer actor 的端点仍明确拒绝 install token。

### 2.4 传输方式

统一走 `Authorization: Bearer <token>`。客户端同时持久化 InstallCredentials 与 CustomerSession，但每个请求只发送一个 token：普通 Customer 业务发 customer token；createAnonymous/无 session login/refresh/updateInstall 发 installToken。无需独立 `x-install-token` header。

### 2.5 token 生命周期

- **installToken：永不过期，不刷新**。用于 createAnonymous、无 session login、refresh、updateInstall 等设备上下文操作，属于长期凭证，客户端必须用 SecureStore 保存。
- customer access/refresh token：**保持现状**（access 900s + refresh 机制不变）。

---

## 3. 数据表

V5 建立 Install/关系表；V6 已完成 Install 单主键和业务 install_id UUID 化。表名使用 `core_` 前缀。

### 3.1 `core_install` — 设备表

V5 初始包含内部 `id` 与业务 `install_id` 两列；V6 已收敛为单一主键：

```text
core_install.id = API installId = JWT iid
```

| 列 | 类型 | 说明 |
|----|------|------|
| id | uuid PK | 服务端 UuidV7，同时是 API installId/JWT iid |
| project_id | text | project 隔离，逻辑外键 → project_info.id |
| platform | integer null | 10=ANDROID / 20=IOS / 30=WEB |
| device_info | jsonb null | 设备信息自由结构 |
| app_version | text null | 来自 `x-app-version` |
| ota_version | text null | 来自 `x-ota-version` |
| locale | text null | 归一化 locale |
| country | text null | ISO 3166-1 alpha-2 |
| currency | text null | ISO 4217 |
| reg_ip | text null | createInstall 时写入，updateInstall 不更新 |
| firebase_install_id | text null | Firebase FID，客户端后补 |
| fcm_token | text null | FCM registration token，客户端后补/轮换更新 |
| created_at | timestamptz | |
| updated_at | timestamptz | |

- 不再有独立 `core_install.install_id` 列或 `(project_id, install_id)` 唯一索引。
- install 记录独立于 customer，可先于任何身份存在。
- createInstall 返回 `id` 并按同一值签 installToken。
- updateInstall 按 `(project_id, id)` 查询，iid 一律来自已验签 token。
- `platform/app_version/ota_version/locale/country/currency` 从 header 获取；`device_info/firebase_install_id/fcm_token` 从 GraphQL input 获取，仅覆盖非空值。
- `reg_ip` write-once，只记录首次注册来源。

### 3.2 `core_install_customer_relation` — 关系表

| 列 | 类型 | 说明 |
|----|------|------|
| id | uuid PK | UuidV7 |
| project_id | text | |
| install_id | uuid | 逻辑外键 → core_install.id（即 JWT iid） |
| customer_id | uuid | 逻辑外键 → core_customer.id |
| created_at | timestamptz | 首次绑定时间，不变 |
| updated_at | timestamptz | 最后写入时间（任意 save 都刷，无业务含义） |
| deleted_at | timestamptz null | `@LogicalDeleted`：null=当前绑定 / not null=已解绑 |

- **唯一约束：`UNIQUE(install_id, customer_id)`**（全局唯一，**不带** deleted_at 条件）——一对关系永远只有一行，反复 bind/unbind 复用同一行、翻转 deleted_at，行数不增长。
- 索引：`(project_id, install_id)`。
- 「一个 install 同时只能绑一个 customer」由**业务逻辑**保证（见 §5），不是 DB 约束。

> 与 `core_auth_identity_to_idpidentity_relation` 的差异：那张表用「部分唯一索引 `WHERE deleted_at IS NULL` + 每次插新行」；本表用「全局唯一 + 复用行翻转 deleted_at」。原因：本表要支持同一对反复 bind/unbind 而不产生噪音行。

---

## 4. 接口（GraphQL mutation，遵循 `m_<module>_<action>`）

新模块 `install`（`bff/graphql/customer/install/` + `modules/install/`）。

### 4.1 `m_install_createInstall`
- **无鉴权**（`requireActorType = null`）。
- 限流：**每 IP 60s 10 次**（复用 `createAnonymousCustomer` 同款 `rateLimiter.checkFixedWindow`）。
- 入参：`deviceInfo`（JSON，可空）。`platform / appVersion / otaVersion / locale / country / currency` **从 header 取**（`RequestParser` 已有 parseXxx），不放 GraphQL 入参。
- 逻辑：生成 installId（UuidV7）→ 写入 `core_install`（header 字段 + deviceInfo）→ 签发 installToken（type=5, iid=installId, 无 sub）。
- 返回：`{ installId, installToken }`。

### 4.2 `m_install_updateInstall`
- **需 token**：从 token 取出 `iid`（installToken 或 customerToken 皆可）。取不出 iid → UNAUTHORIZED。
- 入参：`firebaseInstallId`（可空）、`fcmToken`（可空）、`deviceInfo`（JSON，可空）。`platform / appVersion / otaVersion / locale / country / currency` 同样**从 header 取**。
- 逻辑：按 `(project_id, iid)` 更新，**仅更新非空字段**（header 缺失/入参为 null 的字段不覆盖已有值）。
- 返回：`{ success }`（或简单视图，对齐现有 mutation 风格）。

> - installId 一律取自 token 的 iid，**不从入参、不从 `x-install-id` header 取**。
> - 可变字段（platform/app_version/ota_version/locale/country/currency/device_info/fid/fcm_token）**create 与 update 都可写**，均仅更新非空值。

---

## 5. Install↔Customer 关系维护

挂在现有 auth 流程（`AuthAggHandler`）。installId 一律来自 **token 的 iid**。

### 5.1 绑定（bind）——create/login 时
输入：installId（来自 iid）+ customerId（本次流程最终的 ownerId）。

```
1. 若无 installId（iid 缺失）→ 跳过关系维护（见各流程的策略）。
2. 一 install 只绑一 customer：
   软删该 install 当前所有「其它 customer」的有效关系
   （project_id, install_id, deleted_at IS NULL, customer_id != 目标）→ deleted_at = now。
3. upsert 目标关系 (install_id, customer_id)：
   - 查含软删的行（必须 disable LogicalDeletedFilter，见 §7 坑）：
     - 存在且 deleted_at IS NULL → 幂等，不动。
     - 存在但已软删 → 复活：deleted_at = null。
     - 不存在 → 插新行。
```

### 5.2 解绑（unbind）——logout 时
```
从 customer token 取 iid：
  - iid 缺失（legacy customer token）→ 只撤销会话，跳过关系解绑（legacy token 从未建立 install 关系）。
  - 有 iid → 软删该关系 (install_id, customer_id, deleted_at IS NULL) → deleted_at = now。
```

### 5.3 各 auth 流程的绑定策略

| 流程 | installToken/iid | 行为 |
|------|------------------|------|
| createAnonymousCustomer | **必须是 type=5 installToken** | iid 写入 customer token，并在同一事务创建绑定；无 token/customer/manager token均 UNAUTHORIZED |
| login（转正 PromoteOrCreate） | customer token 或 installToken 的 iid（必需） | 绑定最终 customer；无 token/manager/无 iid 拒绝 |
| login（合并 Merge，cur→existing） | 同上 | install 有效绑定指向合并后的 **existing**（与 customer 合并方向一致，**绝不反向**） |
| logout | 从 customer token 的 iid | 有 iid 则解绑；legacy token 缺 iid 时只撤销会话 |
| deleteAccount（requestAccountDeletion） | 不需要 iid | 用户**请求删除的当下**，按 `customer_id` 软删该 customer 的**全部**有效关系（`customer_id = actorId, deleted_at IS NULL → deleted_at = now`）。一个 customer 可能被多个 install 绑过（换设备），全部解绑。不依赖 iid，不受 iid 缺失影响 |

> **兼容说明**：本轮选择强制方案，不实现按 appVersion/project 的 legacy 无 iid 灰度。logout 仍保留旧 token 缺 iid 时只撤销会话的兼容；新 createAnonymous/login/refresh 均执行强 token/iid 校验。refresh 续期并保留 iid/anonymous；若 refresh token 属于 customer，会对 (iid, customer) 执行幂等 bind（已绑定 NoOp、软删复活），见 `AuthAggHandler.refresh`。

> **两条删除链路别混淆**：
> - **用户主动删除账号**（`requestAccountDeletion`，API 侧）：本设计在此**软删**关系（保留行作审计），customer 尚在。
> - **Cleanup（core-job）**：本 feature 不修改 `AnonymousCleanupCleaner`，不新增 `RESOURCE_GUARD_TABLES`，也不恢复任何删除循环。Customer/Install 候选条件与 resource 处理规则后续另起设计评审。

---

## 6. 关键决策记录

| # | 决策 | 理由 |
|---|------|------|
| D1 | install-id 服务端生成 + installToken | 原「客户端生成可伪造」不安全；服务端签发后可信 |
| D2 | token 加正交 `type` claim（5/10/20），缺省 10 | 区分 token 用途；缺省兼容老 token |
| D3 | install **不是 actor**，只作 `iid` claim | customer token 需同时携带 customerId(actorId) + installId(iid)，actorId 存不下两个值 |
| D4 | installToken 永不过期 | 用于设备上下文 bootstrap/refresh/update，必须 SecureStore 保存；未来设备认证另行增强 |
| D5 | 关系表 `(install_id, customer_id)` 全局唯一 + 复用行翻 deleted_at | 反复 bind/unbind 不产生噪音行 |
| D6 | 不要 bound_at/unbound_at | createdAt=首绑时间；deleted_at=解绑时间（且 @LogicalDeleted 自动过滤）；updatedAt=最后写入（无业务义） |
| D7 | 一 install 只绑一 customer | 绑新的前先软删该 install 其它有效关系 |
| D8 | createAnonymousCustomer 必须有 type=5 installToken+iid | 保证所有新 Customer 创建时原子绑定 Install，所有新 token 含 iid |
| D9 | logout 缺 iid 时跳过关系解绑 | legacy token 从未建立 install 关系，只需撤销会话 |
| D9b | deleteAccount 当下按 customer_id 软删关系；core-job cleanup 延期 | 用户主动删除关系语义已定；后台 Customer/Install 清理和 resource 处理不在本 feature 范围 |
| D10 | 历史关系查询**放弃** | 需求低频，后续交数据分析做 |

---

## 7. 实现坑位（务必注意）

1. **复活软删行必须绕过 `@LogicalDeleted` 过滤**：upsert 关系时按 `(install_id, customer_id)` 找行，`@LogicalDeleted` 默认过滤掉软删行 → 直接查会漏掉已软删行 → 误判「不存在」→ 插新行 → **撞 `UNIQUE(install_id, customer_id)` 报错**。必须 `filters { disable(LogicalDeletedFilter::class) }`（Jimmer 0.11.x，API 名以编译为准）查含软删行。**本 feature 最易翻车处。**

2. **复活时能否直接写 `@LogicalDeleted` 字段**：把 deleted_at 置回 null 是「复活」操作，Jimmer 对 `@LogicalDeleted` 字段的直接赋值可能有特殊处理。需编译 + 单测实测确认路径（可能需 update-only save 或原生 update）。

3. **`RequestParser` 取 iid**：新增从已验签 token 取 `iid` claim 的方法（install token 与 customer token 都用 `iid`）。现有 header 版 `parseInstallId`（读 `x-install-id`）**保留给日志等只读用途**，但关系维护/updateInstall **只用 token 版**。

4. **`type` claim 校验**：verify 后按接口需要校验 token type（如 updateInstall 允许 5 或 10；customer 接口 requireActorType=CUSTOMER 时 install token 因 act 不匹配自然被拒）。

5. **迁移可重复执行安全**：用 `IF NOT EXISTS` 等，对齐现网迁移风格。

---

## 7.5 GraphQL Schema 契约（`resources/schema/customer/install.graphqls`）

> **前后端类型名唯一真相源**。与 `antique/docs/install-tracking-frontend-api.md` §1 **逐字对齐**——改任一处需同步另一处。`JSON` 标量沿用现网（见 `ai.graphqls` 的 `basicResult: JSON`）。

```graphql
# ==================== Install ====================

type CreateInstallResult {
    "服务端生成的 installId"
    installId: UUID!
    "install token（JWT，type=5）。永不过期，无需刷新。"
    installToken: String!
}

type UpdateInstallResult {
    success: Boolean!
}

input CreateInstallInput {
    "设备信息（自由结构 JSON：型号/OS 版本/厂商等），可空"
    deviceInfo: JSON
}

input UpdateInstallInput {
    firebaseInstallId: String
    fcmToken: String
    deviceInfo: JSON
}

# platform / appVersion / otaVersion / locale / country / currency 均从 header 取，不入参。
# installId 一律取自 token 的 iid，不入参、不采信 x-install-id header。

extend type Mutation {
    "无鉴权；每 IP 60s 限 10 次。生成 installId + 签发 installToken(type=5)。"
    m_install_createInstall(input: CreateInstallInput): CreateInstallResult!
    "需 token（installToken 或 customerToken，取 iid）；仅更新非空字段。"
    m_install_updateInstall(input: UpdateInstallInput!): UpdateInstallResult!
}
```

---

## 8. 落地清单（review 通过后执行）

### 新增文件
- `db/migration/V5__install_tracking.sql`（两张表 + 索引）
- `entity/install/Install.kt`（Jimmer 实体，`BaseProjectEntity`）
- `entity/install/InstallCustomerRelation.kt`（`BaseProjectEntity` + `SoftDeletableProps`）
- `modules/install/InstallFacade.kt`
- `modules/install/handler/InstallAggHandler.kt`（含纯函数判定 + 关系 upsert/unbind）
- `modules/install/repo/InstallRepository.kt`
- `modules/install/repo/InstallCustomerRelationRepository.kt`
- `bff/graphql/customer/install/InstallFetcher.kt`
- `resources/schema/customer/install.graphqls`
- 单测：`InstallRelationDecisionTest`（首绑/重复绑幂等/换绑软删旧的/logout 软删/re-login 复活/再 logout）

### 修改文件
- `infra/auth/AuthJwtService.kt`：新增 `type` claim 签发 + verify；新增 `signInstall(installId, projectId)`；`signAccess` 加 `iid` 参数（可空）；`VerifiedToken` 加 `tokenType` + `installId`。
- `infra/auth/RequestParser.kt`：新增从 token 取 iid 的方法；（可选）token type 校验档位。
- `modules/auth/handler/AuthAggHandler.kt`：createAnonymousCustomer / login(promote/merge) / logout / deleteAccount 各分支挂关系维护 + 写 iid。
- `modules/auth/AuthFacade.kt`、Fetcher：透传 installId/installToken 所需入参。
- 文档同步：`docs/AUTH_DESIGN.md`（token type + iid + install 关系）、`docs/DATABASE.md`（两张新表）。

### 验收
- `./gradlew :core-api:compileKotlin` 通过（含 KSP）。
- `./gradlew :core-api:test` 相关单测通过。
- 关系维护判定逻辑抽纯函数 + 单测覆盖 §8 所列全部场景。

---

## 9. 与原任务书（antique/docs/push-server-install-tracking.md）的差异

| 项 | 原任务书 | 本设计 |
|----|---------|--------|
| install-id 来源 | 客户端生成，可伪造，不鉴权 | **服务端生成 + installToken 签发** |
| 上报接口 | 单个 `upsertInstall`（无鉴权） | **拆为 `createInstall`（无鉴权）+ `updateInstall`（需 token）** |
| install 信任 | `x-install-id` header | **token 的 iid**（header 不再采信） |
| 关系表软删字段 | `bound_at` + `unbound_at` | **createdAt + deletedAt(@LogicalDeleted)**，去掉两个业务时间字段 |
| 关系表唯一性 | 部分唯一索引 + 插新行 | **全局唯一 + 复用行翻 deleted_at** |
| createAnonymousCustomer | 未要求 installToken | **必须 type=5 installToken+iid**，创建时原子绑定 |
| 历史关系查询 | 要求支持 | **放弃**（交数据分析） |

### 客户端连带影响（antique 侧，另起任务）
- 启动流程改为：先 `createInstall` → 存 `installId` + `installToken` → 之后所有无 customer token 的请求带 installToken。
- **不再自己生成 installId**。
- 拿到 FID/FCM token 后调 `updateInstall`（带 token）。
- `onTokenRefresh` 时再次 `updateInstall`。
