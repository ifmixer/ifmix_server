# AGENTS.md

## 项目上下文

本项目是 **ifmix_server** — 一个面向移动端的后端 API 服务（古物扫描 + AI 图像识别 + 收藏管理 + 社交登录 + IAP）。

**在开始任何工作之前，请先阅读架构文档:** [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md)

## 技术栈速查

- Kotlin 2.3.10 / JDK 25 (Virtual Threads)
- Spring Boot 4.1.0 / jOOQ 3.21.5 / PostgreSQL / Redis
- GraphQL: Netflix DGS 12.x (DGS codegen 8.6.0)
- Jackson 3 (`tools.jackson`) / Spring AI 2.0 / EdDSA JWT
- Gradle 9.6.1

## 代码约定

### 分层规则

| 层 | 包路径 | 注解 | 职责 |
|----|--------|------|------|
| DataFetcher | `bff/graphql/customer/` | `@DgsComponent` | GraphQL 路由、构造 OperationContext、DataLoader |
| Service | `modules/*/service/` | `@Service` | 业务编排、TxRunner 事务、CrudServiceOps 缓存 |
| Repository | `modules/*/repo/` | `@Repository` | 纯数据访问、注入 CrudOps、接收 RepoContext |
| Model | `entity/` | 无 | Domain data class、可加业务方法 |
| Infra | `infra/` | `@Component`/`@Configuration` | 横切关注点、外部集成 |

### DI 风格
- **组合优于继承**: Service/Repository 使用构造器注入，不用 `@Autowired`
- **Config 类仅创建基础设施 bean**: JwtDecoder、Stub 实现、条件 bean
- **Service 是 `@Service`，不是 Config 里的 `@Bean`**

### API 风格
- **GraphQL**: `POST /customer/graphql` (主 API)
- **Operation 命名**: `${query|mutation}_${module}_${action}`
- **Webhook (REST)**: `POST /webhooks/iap/*`
- 请求头必带 `x-app-id` (UUID)

### 认证
- `AuthInterceptor` 是**非阻塞**的（无效 token 不拦截，只是不填充 userId）
- 需要强认证的接口由 Service 层自行判断 `ctx.userId ?: throw ApiError(UNAUTHORIZED)`
- 这是有意设计，不要改成阻塞式

### 数据库
- 使用 jOOQ（类型安全 SQL DSL + codegen）
- **所有表名带 `core_` 前缀**（如 `core_todo`, `core_app_user`, `core_scan_record`）
- UUIDv7 作为主键（时间有序，支持游标分页）
- **UUID 字符串统一用 22 位 Base58 URL-safe 编码**（不用原始 36 位格式）
  - PG/jOOQ 层：原生 UUID 类型
  - REST API / Redis JSON / 前端交互：22 位 Base58
  - Jackson 全局模块自动转换（`JacksonConfig.uuidBase58Module`）
  - 工具类：`infra/codec/Base58.kt`（`uuid.toBase58()` / `str.toUuidFromBase58()`）
- 游标分页: `WHERE id < cursor ORDER BY id DESC LIMIT n+1`
- 读写分离: mutation → 主库, query → 从库
- **枚举字段用 SMALLINT 存数字编码**（不用 VARCHAR、不用 PG ENUM）
  - Kotlin 用 `enum class Xxx(val code: Int)`，手动指定编码
  - 0 保留不用；同组连续（100,110,120）；不同组间隔 100
  - 对外 API 输出字符串名（`"COMPLETED"`），不暴露数字

### 事务管理
- **TxRunner** 替代 `@Transactional`
- 事务边界在 Service 层
- 传播行为: REQUIRED (默认) / REQUIRES_NEW / SUPPORTS / NOT_SUPPORTED

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

# jOOQ codegen (需本地 PG 运行)
./gradlew :core-api:generateJooq

