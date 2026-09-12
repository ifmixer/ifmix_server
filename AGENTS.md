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

| 文档 | 内容 |
|------|------|
| [架构总览](docs/ARCHITECTURE.md) | 分层、模块职责、设计决策、API 约定 |
| [编码指南](docs/CODING_GUIDE.md) | 每层怎么写、Context 模型、事务、示例代码 |
| [认证设计](docs/AUTH_DESIGN.md) | IDP + AuthIdentity 模型、登录判定表 |
| [数据库约定](docs/DATABASE.md) | 表清单、命名规则、UUID、枚举、FilterGroup |

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
./gradlew :core-api:bootRun           # 运行主服务 (需 PG + Redis)
./gradlew :core-job:bootRun           # 运行批处理任务 (需 PG)
```

## 工作方式

- **写计划 vs 直接做**: 改动明确、文件数少时优先直接做。不确定时问用户。

<!-- gitnexus:start -->
# GitNexus — Code Intelligence

This project is indexed by GitNexus as **ifmix_server** (3969 symbols, 7834 relationships, 331 execution flows).

> Index stale? Run `node .gitnexus/run.cjs analyze --index-only` from the project root — it auto-selects an available runner. No `.gitnexus/run.cjs` yet? Bootstrap with `npx`, `bunx`, or `pnpm dlx` — e.g. `bunx gitnexus@latest analyze` (npm 11 npx crash; #1939).

## Always Do

- **MUST run impact analysis before editing.** Use `impact({target: "symbolName", direction: "upstream"})` (MCP) or `node .gitnexus/run.cjs impact "symbolName" --direction upstream --repo .` (CLI fallback); report callers, processes, and risk. Never substitute grep for graph analysis.
- **MUST analyze graph changes before committing.** Use `detect_changes({scope: "all"})` (MCP) or `node .gitnexus/run.cjs detect-changes --scope all --repo .` (CLI fallback). `partial: true` or `truncated: true` is not a clean check — a zero means unseen, not unaffected; re-run it. For regression review: `detect_changes({scope: "compare", base_ref: "main"})` or `node .gitnexus/run.cjs detect-changes --scope compare --base-ref "main" --repo .`.
- **MUST warn the user** if impact analysis returns HIGH or CRITICAL risk before proceeding with edits.
- **MUST treat `risk: UNKNOWN` as unresolved, not as low.** An empty caller set is not evidence the symbol is unused — it can also mean the callers are not resolvable by the index (plain-object property access, dynamic dispatch, cross-language calls). `impact` pairs `UNKNOWN` with a `riskNote` saying so. Confirm with a text search before treating the symbol as safe to change or delete; do not proceed on the strength of a zero.
- When exploring unfamiliar code, use `query({search_query: "concept"})` to find execution flows instead of grepping. It returns process-grouped results ranked by relevance.
- When you need full context on a specific symbol — callers, callees, which execution flows it participates in — use `context({name: "symbolName"})`.
- For security review, `explain({target: "fileOrSymbol"})` lists taint findings (source→sink flows; needs `analyze --pdg`).

## Never Do

- NEVER edit a function, class, or method before MCP/CLI impact analysis.
- NEVER ignore HIGH or CRITICAL risk warnings from impact analysis, and never read `UNKNOWN` as an all-clear — it means the walk could not answer, which is the one verdict that requires confirming by other means.
- NEVER rename symbols with find-and-replace — use `rename` which understands the call graph.
- NEVER commit before MCP/CLI graph change analysis.

## Resources

| Resource | Use for |
| --- | --- |
| `gitnexus://repo/ifmix_server/context` | Codebase overview, check index freshness |
| `gitnexus://repo/ifmix_server/clusters` | All functional areas |
| `gitnexus://repo/ifmix_server/processes` | All execution flows |
| `gitnexus://repo/ifmix_server/process/{name}` | Step-by-step execution trace |

## CLI

| Task | Read this skill file |
| --- | --- |
| Understand architecture / "How does X work?" | `.claude/skills/gitnexus-exploring/SKILL.md` |
| Blast radius / "What breaks if I change X?" | `.claude/skills/gitnexus-impact-analysis/SKILL.md` |
| Trace bugs / "Why is X failing?" | `.claude/skills/gitnexus-debugging/SKILL.md` |
| Rename / extract / split / refactor | `.claude/skills/gitnexus-refactoring/SKILL.md` |
| Tools, resources, schema reference | `.claude/skills/gitnexus-guide/SKILL.md` |
| Index, status, clean, wiki CLI commands | `.claude/skills/gitnexus-cli/SKILL.md` |

<!-- gitnexus:end -->
