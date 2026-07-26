# Auth 社交登录设计（Kotlin/Spring/MongoDB 移植）

- 日期：2026-07-27
- 状态：认证部分已定案（本文档）；**RBAC 授权部分暂缓，单独设计**（见文末占位）。
- 落点：`ifmix_server` core-api，新增 `modules/auth` + `common/auth`（现有 `modules/auth` 仅规划中的
  `r2SignUpload` 已挪至 `storage`，见剩余模块计划）。
- 参考：旧项目 `ifmix_apps/docs/superpowers/specs/2026-07-20-auth-design.md`（本文档忠实移植其认证
  设计，并做 Kotlin/Spring/MongoDB 适配）。

## 0. 概述与范围

OAuth 式认证：客户端用原生 Google/Apple SDK 登录拿 provider id token，后端用
**`spring-security-oauth2-jose` 的 `NimbusJwtDecoder`** 验 provider id_token（JWKS 缓存 + iss/aud/exp
校验），落身份，签发**无状态 app 级 EdDSA access JWT**（15min，携带 `appUserId`；用 Nimbus 签发/暴露
JWKS）+ 长效不透明
refresh（30 天，DB 存 sha256、可轮换/撤销）。鉴权无状态：每请求验签 access JWT 得 `appUserId`，不查
DB/Redis。身份与资料上移到**租户级**（`auth_identity`），兄弟 app 共享，减少重复注册。跨 app 免重登靠
设备级 `device_secret`（keychain 共享）经 `/auth/exchange` 引导。公开 **JWKS 端点**供其它服务自行验签。

**命名规范：`auth_` = 认证/租户级（跨兄弟 app 共享），`app_` = app 级。**

**范围（认证，v1 全量移植旧设计）：**
- `POST /customer/core/mutation/auth/google|apple`（登录：签发 access+refresh+device_secret）。
- `POST /customer/core/mutation/auth/exchange`（兄弟 app 用 device_secret 换本 app 的 access+refresh）。
- `POST /customer/core/mutation/auth/refresh`（轮换）、`.../auth/logout`（登出本设备）。
- `PUT  /customer/core/query/auth/me`（需登录）。
- `populateAuth`/`requireAuth` 授权前的**认证**装配；登录发 `auth.logged_in` 事件 → `mergeOnLogin`
  归并匿名 `scan_record`/`subscription`。
- 公开 JWKS 端点 `/.well-known/jwks.json`（无 header 校验）。
- `auth_tenant` 集合（新）；`app_config` 的 auth 相关字段**已就位**（见 §8）。

**不包含（后续）：** RBAC 授权（单独设计）；绑定/解绑多 provider（schema 预留，逻辑不做）；邮箱密码/
找回/验证邮件（`passwordHash` 预留）；跨 provider 按 email 自动合并；账号注销；MFA；access token 即时
吊销的 Redis jti 黑名单（TTL 兜底）。

## 1. 身份分层与租户

```
app_config (per app)              # appId → authTenantId + 本 app 各平台 login client id（已就位）
auth_tenant (per tenant/org)      # 租户实体（name/slug）：身份联邦单位（同 Google 项目/Apple App ID group）
auth_provider_identity (租户级) ─N:1─▶ auth_identity (租户级"人"+资料) ─1:N─▶ app_user (app 级)
业务表 subscription/scan_record.userId ───────────────────────────────────────▶ app_user.id
auth_device_secret (租户级, per 设备) ─▶ (authTenantId, authIdentityId)   # 跨 app 引导钥匙
app_refresh_token (DB, app 级) ─▶ (appId, appUserId, deviceSecretId)
access JWT { sub: appUserId, aid: appId, iat, exp }   # 验签即鉴权（app 级；identity 经 app_user 反查）
```

- access/refresh 是 **app 级**（携带/键为 `appUserId`/`appId`）；鉴权 = 验签 + 校验 `aid == x-app-id`。
- `device_secret` 是**设备+租户级**，兄弟 app 经 keychain 共享，仅用于 `/auth/exchange`。
- 业务 `userId` = `app_user.id`；`ctx.userId = appUserId`；identity 需要时经 `app_user` 反查。
- app 级隔离场景 → 给该 app 单独一个 `auth_tenant`。

