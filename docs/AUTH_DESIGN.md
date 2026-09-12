# 认证架构（IDP + AuthIdentity 模型）

## 核心概念

```
Idp (全局)                              — 身份提供商配置（Apple/Google），一旦创建只改 name/desc
IdpIdentity (全局, 跨 project)              — IDP 下的第三方身份，按 (idpId, providerSubjectId) 唯一
AuthIdentity (project 级)                   — 账号中枢：password + 账号权威资料（姓名/邮箱/手机/metadata）
AuthIdentityIdpRelation (project 级)        — AuthIdentity ↔ IdpIdentity 的 M:N 绑定（软删）
Customer (project 级)                       — Project 内 C 端用户：匿名/合并语义 + authIdentityId
RefreshToken (project 级, auth_refreshtoken) — Refresh Token（actorType + actorId，主体无关）
ProjectToIdpRelation (project 级)               — Project 启用了哪些 IDP
```

### 为什么是这套分层

- **`IdpIdentity` 全局、跨 project**：同一第三方 sub 在不同 project 复用同一行。
- **`AuthIdentity` project 级**：账号中枢，持密码与用户权威资料。与 `Customer` 是 **1:N**（customer 逻辑注销后可新建、复用同一 auth_identity）。
- **M:N 用关系表**：`IdpIdentity ↔ AuthIdentity` 是多对多，用关系表 `auth_identity_to_idpidentity_relation` 表达（双向普通 B-tree 索引点查，不用数组列/JSONB）。
- **`Customer` 精简**：只保留 project 级身份与合并语义（`anonymous / mergedTo / mergedToAt / authIdentityId`），资料归 `AuthIdentity`。

### AuthIdentity ↔ Actor（Customer / Manager）

一个 `AuthIdentity`（project 级账号）映射一到两类**主体（actor）**：

- **Customer**（C 端）：`customer.authIdentityId` → `auth_identity.id`。
- **Manager**（B 端，规划中）：同 project 下同一 `AuthIdentity` 也可被 manager 主体引用。

即：账号（`AuthIdentity`）是"人"，`Customer`/`Manager` 是这个人在同一 project 内的不同**角色身份**。`RefreshToken` 因此主体无关，用 `actorType`（10=customer / 20=manager）+ `actorId` 关联，而非直接绑 customer。

### IdpIdentity ↔ AuthIdentity 的双向多对多

关系是真正的 **M:N**，两个方向都成立，正是用关系表而非内嵌数组的原因：

- **一个 IdpIdentity → 多个 AuthIdentity（跨 project）**：同一个 Google 身份（`IdpIdentity`，全局唯一）被多个 project 各自的 `AuthIdentity` 绑定——每个 project 一条 relation。
- **一个 AuthIdentity → 多个 IdpIdentity（同 app 多 provider）**：同一 project 内一个账号可同时绑定 Google + Apple 等多个第三方身份——每个 provider 一条 relation。

关系表按 project 隔离（`project_id` 列）：反查方向 `(project_id, idp_identity_id)` 未删记录唯一（一个身份在一个 project 内只归属一个账号），正查方向 `(project_id, auth_identity_id)` 可多行（一个账号名下多个身份）。

### 数据归属

| 数据 | 归属 | 说明 |
|------|------|------|
| 第三方原始身份（provider 给的 email/phone/profile） | `IdpIdentity` | provider 来源，只读快照 |
| 账号权威资料（firstName/lastName/email/phone 分段/metadata） | `AuthIdentity` | 用户填写/合并后的权威值 |
| 密码哈希 | `AuthIdentity.password` | 社交登录为 null |
| project 级用户身份、匿名、合并 | `Customer` | 匿名先行、登录转正、跨设备合并 |

## 登录流程

```
1. 客户端传 idpId + credential (id_token)
2. 验证 project 是否启用了该 IDP (auth_project_to_idp_relation)
3. 加载 IDP 配置 (auth_idp.config)，验证 credential → 得到 providerSubjectId (accountId)
4. 找/建 IdpIdentity（全局，按 idpId + providerSubjectId 唯一）
5. 判定 existing：
   idpIdentity → relation(project 级, findByAppAndIdpIdentity) → auth_identity
              → customer WHERE authIdentityId = auth_identity.id 且未合并
6. 按判定表决定动作（见下），得到 ownerId
7. 更新 idpIdentity 登录信息（email/loginIp 等）
8. 为 ownerId 签发 refresh token + access token
9. 发布 AuthLoggedInEvent
```

### 登录判定表（R1：合并方向单一入口，绝不反向）

`decideLoginAction(cur, curAnonymous, existing)` 纯函数无副作用，四分支：

| # | 条件 | 动作 |
|---|------|------|
| ① PromoteOrCreate | `existing == null`（此 project 下该身份无账号） | `target = cur ?: 新建 customer`；建 `auth_identity` + 关系；`customer.authIdentityId = authId`；`cur != null` 时转正 |
| ② NoOp | `existing == cur` | 重复登录，无操作 |
| ③ Merge | `cur` 匿名且 `existing != cur` | 合并 `cur → existing`，吊销 cur 的 refresh token |
| ④ Conflict | `cur` 非匿名且 `existing != cur` | 报错（该账号已在其他设备使用） |

> `cur` = 当前 token 主体（匿名 customer 或 null）；`curAnonymous` = `mc.op.anonymous`。

## idpType 编码

`Idp.idpType` 与 `IdpIdentity.idpType` 共用编码：

| 编码 | Provider |
|------|----------|
| 10 | Apple |
| 20 | Google |

> `AuthAggHandler.providerKeyForType` 按此编码路由到对应 `ProviderVerifier`。

## AuthInterceptor

- **非阻塞设计**：无效 token 不拦截，只是不填充 `customerId`
- 需要强认证的接口由 Handler 层判断 `mc.op.customerId ?: throw ApiError(UNAUTHORIZED)`
- OPTIONS 请求自动跳过（CORS preflight）
- token claim：`sub=actorId, act=actorType(Int), aud=projectId, ano=anonymous`（见 `AuthJwtService`）

## 跨模块调用

- `AuthAggHandler` 注入 `CustomerRepository`（建/转正 customer、设 authIdentityId）、`AuthIdentityRepository`、`AuthIdentityIdpRelationRepository`、`CustomerMergeHandler`
- 跨模块字段用逻辑外键 UUID（`authIdentityId`、`idpIdentityId`…），不用 `@ManyToOne`
- `AuthFacade.me()` 内部经 `customer.authIdentityId → 关系表 → idpIdentity.email` 取主邮箱

## GraphQL API

```graphql
input IdpLoginInput {
    idpId: UUID!
    credential: String!
}

extend type Mutation {
    m_auth_login(input: IdpLoginInput!): LoginResult!
    m_auth_refreshToken(input: RefreshInput!): RefreshResult!
    m_auth_logout(input: LogoutInput!): OperationResult!
    m_auth_deleteAccount: OperationResult!
}

extend type Query {
    q_auth_me: MeResult!
}
```
