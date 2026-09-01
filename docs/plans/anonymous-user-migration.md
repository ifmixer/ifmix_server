# 计划：统一到 Customer + 匿名用户模型（方案 B）

> 状态：已评审，待实施
> 目标：废弃 install/appUser 双主体归属，统一到单一 `Customer` 主体；匿名先行（app 启动即建匿名 customer），
> 登录转正，跨设备同账号登录时合并。认证机制主体无关，为未来 `Manager`（后台）复用。
> 参考：Firebase Anonymous Auth / Supabase Anonymous Sign-In / Medusa v2 actor 模型。

---

## 0. 已敲定的决策（讨论结论）

- **方案 B**：单一归属主体 `Customer`；匿名先行、登录转正、跨设备合并。查询多合并少的画像下最优，且为行业主流。
- **install 删除**：物理删 `Install` 实体、`user_install` 表、`user_install_binding`、`RegisterInstall` API、
  `InstallHandler`、`InstallRepository`、`AppUserToInstallRelation` 及其 repo。归属不再有 installId。
  未来做 push 时再建瘦身 `push_token` 表（`customerId + token + platform + lastActiveAt`）。
- **命名**（不考虑兼容，直接改）：
  - 表 `user_appuser` → `customer`（去掉 `user_` 前缀）；未来 `manager`。
  - 实体 / GraphQL：`AppUser` → `Customer`；未来 `Manager`。
  - 模块 `modules/user` → `modules/customer`；`modules/auth` 定位为**主体无关的认证机制中心**；未来 `modules/manager`。
- **数据模型对齐 Medusa v2**（已核对官方文档）：
  - Medusa 的 `user` = 后台管理员（= 我们的 `manager`）；`customer` = C 端用户。二者**分开**，各由自己的模块管。
  - Auth Module **不存主体模型**，只管认证；主体（Customer/Manager）由各自模块管。auth 层只认 `actorType` + `actorId`。
  - Medusa 官方自定义 actor 的示例正是 `Manager`——命名一致。
  - **有意偏离**：Medusa 把主体 id 内联进 `auth_identity.app_metadata.{actor}_id`（无关联表）。
    我们**保留现有 relation 表**（`IdpIdentity` ↔ 主体 的关联表），只把它泛化为 `actorId/actorType`——
    关联表更规范化，天然支持一个主体绑多个第三方身份，不采用 Medusa 的 app_metadata 内联。
  - provider 与 MFA **跨 actor 共用**（Medusa 亦然）：IdP verifier、2FA 放共用 `auth`，不按主体复制。
- **多态 vs 单态 id 命名**（Medusa v2 actor 模型）：
  - **多态表**（认证机制表，customer/manager 共用）：`actorId` + `actorType`（`"customer"`/`"manager"`）。
  - **单态字段**（业务资源归属，只属于 customer）：`customerId`。
  - JWT claim：`sub`=actorId、`act`=actorType、`ano`=是否匿名。（不用 `typ`，避免与 JOSE header `typ` 混。）
- **模块职责边界**（按职责切，不按主体切，避免认证写两遍）：
  | 能力 | 模块 | 主体 |
  |---|---|---|
  | IdP verifier / IdpIdentity / 绑定 relation | `auth` | 共用（manager 也第三方登录） |
  | 2FA（TOTP/恢复码/challenge） | `auth` | 共用（双方都要）— **本期结构预留，不实现** |
  | JWT 签发/验签、refresh 轮换、限流 | `auth` | 共用 |
  | 匿名创建 + 转正 + 合并 | `customer` | 仅 customer |
  | RBAC/角色 | `manager` | 仅 manager — **本期不建** |
  | 业务资源归属 | 各业务模块 | `customerId` |
- **清理**：refreshToken TTL 30d → **90d**；匿名 customer 若**无有效 refreshToken** 即**直接物理删**（连同资源）。
  **不做两段式软删**——匿名 customer 无登录凭证，token 过期后客户端无法再 attach，"复活"无意义（用户确认）。
  已转正（`anonymous=false`）的 customer 永不按此清理。
- **Subscription 补归属**：现无任何归属列，独立缺陷，本期补 `customerId`（存量留空）。
- **合并方向**：匿名并入已存在；双方都非匿名冲突则报错（用户确认）。
- **第三方/ push**：identify/alias，不刷数据；push 发送用 FCM，通知按用户则用 topic `customer_<id>`（本计划不含 push 实现）。