## 2. 数据模型（集合 / 能力 / 索引）

> 分片：`auth_*`（租户级）按 `authTenantId` 分片；`app_*`（app 级）按 `appId` 分片。租户级集合**不套用**
> 通用 `AppScoped`（那是 appId 分片），与 `AppConfigRepo` 一样用**自定义 repo**（直接 `MongoTemplate`，
> 查询键带 `authTenantId`）。主键 ObjectId（对外 hex）；时间 `Instant`；唯一索引以分片键为前缀。

**租户/认证级（`auth_`，自定义 repo）：**

- `AuthTenantDocument`（集合 `auth_tenant`，`_id` = tenantId，`BaseDocument`，不软删/不分片——是注册表）：
  `name`、`slug`（唯一稀疏）、`desc?` + 时间戳。租户 = 一组共享同一 Google 项目 + Apple App ID group 的
  兄弟 app。**不存 client id**（下放 `app_config`）。
- `AuthIdentityDocument`（集合 `auth_identity`，租户级"人"+资料）：`authTenantId`、`rawEmail?`、
  `email?`（规范化）、`rawPhone?`、`phone?`（规范化）、`contactEmail?`、`displayName?`、`passwordHash?`
  （预留）、`profile?`(嵌套/Map)、`metadata?`(Map) + 时间戳。索引 `(authTenantId)`、`(authTenantId, email)`、
  `(authTenantId, phone)`（v1 不加唯一、不自动合并）。
- `AuthProviderIdentityDocument`（集合 `auth_provider_identity`，provider 维度）：`authTenantId`、
  `authIdentityId`、`provider`（`google`|`apple`）、`providerAccountId`（oauth sub）、`email?`、
  `emailVerified`(Boolean)、`phone?`、`userMetadata?`(Map)、`providerMetadata?`(Map)、`loginIp?`、
  `loginInstallId?`、`loginAppId?` + 时间戳。唯一 `(authTenantId, provider, providerAccountId)`；索引 `(authIdentityId)`。
- `AuthDeviceSecretDocument`（集合 `auth_device_secret`，设备级引导钥匙）：`authTenantId`、`authIdentityId`、
  `secretHash`、`loginInstallId?`、`expiresAt`（空闲过期，每次使用续期）、`revokedAt?`、
  `lastUsedAt?` + 时间戳。唯一 `(authTenantId, secretHash)`（唯一索引以分片键 `authTenantId` 为前缀）；
  索引 `(authTenantId, authIdentityId)`。**稳定、不轮换**（兄弟 app 共享多读者）；有效性 =
  `revokedAt == null && expiresAt > now`。查找恒带 `authTenantId`（`exchange`/`login` 已知本 app 的
  tenant，故定向单 shard，不 scatter-gather）。

**app 级（`app_`，`BaseAppDocument` + 通用 `CRUDRepository`）：**

- `AppUserDocument`（集合 `app_user`，`_id` = 业务 userId）：`appId`(来自 AppScoped)、`authIdentityId`、
  `metadata?`(Map) + 时间戳。唯一 `(appId, authIdentityId)`。
- `AppRefreshTokenDocument`（集合 `app_refresh_token`）：`appId`、`appUserId`、`deviceSecretId`、
  `tokenHash`、`loginInstallId?`、`expiresAt`、`revokedAt?`、`replacedBy?` + 时间戳。唯一 `(appId, tokenHash)`
  （唯一索引以分片键 `appId` 为前缀）；索引 `(appId, appUserId)`、`(deviceSecretId)`。`deviceSecretId` 使
  登出可"按设备清掉该设备所有 app 的 refresh"。refresh/logout 请求带 `x-app-id`，故按 `(appId, tokenHash)`
  查、分片定向。

**app_config（已就位，无需改）：** `authTenantId`、`apple.servicesId`（Apple web Services ID；native
复用 `appleBundleId`）、`google.clientIds.{ios,android,web}`（Google 各平台 login client id，验 `aud` 用）
均已在 `AppConfigDocument`。`app_info` 已有 `name`/`slug`/`desc`。

