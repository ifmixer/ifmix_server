# AGENTS.md

## 项目上下文

本项目是 **ifmix_server** — 面向移动端的后端 API 服务（古物扫描 + AI 图像识别 + 收藏管理 + 社交登录 + IAP）。

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
| [认证设计](docs/AUTH_DESIGN.md) | IDP 模型、登录流程 |
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
./gradlew :core-api:compileKotlin    # 编译（含 KSP）
./gradlew :core-api:test              # 测试
./gradlew :core-api:bootRun           # 运行 (需 PG + Redis)
```

## 工作方式

- **写计划 vs 直接做**: 改动明确、文件数少时优先直接做。不确定时问用户。