---

## 1. 现状事实（已核对代码）

- `CustomerOwnedProps { installId: UUID(必填), userId: UUID? }`；`ownsRow()` 分叉（登录看 userId，匿名看 installId）。
  资源表 `ai_scan_record` / `ai_scan_collection` / `media_upload_record` / `demo_todo` 带 `install_id`+`user_id`。
- `pay_subscription` 无归属列，仅 `subscription_pxid` + `app_id`。
- JWT：user token `sub=userId, iid=installId, type="u"`；install token `type="i"`。无 `ano`。
- login（`AuthAggHandler.login`）：服务端验 IdP → IdpIdentity → relation 找/建 AppUser；已发 `AuthLoggedInEvent`，
  `MergeOnLoginListener` 有 `// TODO 归并`。当前"无 relation 就新建 user"分支需改。
- refresh token（`AppUserRefreshToken`）按 `loginInstallId` 关联，30d TTL。
- 限流（`RateLimiter`）为 **UTC 日固定窗口**，无分钟窗口——需新增短窗口方法。
- 改名波及 `AppUser` 符号 16 文件；`Install` 相关 46 文件。迁移最新 `V35`，下一个 `V36`。

---

## 2. 分阶段实施计划

> 每阶段结束 `./gradlew :core-api:compileKotlin` 通过；行为阶段补 1 个最小测试。按阶段提交，便于回滚。

### 阶段 1：Schema 地基（迁移 + 实体，零行为变化）
1. 迁移 `V36__customer_anonymous_model.sql`：
   - `user_appuser` 增列：`anonymous BOOLEAN NOT NULL DEFAULT true`、`merged_to UUID`。
   - 存量修正（避免误判已登录用户为匿名）：
     `UPDATE {customer表} u SET anonymous=false WHERE EXISTS (SELECT 1 FROM auth_appuser_to_idpidentity_relation r WHERE r.app_user_id=u.id AND r.deleted_at IS NULL);`
   - `pay_subscription` 增 `customer_id UUID`（可空），索引 `(app_id, customer_id)`。存量留空（R3）。
   - 资源表归属列 `user_id` → `customer_id`。

> **待确认 · 改名时机**：表名/列名不考虑兼容、直接改。有两种排法：
> - **(推荐) 前移合并**：阶段 1 直接把表建成 `customer`、列建成 `customer_id`、实体一开始就叫 `Customer`，
>   取消阶段 5 的改名。优点：无中间态、无 `AppUser` 残留；缺点：阶段 1 从"纯加字段"变成"改名+加字段"，diff 变大。
> - **保持分离**：阶段 1 只加字段（表仍 `user_appuser`），阶段 5 统一 rename 到 `customer`。优点：阶段 1 小步、零风险；
>   缺点：中间阶段仍是 `AppUser`/`user_appuser`，多一次迁移。
> 下面阶段 2–4 的表名按「前移合并」写作 `customer`/`customer_id`；若选「保持分离」，这些名在阶段 5 前仍是旧名。
2. 实体：`AppUser` 增 `anonymous: Boolean`、`mergedTo: UUID?`；`Subscription` 增 `customerId: UUID?`。
3. 验证：编译通过；本地 PG 跑 Flyway 迁移无错。

### 阶段 2：删除 install
1. 删实体/表/API/handler/repo/relation（见决策清单）。迁移 `V37__drop_install.sql`：
   drop `user_install`、`user_install_binding`（`auth_appuser_to_install_relation`）。
2. `CustomerOwnedProps`：移除 `installId`，只留 `customerId`；`ownsRow()` 只认 customerId。
   资源表 `install_id` 列在 `V37` 中 drop（NOT NULL 先解除）。
3. 清理引用：`AuthLoggedInEvent.installId`、refresh token 的 `loginInstallId`、JWT `iid` claim、
   `RequestContext.installId`、`OperationContext.installId`、`AuthInterceptor` 解析 iid、`RequestLoggingFilter` 等。
   （install token / `type="i"` 路径整体移除。）
4. 验证：编译通过；受影响 e2e（Collection/Scan）跑通。

### 阶段 3：匿名 Customer 创建 API + JWT 增强（auth 机制）
1. `AuthJwtService`：`signAccess` 参数化为 `(actorId, actorType, appId)`，claim 加 `act` + `ano`；
   `VerifiedToken` 加 `actorType`、`anonymous`。移除 install token 相关。
   `AuthInterceptor`/`RequestContext`/`OperationContext` 透传 `customerId`(=actorId when act==customer)、`anonymous`、`actorType`。