**规范化：** email → 全小写；local 去 `+` 及其后；gmail/googlemail 去 `.` 且域名归一 gmail.com。
phone → E.164。`raw*` 存原值。纯函数 `EmailNormalize.normalizeEmail/normalizePhone`。

**无 session 表、无 Redis session：** 鉴权靠无状态 JWT。

> 绑定/解绑（v1 不实现，schema 就位）：`auth_identity` 1:N `auth_provider_identity`。v1 每次 provider
> 登录 = 各自 provider_identity + 各自新建 auth_identity（1:1），不按 email 自动合并；将来加 `/auth/bind`。

## 3. Token 与 device_secret

**access（app 级，无状态，Nimbus EdDSA/Ed25519）**
- header 带 `kid`，claims `{ sub: appUserId, aid: appId, iat, exp }`，`ACCESS_TTL_SEC=900`。
- **不含 tier、不含 identity**（tier 现查见 §8；identity 经 `app_user` 反查）。鉴权 = 验签 + `exp` +
  校验 `aid == x-app-id`（跨 app/租户盗用直接拒）。
- 算法 **EdDSA（Ed25519）非对称**：私钥只在 core-api 签发，公钥经 JWKS 端点（`/.well-known/jwks.json`
  + `kid` 轮换）分发给其它服务自行验签。客户端不验签。

**refresh（app 级，不透明）**
- `SecureRandom` 32 字节 base64url；DB 存 `sha256`；`REFRESH_TTL_SEC=2592000`（30 天）；app 级
  （键 `appId`+`appUserId`）；旋转 + 撤销 + 重放检测（轮换必须原子，见 §4）。响应带 `refreshExpiresAt`。

**device_secret（设备+租户级，不透明，稳定不轮换）**
- `SecureRandom` 32 字节 base64url；DB 存 `sha256`；空闲过期（如 90d，每次使用续 `expiresAt`）；可撤销。
- 登录时若客户端带有效 device_secret（同 identity）→ 复用并续期；否则 mint 新的。兄弟 app 经 keychain
  共享，用于 `/auth/exchange`。**不 per-use 轮换**（多读者共享，轮换会互相踩）。

## 4. 流程

```
POST …/auth/google|apple   body: { idToken, deviceSecret? }   (headers: x-app-id, x-install-id?)
  1. cfg = appConfigRepo.getByAppId(x-app-id)；缺/无 authTenantId → APP_CONFIG_MISSING；tenantId = cfg.authTenantId
  2. { accountId, email?, emailVerified, phone?, userMetadata } =
       providerVerifier.verify(cfg, provider, x-client-platform, idToken)   // 用本 app client id 验 aud；失败 → AUTH_PROVIDER_FAILED
  --- 步骤 3-6 在 TxRunner.withTx(ctx) 内原子执行（任一失败整体回滚）---
  3. { authIdentityId } = authProviderIdentityRepo.upsert(tenantId, provider, accountId, {…,loginIp,loginInstallId,loginAppId})
       // 新账号：建 auth_identity（写规范化 email/phone/profile）+ provider_identity；已存在：更新并复用
  4. appUserId = appUserRepo.ensure(x-app-id, authIdentityId)
  5. deviceSecretId = body.deviceSecret 有效(同 identity) ? 复用并续期 : deviceSecretRepo.issue(tenantId, authIdentityId, {loginInstallId})
  6. refresh = appRefreshTokenRepo.issue(appId, appUserId, deviceSecretId, {loginInstallId});  access = signAccess(appUserId, appId)
  7. 返回 { accessToken, refreshToken, refreshExpiresAt, deviceSecret, expiresIn, user:{ id: appUserId, email } }
  8. 事件 emit auth.logged_in { appId, appUserId, installId }   // mergeOnLogin，见 §7

POST …/auth/exchange   body: { deviceSecret }   (headers: x-app-id)   // 兄弟 app 免重登
  1. tenantId = appConfigRepo.getByAppId(x-app-id).authTenantId；缺 → APP_CONFIG_MISSING
  2. ds = deviceSecretRepo.findValidByHash(tenantId, sha256(deviceSecret))；无效/过期/撤销 → UNAUTHORIZED
       // 按 (authTenantId, secretHash) 定向查；跨租户的 secret 天然查不到
  3. 校验 ds.authTenantId == tenantId（跨租户 → UNAUTHORIZED；双重保险）
  4. appUserId = appUserRepo.ensure(x-app-id, ds.authIdentityId)
  5. deviceSecretRepo.touch(ds.id)（续 expiresAt）；refresh = issue(appId, appUserId, ds.id, {});  access = signAccess(appUserId, appId)
  6. 返回 { accessToken, refreshToken, refreshExpiresAt, expiresIn, user:{ id: appUserId } }   // 不重发 provider，不返回新 device_secret

POST …/auth/refresh { refreshToken }   (x-app-id)
  h=sha256 → 原子轮换：updateFirst(where tokenHash=h AND appId=x-app-id AND revokedAt=null,
      set revokedAt=now, replacedBy=newId)；modifiedCount==0 → 竞态失败方 / 重放
  命中已撤销（重放）→ 撤销该 appUser 全部未撤销 refresh → UNAUTHORIZED
  否则：发新 refresh（同 appId/appUserId/deviceSecretId）+ 新 access → 返回

POST …/auth/logout { refreshToken }   (x-app-id)   // 登出本设备（跨兄弟 app）
  h=sha256 → 查 refresh → 取 deviceSecretId → 撤销该 device_secret + 撤销 deviceSecretId 匹配的全部未撤销 app_refresh_token
  → 本设备所有 app 的 refresh 失效；已发 access 自然过期(≤ACCESS_TTL)；其它设备不受影响。返回 { ok: true }

PUT …/query/auth/me   (requireAuth) → { id: appUserId, email }
```

