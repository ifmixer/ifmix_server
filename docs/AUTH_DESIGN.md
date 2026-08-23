# 认证架构（IDP 模型）

## 核心概念

```
Idp (全局)                          — 身份提供商配置（Apple/Google），一旦创建只改 name/desc
IdpIdentity (全局)                  — IDP 下的用户身份，按 (idpId, idpIdentityId) 唯一
AppToIdpRelation (app 级)           — App 启用了哪些 IDP
AppUser (app 级, user 模块)         — App 内的用户
AppUserToIdpIdentityRelation (app)  — AppUser 绑定了哪些 IDP 身份
AppUserRefreshToken (app)           — Refresh Token
```

## 登录流程

```
1. 客户端传 idpId + credential (id_token)
2. 验证 app 是否启用了该 IDP (auth_app_to_idp_relation)
3. 加载 IDP 配置 (auth_idp.config)，验证 credential → 得到 accountId
4. 找/建 IdpIdentity（全局，按 idpId + accountId 唯一）
5. 通过 auth_appuser_to_idpidentity_relation 查该 identity 在此 app 下绑了哪个 AppUser
   - 有 → 拿到 appUserId
   - 没有 → 创建 AppUser → 建 relation
6. 签发 refresh token + access token
```

## providerType 编码

| 编码 | Provider |
|------|----------|
| 10 | Apple |
| 20 | Google |

## AuthInterceptor

- **非阻塞设计**：无效 token 不拦截，只是不填充 userId
- 需要强认证的接口由 Handler 层判断 `mc.userId ?: throw ApiError(UNAUTHORIZED)`
- OPTIONS 请求自动跳过（CORS preflight）

## 跨模块调用

- `AuthAggHandler` 注入 `AppUserRepository`（创建用户）
- DataFetcher 查用户信息 → `AuthFacade.me()` 内部协调
- 跨模块字段用逻辑外键 `val appUserId: UUID`，不用 `@ManyToOne`

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