# 运行 (需要 PostgreSQL + Redis)
./gradlew :core-api:bootRun
```

## 参考文档

- [架构全貌](docs/ARCHITECTURE.md) — **必读**
- [E2E 测试方案](docs/E2E_TESTING.md)
- [剩余整理任务](docs/superpowers/plans/2026-08-18-remaining-cleanup.md)

## 重要设计决策

以下是已确定的设计决策，除非有充分理由否则不要推翻：

1. **GraphQL (DGS) 作为 API 层** — 替代旧 REST BFF
2. **jOOQ 替代 Jimmer** — 类型安全 SQL + 显式控制
3. **TxRunner 替代 @Transactional** — 支持多集群动态路由
4. **AuthInterceptor 非阻塞** — 支持匿名+认证混合接口
5. **presignUpload 不要求登录** — 后续通过行为验证增强
6. **Service 用 @Service + 构造器注入** — 不在 Config 里手动 new
7. **Repository 用 @Repository** — 启用 Spring 异常翻译
8. **AI ScanRunner 每个模型遍历所有 key** — 不是只试一个就跳下一个模型
9. **限流超限不删 Redis key** — 让 key 自然 TTL 过期
10. **objectKey 强格式校验** — `app_{appId}/(i_|u_)/...`，防路径遍历
11. **Webhook 必须验签** — Apple JWS / Google 通过 packageName 反查 appId
12. **DataLoader caching=false** — 只 batching，防 mutation 间脏读
13. **CacheAside 显式调用** — 不用 @Cacheable 魔法
14. **Domain Entity = data class** — 不是 jOOQ codegen POJO
15. **set/unset Update 语义** — 防 null vs undefined 歧义
16. **枚举全链路 Int 透传** — GraphQL 不用 enum，灰度/多版本安全
17. **枚举常量放 model class 嵌套 object** — 就近原则，跨模块的放 entity/shared/
18. **codegen 输出不与手写混** — jOOQ 生成到 src/generated/jooq/，DGS 在 build/generated/
19. **Operation 命名含对象** — `query_todo_findTodoById` 而非 `query_todo_findById`

## 工作方式

- **写计划 vs 直接做**: 如果直接做 token 消耗更少（改动明确、文件数少、不需要跨模块协调），优先直接做。不确定时问用户。

<!-- gitnexus:start -->
# GitNexus — Code Intelligence

This project is indexed by GitNexus as **ifmix_server** (2737 symbols, 5028 relationships, 229 execution flows). Use the GitNexus MCP tools to understand code, assess impact, and navigate safely.

> Index stale? Run `node .gitnexus/run.cjs analyze` from the project root — it auto-selects an available runner. No `.gitnexus/run.cjs` yet? `npx gitnexus analyze` (npm 11 crash → `npm i -g gitnexus`; #1939).

## Always Do

- **MUST run impact analysis before editing any symbol.** Before modifying a function, class, or method, run `impact({target: "symbolName", direction: "upstream"})` and report the blast radius (direct callers, affected processes, risk level) to the user.
- **MUST run `detect_changes()` before committing** to verify your changes only affect expected symbols and execution flows. For regression review, compare against the default branch: `detect_changes({scope: "compare", base_ref: "main"})`.
- **MUST warn the user** if impact analysis returns HIGH or CRITICAL risk before proceeding with edits.
- When exploring unfamiliar code, use `query({search_query: "concept"})` to find execution flows instead of grepping. It returns process-grouped results ranked by relevance.
- When you need full context on a specific symbol — callers, callees, which execution flows it participates in — use `context({name: "symbolName"})`.
- For security review, `explain({target: "fileOrSymbol"})` lists taint findings (source→sink flows; needs `analyze --pdg`).

## Never Do

- NEVER edit a function, class, or method without first running `impact` on it.
- NEVER ignore HIGH or CRITICAL risk warnings from impact analysis.
- NEVER rename symbols with find-and-replace — use `rename` which understands the call graph.
- NEVER commit changes without running `detect_changes()` to check affected scope.

## Resources

| Resource | Use for |
|----------|---------|
| `gitnexus://repo/ifmix_server/context` | Codebase overview, check index freshness |
| `gitnexus://repo/ifmix_server/clusters` | All functional areas |
| `gitnexus://repo/ifmix_server/processes` | All execution flows |
| `gitnexus://repo/ifmix_server/process/{name}` | Step-by-step execution trace |

## CLI

| Task | Read this skill file |
|------|---------------------|
| Understand architecture / "How does X work?" | `.claude/skills/gitnexus/gitnexus-exploring/SKILL.md` |
| Blast radius / "What breaks if I change X?" | `.claude/skills/gitnexus/gitnexus-impact-analysis/SKILL.md` |
| Trace bugs / "Why is X failing?" | `.claude/skills/gitnexus/gitnexus-debugging/SKILL.md` |
| Rename / extract / split / refactor | `.claude/skills/gitnexus/gitnexus-refactoring/SKILL.md` |
| Tools, resources, schema reference | `.claude/skills/gitnexus/gitnexus-guide/SKILL.md` |
| Index, status, clean, wiki CLI commands | `.claude/skills/gitnexus/gitnexus-cli/SKILL.md` |

<!-- gitnexus:end -->