轮换/登录写用 `TxRunner.withTx`（副本集事务）；原子轮换用条件 `updateFirst`（`modifiedCount` 判竞态）。

## 5. Provider 验证（spring-security-oauth2-jose `NimbusJwtDecoder`）

- 依赖：`spring-security-oauth2-jose`（提供 `NimbusJwtDecoder`，Spring 维护、内含 Nimbus）。**不使用**
  Spring Social（已 EOL、面向 web 重定向授权码流程，与原生 SDK id_token 验证不符）；**不使用** OAuth2
  Login / Authorization Server（流程不符 / 过度设计）。
- `ProviderVerifier.verify(appConfig, provider, platform, idToken) → { accountId, email?, emailVerified,
  phone?, userMetadata? }`（`accountId` = provider `sub`；同一人跨兄弟 app 一致 → 归同一 `auth_identity`）。
- Google/Apple 各一实现，各建一个 `NimbusJwtDecoder`：
  - `NimbusJwtDecoder.withJwkSetUri(...)`：Google `https://www.googleapis.com/oauth2/v3/certs`；
    Apple `https://appleid.apple.com/auth/keys`（decoder 内建 JWKS 缓存/刷新）。
  - 校验器：`JwtValidators` + `JwtIssuerValidator`（Google `https://accounts.google.com`、Apple
    `https://appleid.apple.com`）+ **自定义 `aud` 校验**——按 `x-client-platform` 取本 app 的 Google
    client id（`cfg.google.clientIds.{ios/android/web}`）或 Apple `cfg.appleBundleId`(native) /
    `cfg.apple.servicesId`(web) + `exp`（默认含）。验签失败 → `AUTH_PROVIDER_FAILED`。
  - `emailVerified` 取 `email_verified` claim。
- **`NimbusJwtDecoder` 必须容器级单例/缓存**（复用实例才缓存 JWKS，否则每次登录重拉）。因 `aud` 依赖
  per-app + per-platform 的 client id，`aud` 校验放在 decoder 之外（decoder 只做验签 + iss + exp，
  取出 claims 后再按本 app 配置校验 `aud`），使 decoder 可按 (provider) 单例复用、不随 app 变化。
  decoder 可注入以测（本地 JWKS）。

## 6. 中间件与 JWKS 端点