2. refresh token 表泛化：`app_user_id` → `actor_id` + `actor_type`（迁移 `V38`）；`AppUserRefreshToken` 实体 +
   repo 改 `actorId/actorType`（本期只写 `"customer"`）。
3. 新 API `m_customer_createAnonymous`（GraphQL mutation，无需鉴权）：
   建 `Customer{anonymous=true}` → 签发 accessToken + refreshToken → 返回
   `{ accessToken, refreshToken, refreshExpiresAt, expiresIn }`。token：`sub=customerId, act="customer", ano=true`。
4. 限流：`RateLimiter` 新增 `checkFixedWindow(subject, limit, windowSec)`（Redis INCR + 60s TTL）；
   该 API 挂 **每 IP 60s 10 次**，超限 `RATE_LIMITED`。
5. refreshToken TTL 改 **90d**。
6. 测试：连调 11 次触发限流；解析 token 断言 `act="customer"`、`ano=true`、`sub` 可解析 UUID。

### 阶段 4：登录转正 + 账号合并（customer 专有）
1. `login` 改造（`modules/customer` 侧调 `auth` 机制）：入参带当前匿名 customer（token `sub`）。
   验 IdP → idpIdentity → 查绑定 relation：
   | cur（当前 token） | existing（该 idpIdentity 已绑定） | 动作 |
   |---|---|---|
   | — | 不存在 | cur 转正：绑定 relation + `anonymous=false`，**零迁移** |
   | — | == cur | 无操作 |
   | cur 匿名 | 存在且 ≠ cur | **合并 cur → existing** |
   | cur 非匿名 | 存在且 ≠ cur | **报错**：该账号已在其他设备使用，请用原账号登录 |
   （移除旧"无 relation 就新建 user"分支。）
2. 合并（`GlobalTxRunner` 单事务，实现 `MergeOnLoginListener` TODO）：
   - 改写 cur 名下资源 `customerId → existing`：`ai_scan_record`/`ai_scan_collection`/`media_upload_record`/`demo_todo`/`pay_subscription`。
   - cur 置 `mergedTo=existing`。
   - 吊销 cur 的 refresh token，发 existing 的。
   - 去重：`ai_scan_collection.is_default` 每 customer 唯一——保留 existing 的，cur 的降级 `isDefault=false`。
   - login 返回 existing 的 token。
   - restore purchases：`PaymentAggHandler.verifyAndUpsert` 命中已存在订阅时，若 `customerId` 不一致按同规则刷新。
3. `idpIdentity` 绑定 relation 表泛化为 `actorId/actorType`（为 manager 第三方登录预留；本期只写 customer）。
4. 测试：cur(匿名,1 scan) 用 G1 登录、existing 已绑 G1 → 断言 scan.customerId==existing、cur.mergedTo==existing、
   响应 token.sub==existing。

### 阶段 5：auth 侧 actor 实体收尾改名（放最后，纯改名）
> 主体改名（AppUser→Customer、表→customer、模块→customer、资源列→customer_id）已在**阶段 1 前移完成**。
> 本阶段只收尾 auth 侧仍留 `AppUser` 命名的 actor 实体（它们已在阶段 3 泛化为 actor 语义，仅类名待改）：
- `AppUserRefreshToken` → `RefreshToken`；`AppUserToIdpIdentityRelation` → `IdpIdentityBinding`（或按 actor 语义命名）。
- `AuthLoggedInEvent.appUserId`、`signAccess` 相关参数名等残留 `appUser*` 命名统一到 `customer*`/`actor*`。
- 表名如需同步（`auth_appuser_refreshtoken` → `auth_refreshtoken` 等）加一条 rename 迁移；否则仅改 Kotlin 符号。
- 用 `rename`/重构逐符号做，`detect_changes` 复核。纯改名、零行为变化，编译通过即可。

