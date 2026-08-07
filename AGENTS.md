# AGENTS.md

## 项目上下文

本项目是 **ifmix_server** — 一个面向移动端的后端 API 服务（古物扫描 + AI 图像识别 + 收藏管理 + 社交登录 + IAP）。

**在开始任何工作之前，请先阅读架构文档:** [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)

## 技术栈速查

- Kotlin 2.3.10 / JDK 25 (Virtual Threads)
- Spring Boot 4.1.0 / Jimmer 0.11.5 / PostgreSQL / Redis
- Jackson 3 (`tools.jackson`) / Spring AI 2.0 / EdDSA JWT
- Gradle 9.6.1 + KSP

## 代码约定

### 分层规则

| 层 | 包路径 | 注解 | 职责 |
|----|--------|------|------|
| Controller | `bff/` | `@RestController` | 路由、参数绑定、DTO 转换 |
| Service | `service/` | `@Service` | 业务编排、事务边界 |
| Repository | `repository/` | `@Repository` | 数据访问、SQL 查询 |
| Infra | `infra/` | `@Component`/`@Configuration` | 横切关注点、外部集成 |
| Entity | `entity/` | Jimmer `@Entity` | 数据模型定义 |

### DI 风格
- **组合优于继承**: Service/Repository 使用构造器注入，不用 `@Autowired`
- **Config 类仅创建基础设施 bean**: JwtDecoder、Stub 实现、条件 bean
- **Service 是 `@Service`，不是 Config 里的 `@Bean`**

### API 风格
- 查询端点: `PUT /customer/query/core/{module}/{method}`
- 修改端点: `POST /customer/mutation/core/{module}/{method}`
- 所有响应包装为 `Envelope<T>` (`{code, msg, data}`)
- 请求头必带 `x-app-id` (UUID)

### 认证
- `AuthInterceptor` 是**非阻塞**的（无效 token 不拦截，只是不填充 userId）
- 需要强认证的接口由 Service 层自行判断 `ctx.userId ?: throw ApiError(UNAUTHORIZED)`
- 这是有意设计，不要改成阻塞式

### 数据库
- 使用 Jimmer ORM，实体定义在 `entity/` 下
- **所有表名带 `core_` 前缀**（如 `core_todo`, `core_app_user`, `core_scan_record`）
- UUIDv7 作为主键（时间有序，支持游标分页）
- **UUID 字符串统一用 22 位 Base58 URL-safe 编码**（不用原始 36 位格式）
  - PG/Jimmer 层：原生 UUID 类型
  - REST API / Redis JSON / 前端交互：22 位 Base58
  - Jackson 全局模块自动转换（`JacksonConfig.uuidBase58Module`）
  - 工具类：`infra/codec/Base58.kt`（`uuid.toBase58()` / `str.toUuidFromBase58()`）
- 游标分页: `WHERE id < cursor ORDER BY id DESC LIMIT n+1`
- 读写分离: `@Transactional(readOnly=true)` 自动路由到 reader
- **枚举字段用 SMALLINT 存数字编码**（不用 VARCHAR、不用 PG ENUM）
  - Kotlin 用 `enum class Xxx(val code: Int)`，手动指定编码
  - 0 保留不用；同组连续（100,110,120）；不同组间隔 100
  - Jimmer 用 `ValueConverter<Enum, Int>` 做双向转换
  - 对外 API 输出字符串名（`"COMPLETED"`），不暴露数字
  - 详见 `docs/ARCHITECTURE.md` 的「枚举设计规范」

### 存储上传
- objectKey 格式: `app_{appId}/i_{installId}/...` 或 `app_{appId}/u_{userId}/...`
- presignUpload 不要求登录
- presignDownload 暂不做权限验证

### 限流
- Redis 日固定窗口，超限后**不删除 key**（防止重置绕过）
- 配额支持 refund（下游失败归还）

## 常用命令

```bash
# 编译
./gradlew :core-api:compileKotlin

# 测试
./gradlew :core-api:test

# 运行 (需要 PostgreSQL + Redis)
./gradlew :core-api:bootRun
```

## 参考文档

- [架构全貌](docs/ARCHITECTURE.md) — **必读**
- [Jimmer 迁移设计](docs/superpowers/specs/2026-07-29-jimmer-pg-migration-design.md)
- [认证设计](docs/superpowers/specs/2026-07-27-auth-social-login-design.md)
- [基础设计](docs/superpowers/specs/2026-07-26-ifmix-core-foundation-design.md)
- [迁移状态](migration_status.md)

## 重要设计决策

以下是已确定的设计决策，除非有充分理由否则不要推翻：

1. **API 用 PUT 做查询、POST 做修改** — 已确定的 BFF 风格
2. **AuthInterceptor 非阻塞** — 支持匿名+认证混合接口
3. **presignUpload 不要求登录** — 后续通过行为验证增强
4. **Service 用 @Service + 构造器注入** — 不在 Config 里手动 new
5. **Repository 用 @Repository** — 启用 Spring 异常翻译
6. **AI ScanRunner 每个模型遍历所有 key** — 不是只试一个就跳下一个模型
7. **限流超限不删 Redis key** — 让 key 自然 TTL 过期
8. **objectKey 强格式校验** — `app_{appId}/(i_|u_)/...`，防路径遍历
9. **Webhook 必须验签** — Apple JWS / Google 通过 packageName 反查 appId