- **`populateAuth`（非阻塞、无状态，认证装配）**：有 `Authorization: Bearer <access>` → 验签 + `exp` →
  校验 `aid == x-app-id` → 命中则把 `appUserId` 放入请求（由 `RequestContextArgumentResolver` 读入
  `ctx.userId`）；miss/无效/aid 不匹配 → 匿名（不报错、不查 DB/Redis）。挂 `/customer/**` 业务路由。
  > 落地方式：扩展现有 `RequestContextArgumentResolver`/拦截器体系——在解析 `RequestContext` 前，从
  > `Authorization` 头验签得 `appUserId` 填入 `ctx.userId`（现 `RequestContext.userId` 已预留）。
- **`requireAuth`（阻塞）**：无 `ctx.userId` → `UNAUTHORIZED(401)`。给 `/auth/me` 等。
- **JWKS 端点** `GET /.well-known/jwks.json`：返回当前公钥集（含 `kid`）。挂在**无 header 校验**的公开
  路径（类似 webhook，`WebConfig` 拦截器 excludePathPatterns 已排除 `/.well-known` 需补充）。
- **JWT 密钥：** `AUTH_JWT_PRIVATE_KEY`（Ed25519 私钥）走 env，仅 core-api 持有；`kid` 支持轮换（新旧
  公钥并存于 JWKS，验签按 `kid` 选）。

## 7. mergeOnLogin（Spring 应用事件）

- 旧设计的 `common/events` 类型化事件总线 → 适配为 **Spring `ApplicationEventPublisher` + `@EventListener`
  （`@Async`，虚拟线程）**：登录成功 publish `AuthLoggedInEvent(appId, appUserId, installId?)`。
- 监听器 `mergeOnLogin`（best-effort）：若有 `installId`，`TxRunner.withTx` 内把 `subscription`/
  `scan_record` 中 `(appId, installId, userId == null)` 回填 `userId = appUserId`。**完成匿名购买/扫描
  归属用户**。
- 错误隔离（不拖垮登录），但回填失败须**结构化 error 日志 + 失败计数**；§8 的 install_id 兜底为漏回填
  提供自愈。

## 8. 与现有模块的集成点

- **appconfig：** `authTenantId` + 各平台 login client id（`google.clientIds`、`apple.servicesId`、
  `appleBundleId`）**已在 `AppConfigDocument`**，无需改 schema；仅需实际配置数据。
- **RequestContext：** `userId` 字段**已预留**；`populateAuth` 填充它。
- **iap 档位（D1 双查）：** `IapService`/`SubscriptionRepo.findActiveBySubject(appId, userId, installId)`
  已按 userId OR installId 双查（见 iap 计划），与旧设计 D1 一致——auth 落地后 `resolveTier` 登录态天然
  按 userId 命中，无需改 iap。
- **限流主体（D5）：** 旧设计 auth 落地后把 `resolveSubject` 从 `userId ?? installId ?? ip` 收紧为
  **`userId ?? ip`**（installId 客户端可控、重置即绕过）。**这是 auth 落地时对 `common/ratelimit`
  `resolveSubject` 的一处修改**（antique 计划里现为三段式，auth 计划实现时改两段式）。
- **fail-safe：** entitlement 查询失败判 free 时记 error 日志（可用性优先）。

## 9. 错误处理

复用 `UNAUTHORIZED(401/401000)`、`APP_CONFIG_MISSING(400002)`。新增 `AUTH_PROVIDER_FAILED(401/401001)`。
refresh 无效/过期/撤销、device_secret 无效/过期/撤销/租户不匹配 → `UNAUTHORIZED`。

## 10. 测试

- **单元（无 Mongo）：** provider verifier（注入本地 JWKS：aud ∈ 集合 / iss / exp / 坏签）、`jwt` 签验
  （过期、错密钥、`aid` 不匹配）、`appRefreshToken` 原子轮换 + 重放（mock repo）、`deviceSecret`
  （issue/findValid/过期/撤销）、`populateAuth`/`requireAuth`（命中/miss 匿名/无效/aid 不匹配）、
  `EmailNormalize`、事件监听（spy）、mergeOnLogin（mock）、JWKS 端点产出正确公钥集 + `kid` 轮换。
