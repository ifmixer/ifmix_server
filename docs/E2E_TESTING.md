# E2E HTTP 自动化测试方案

## 方案选型

| 方案 | 优点 | 缺点 | 推荐度 |
|------|------|------|--------|
| **Spring Boot Test + Testcontainers** | 零外部依赖、IDE 直接运行、CI 友好 | 启动慢（但 virtual threads 缓解） | ★★★★★ |
| REST Assured + 外部进程 | 语法友好 | 需要手动管理进程生命周期 | ★★★ |
| Newman (Postman CLI) | 非技术团队也能写 | 需额外维护 Postman collection | ★★ |
| k6 / Artillery | 性能测试兼顾 | 不适合逻辑断言 | ★★ |

**推荐: Spring Boot `@SpringBootTest` + Testcontainers PostgreSQL + `WebTestClient`**

理由：
1. 与项目栈一致（Kotlin + JUnit 5），不引入额外语言
2. Testcontainers 自动管理 PostgreSQL + Redis 生命周期
3. 测试环境下 Flyway 执行全部 migration（V1–V5）；生产/本地则用 `./gradlew :core-api:flywayMigrate` 手动执行
4. `./gradlew :core-api:test` 一条命令覆盖全部 E2E
5. CI/CD 直接集成，发布前门禁

## 架构

```
┌───────────────────────────────────────────────────────────────┐
│                     E2E Test Suite                              │
│  @SpringBootTest(webEnvironment = RANDOM_PORT)                 │
│  + Testcontainers (PostgreSQL, Redis)                          │
├───────────────────────────────────────────────────────────────┤
│                                                                │
│  TestFixtures         →  Seed data (ProjectInfo, ProjectConfig, Idp, etc)  │
│  WebTestClient        →  HTTP calls (real HTTP, real server)   │
│  Assertions           →  Response code + body + DB state       │
│                                                                │
├───────────────────────────────────────────────────────────────┤
│  ┌─────────┐  ┌─────────┐  ┌─────────┐                       │
│  │  PG     │  │  Redis  │  │  App    │  (all in Docker)       │
│  │Container│  │Container│  │  :0     │                        │
│  └─────────┘  └─────────┘  └─────────┘                        │
└───────────────────────────────────────────────────────────────┘
```

## 测试覆盖计划

### Phase 1 — 核心流程 (必须)
- [ ] 认证: Google login → access token → refresh → logout
- [ ] 扫描: createScan → getScanResult → findByCursor
- [ ] 收藏: getDefault → addItem → listItems → removeItems
- [ ] 存储: presignUpload (格式校验) → presignDownload
- [ ] IAP: verifyIapPurchase (stub verifier)

### Phase 2 — 边界 & 安全
- [ ] 无 x-project-id 请求 → 400
- [ ] 无效 projectId 格式 → 400
- [ ] 过期 token → 匿名继续 (AuthInterceptor 非阻塞)
- [ ] 限流超限 → 429
- [ ] objectKey 路径遍历 → 400
- [ ] objectKey projectId 不匹配 → 400

### Phase 3 — Webhook 安全
- [ ] Apple webhook 无效签名 → 403
- [ ] Apple webhook 有效 payload → 200 + 订阅更新
- [ ] Google webhook 未知 packageName → 400

## 文件组织

```
core-api/src/test/kotlin/com/ifmix/core/api/
├── e2e/                          # E2E 测试包
│   ├── support/
│   │   ├── E2eTestBase.kt       # 基类：启动容器 + 配置
│   │   ├── TestFixtures.kt      # 种子数据工厂
│   │   └── TestAuthHelper.kt    # 生成测试 JWT
│   ├── AuthE2eTest.kt           # 认证流程
│   ├── AntiqueE2eTest.kt        # 扫描流程
│   ├── CollectionE2eTest.kt     # 收藏流程
│   ├── StorageE2eTest.kt        # 存储安全
│   ├── IapE2eTest.kt            # IAP 流程
│   └── SecurityE2eTest.kt       # 安全边界
```

## 运行方式

```bash
# 本地运行全部 E2E
./gradlew :core-api:test --tests "com.ifmix.core.api.e2e.*"

# CI 中作为发布门禁
./gradlew :core-api:test
# 全部通过才允许 deploy

# 只跑某个模块
./gradlew :core-api:test --tests "com.ifmix.core.api.e2e.AuthE2eTest"
```

## 实现步骤

1. 添加测试依赖 (Testcontainers Redis)
2. 创建 E2eTestBase 基类
3. 逐模块编写测试