### 阶段 6：匿名 Customer 清理定时任务
1. 每天一次，两类都清（分批 `LIMIT` 循环，幂等可重入，**直接物理删含其资源**）：
   - **未合并僵尸**：`anonymous=true AND merged_to IS NULL AND 无有效 refresh token`
     （有效 = `revoked_at IS NULL AND expires_at > now()`）。
   - **已合并 tombstone**：`merged_to IS NOT NULL AND 合并时间超过审计窗口（如 7d）`。
     合并事务已把其资源/refresh token 全部迁走，tombstone 是空壳，可安全删。
     （溯源信息若需保留，写 `merge_log`{from,to,at} 而非在 customer 表留空壳。）
2. 删前扫 `active subscription`：命中则跳过 + 告警（避免误删"匿名却付费"边界数据）。
3. 背景：**登出→操作→重登** 循环会反复产生匿名 customer 并在重登时合并成 tombstone；
   tombstone 清理（本任务第 1 条第二项）是回收这类垃圾的关键，否则会泄漏。
4. 测试：造匿名 customer + 已过期 refresh token → 跑任务 → 断言被删；造一个 `merged_to` 超窗 tombstone → 断言被删。

---

## 3. 客户端契约变更（同步前端）
- app 启动先调 `m_customer_createAnonymous` 拿 token（替代原 install 注册）。
- **登出语义**（对齐 Firebase/Supabase/RevenueCat "logout → fresh anonymous"）：
  - 登出 = 客户端清 access+refresh token + 服务端吊销 refresh token（`logout` API 已 revoke）。
  - **不在登出瞬间自动建匿名 customer**；回到"无身份"态。
  - **惰性创建**：下次真正需要身份（启动 / 首次调受保护接口 / 首次产生数据）时才调 `m_customer_createAnonymous`。
    避免"登出后直接重登、中间无操作"凭空产生匿名 customer。
  - 登出 ≠ 注销：已登录用户登出后 customer(anonymous=false) 与数据原样保留，再登录 email 完整找回；
    注销走 `deleteAccount`。
  - 匿名态不应提供"退出登录"入口；若提供"清除数据"须明确警告匿名数据不可找回。
- 不得把 `sub`(customerId) 当**永恒**本地锚点：跨设备登录合并时 `sub` 会切到 existing，以 login 响应为准。
- 匿名数据尽力保留、不承诺永久；重要数据引导登录后产生。

---

## 4. 风险与缓解
- **R1 合并方向写反**：方向硬编码「匿名 cur → existing」，判定表是唯一入口。
- **R2 并发**：同 email 两设备同时登录 → idpIdentity/existing 行锁或唯一约束串行化。
  > **阶段4 实测（2026-09）**：`auth_appuser_to_idpidentity_relation` 现有的是**普通 INDEX**
  > `auth_appuser_idpidentity_app_identity_idx (app_id, idp_identity_id)`，**并非 UNIQUE**（见 `V28`）。
  > 因此当前无数据库级串行化：两设备同 email 并发首登可能各建一条 relation（重复绑定）。
  > 合并/转正逻辑本身在 `login` 的单事务内（`GlobalTxRunner`），方向硬编码不受影响；
  > 但防重需靠唯一约束。按约定**本期不加索引**，遗留待阶段5/6：
  > 将该 INDEX 升级为 `CREATE UNIQUE INDEX ... ON (app_id, idp_identity_id) WHERE deleted_at IS NULL`（部分唯一索引，兼容软删）。
- **R3 存量 subscription 无归属**：`customer_id` 留空，仅新数据绑定。可接受。
- **R4 存量 anonymous 初值**：阶段 1 用 relation 存在性回填 `anonymous=false`，避免误删活跃已登录用户。
- **R5 改名/删 install 波及面大**：删 install（阶段 2）与改名（阶段 5）各自独立提交；改名阶段纯符号+列 rename。
- **R6 IAP 改 customerId 不影响 refund/cancel**：webhook 按 `subscription_pxid` 定位，不碰归属（已核实）。

---

## 5. 未来扩展（本期仅预留结构，不实现）
- **Manager（后台）**：`modules/manager` + `user_manager` 表 + RBAC；认证复用 `modules/auth`（IdP、2FA、token、refresh）。
- **2FA**：`auth` 内新增 TOTP/恢复码/challenge；2FA 配置表用 `actorId/actorType`（双方共用）。
- **Push**：瘦身 `push_token` 表（`customerId + token + platform`）或 FCM topic `customer_<id>`。

---

## 6. 实施顺序
阶段 1（地基）→ 2（删 install）→ 3（匿名创建 + auth 机制泛化）→ 4（转正+合并）→ 6（清理）→ 5（改名，最后）。