- **集成（Testcontainers Mongo 副本集，CI 跑）：** login → 建 identity/provider_identity/app_user/
  refresh/device_secret；同 provider 再登录复用 auth_identity；exchange → 新 app 建 app_user + 令牌
  （不跑 provider）；mergeOnLogin 回填 subscription/scan_record；refresh 轮换（旧失效、新可用）；logout
  撤该设备全部 app refresh + device_secret。
- **关键回归：** ① 共享 device_secret 双 exchange 都成功；② 并发刷新原子轮换只一方成功；③ 登录后
  tier 生效（匿名购买 → 登录归并 → 按 userId 判 pro）；④ 跨租户隔离（tenant2 持 tenant1 的 access →
  `aid` 不匹配拒；tenant2 用 tenant1 device_secret exchange → 拒）。

## 11. Kotlin/Mongo 适配汇总（相对旧 TS 设计）

- JS `jose` → provider id_token 验证用 **`spring-security-oauth2-jose` 的 `NimbusJwtDecoder`**（JWKS
  缓存 + iss/exp 校验，`aud` 在 decoder 外按本 app 配置校验）；**签发/验签自家 Ed25519 JWT + 构造 JWKS**
  用 Nimbus。**不用** Spring Social（EOL）、OAuth2 Login / Authorization Server（流程不符/过度设计）；
  我们自己的 access token 鉴权保持手写 `populateAuth`（非阻塞 + `aid` 校验），不套 resource-server。
- Postgres 表 → Mongo 集合：`auth_*` 租户级自定义 repo（`authTenantId` 分片）、`app_*` 用 `BaseAppDocument`
  + 通用 `CRUDRepository`（`appId` 分片）。主键 ObjectId、时间 `Instant`、唯一索引带分片键前缀。
- 自定义事件总线 → Spring `ApplicationEventPublisher` + `@Async @EventListener`（虚拟线程）。
- `withTx` → `TxRunner.withTx`（副本集事务）。
- 中间件 → 现有拦截器 + `RequestContextArgumentResolver` 扩展（认证装配 `ctx.userId`）。
- 无 Redis session（与旧设计一致）。

## 12. 实现计划拆分（→ writing-plans）

本认证设计规模较大，建议 writing-plans 时拆为有序小计划（每个独立可测）：
1. 数据模型 + 自定义 repo（auth_tenant/identity/provider_identity/device_secret；app_user/refresh_token）。
2. JWT/JWKS（Nimbus Ed25519 签验 + JWKS 端点）+ EmailNormalize。
3. Provider 验证（Google/Apple，`spring-security-oauth2-jose` `NimbusJwtDecoder` + 自定义 aud 校验）。
4. 登录/exchange/refresh/logout/me 流程 + service（TxRunner 原子）。
5. populateAuth/requireAuth 中间件 + RequestContext 集成。
6. mergeOnLogin 事件 + 集成点（resolveSubject 收紧 D5、fail-safe 日志）。

---

## 附：RBAC 授权（暂缓，单独设计）

管理端多租户 RBAC 已讨论出方向但**未定稿**，留作后续单独设计文档。已确认要点（供回头继续）：
- 作用对象：仅管理端（`/app`、`/platform`）；consumer 无角色。
- 主体：复用 consumer 身份（`auth_identity`/`app_user`），同一人可被授予管理角色。
- scope：**平台 + org（`auth_tenant`）**两级，建模为通用 `(scopeType, scopeId)`，v1 只放 `PLATFORM`/`ORG`，
  预留 `APP`。`/app` 请求经 `x-app-id` → `appConfig.authTenantId` 解析 org 再判权。
- 粒度：角色 → 权限映射，预定义角色目录（代码），端点按 permission 判定。
- 认证复用本设计；管理端仅加**独立授权中间件**（不改认证）。
- 授予管理：平台管理员经 `/platform` grant/revoke，seed 引导首个 `platform_admin`。
- **待定：** 角色/权限目录细节、`role_assignment` 结构、授权中间件与端点判定的落地形式、授予接口契约。
