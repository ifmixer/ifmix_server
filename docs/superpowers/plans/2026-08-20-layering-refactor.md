# 重构计划：分层规范化 + ModuleCtx + 读写分离

> 创建时间: 2026-08-20
> 状态: 待执行
> 参考: ifmix-server-mongo/docs/plans/2026-08-19-architecture-restructure.md

## 目标

1. SvcCtx → ModuleCtx 改名（缩写 mc）
2. 全部 @Service/@Component 直注册，删除 Config 间接注册
3. 分层严格不跨级：DataFetcher → Facade → Handler → Repo
4. CrudRepoTemplate 组合持有，companion object
5. OperationContext 增加 preferReader，ModuleCtxFactory 构造时选择 sql（writer/reader）
6. Jimmer entity 直出 GraphQL，删除 toDto() 转换层
7. Handler 分为 AggHandler / EntityHandler
8. 事务分 GlobalTx / ModuleTx，当前只用 GlobalTx
9. 模块 & 目录重命名对齐业务语义
10. Operation 命名对齐 `q_/m_` 前缀

## 决策记录

| # | 决策 | 理由 |
|---|------|------|
| 1 | Context 三层：RequestContext → OperationContext → ModuleCtx | Jimmer 无需 session 传递，三层够用 |
| 2 | GlobalTx 在 DataFetcher 层开启 | 显式事务边界，整个 mutation field 一个事务 |
| 3 | ModuleTx 预留在 Facade 层 | 当前不用，将来拆分 module 时加 |
| 4 | 全部 @Service/@Component 直注册 | 删除 *Config 间接注册 |
| 5 | CrudRepoTemplate 组合持有 | 不继承，companion object |
| 6 | Handler 分 AggHandler / EntityHandler | Agg 跨 entity 编排，Entity 内部逻辑；简单场景只有 AggHandler |
| 7 | Jimmer entity 直出 GraphQL output | DGS PropertyDataFetcher 按 selection set 取字段，select(table) 保证标量全 loaded → 零转换 |
| 8 | 不手写 DTO/Mapper | DGS codegen 生成 input；output 直接用 entity；通用类型用共享 class |
| 9 | Mutation 返回 XxxResult | `{success, entity?}` 避免前端二次查询 |
| 10 | Update Input: set/unset 分离 | set null = 不更新；要清空用 unset 枚举；unset 优先 |
| 11 | 所有 Query 走 DataLoader | 批量加载，消除 N+1 |
| 12 | 查询优先支持 FilterGroup | 通用动态条件 + TypedProp 白名单 |
| 13 | Operation 命名: `${q\|m}_${module}_${action}` | 短前缀 + action 带 entity 名 |
| 14 | storage 独立模块 | 预签名上传/下载是通用基础服务 |
| 15 | Facade 只构造 ModuleCtx + 简单转发 | 不做 cache，不做复杂编排 |
| 16 | Cache 逻辑在 Handler 层 | Handler 知道 readCache 语义，写入后 evict |

---

## 1. 改名：SvcCtx → ModuleCtx

### 改动
```kotlin
data class ModuleCtx(
    val op: OperationContext,
    val sql: KSqlClient,
    val clusterId: String = "default",
    val inTransaction: Boolean = false,
) {
    val appId get() = op.appId
    val userId get() = op.userId
    val installId get() = op.installId
    val readCache get() = op.readCache
}
```

缩写约定：参数名用 `mc`（替代原来的 `sc` / `ctx`）

### 涉及文件
- `infra/db/SvcCtx.kt` → `infra/db/ModuleCtx.kt`
- `infra/db/SvcCtxFactory.kt` → `infra/db/ModuleCtxFactory.kt`
- 所有 Facade、Handler、Repo、TxRunner 引用处

---

## 2. 全部 @Service/@Component 直注册

- Facade → `@Service`
- Handler → `@Component`
- Repository → `@Repository`
- **删除**任何 Config 类中 `@Bean fun xxxService() = ...` 的间接注册

---

## 3. 分层严格不跨级

```
DataFetcher  →  只注入 Facade + GlobalTxRunner
Facade       →  只注入 Handler + ModuleCtxFactory（构造 mc + 转发）
Handler      →  只注入 Repo + CacheAside + 同模块 infra service
Repo         →  持有 CrudRepoTemplate（companion object）
```

### 事务
- **GlobalTx**: DataFetcher 层 `globalTx.withTx(opCtx) { ... }`
- **ModuleTx**: 预留 Facade 层，当前不用

### 禁止
- DataFetcher 不能 import handler/repo 包
- Facade 不能 import repo 包
- Handler 不能 import facade 包

### 跨模块
- Facade 可注入其他模块的 Facade

---

## 4. Handler 分类

### AggHandler（聚合处理器）
- 跨 entity 编排，命名 `XxxAggHandler`
- 注入多个 Repo + CacheAside
- 接收 ModuleCtx

### EntityHandler（实体处理器）
- 单 entity 复杂逻辑（状态机、领域规则）
- 命名 `XxxEntityHandler`
- 只在复杂场景使用，简单模块只有 AggHandler

### 当前规划

| 模块 | Handler |
|------|---------|
| demo | `TodoAggHandler` |
| ai | `ScanAggHandler` |
| payment | `PaymentAggHandler` |
| auth | `AuthAggHandler` |
| cms | `FeedbackAggHandler` |
| storage | `StorageAggHandler` |
| app | `AppConfigAggHandler` |

---

## 5. Jimmer Entity 直出 GraphQL

### 原理
DGS 用 graphql-java PropertyDataFetcher 按 selection set 逐字段调 getter。Jimmer Jackson Module 跳过未加载字段。`select(table)` 保证标量全 loaded → getter 安全。

### 做法
1. 删除所有 `toDto()` 转换
2. DGS typeMapping 映射 output type → Jimmer entity interface
3. DataFetcher 直返 entity
4. 关联字段走 DataLoader，不走 entity getter

### typeMapping
```kotlin
"Todo" to "com.ifmix.api.core.entity.demo.Todo",
"TodoItem" to "com.ifmix.api.core.entity.demo.TodoItem",
"ScanRecord" to "com.ifmix.api.core.entity.ai.ScanRecord",
// ...
"TodoPage" to "com.ifmix.api.core.dto.common.Page",
"OperationResult" to "com.ifmix.api.core.dto.common.OperationResult",
```

### 安全性
- schema 不声明的字段（appId, deletedAt 等）不暴露
- UUID 通过 Jackson 全局模块自动转 Base58

---

## 6. 多集群路由 + preferReader 读写分离

### 模型

每个集群有一对 KSqlClient（writer + reader）。通过 appId 或 authTenantId 决定走哪个集群，通过 preferReader 决定走 writer 还是 reader。

```kotlin
/** 一个集群 = writer + reader 两个 KSqlClient */
data class ClusterSqlPair(val writer: KSqlClient, val reader: KSqlClient)

interface ClusterRouter {
    fun forApp(appId: UUID): ClusterSqlPair
    fun forTenant(tenantId: UUID): ClusterSqlPair
}
```

### ClusterRegistry

```kotlin
@Component
class ClusterRegistry(private val props: ClusterProperties) {
    /** clusterId → ClusterSqlPair */
    private val clusters: Map<String, ClusterSqlPair> = buildClusters(props)

    fun get(clusterId: String): ClusterSqlPair =
        clusters[clusterId] ?: error("Unknown cluster: $clusterId")
}
```

### ClusterRouter 实现

```kotlin
@Component
class DefaultClusterRouter(private val registry: ClusterRegistry) : ClusterRouter {
    // 当前：所有 appId 走同一集群。将来根据 appId 查映射表路由。
    override fun forApp(appId: UUID) = registry.get("default")
    override fun forTenant(tenantId: UUID) = registry.get("default")
}
```

### GlobalTxRunner

```kotlin
@Component
class GlobalTxRunner(private val router: ClusterRouter) {

    /** 按 appId 路由到集群 writer 开事务 */
    fun <R> withTx(opCtx: OperationContext, body: (OperationContext) -> R): R {
        val pair = router.forApp(opCtx.mustGetAppId())
        val txCtx = opCtx.copy(globalTxSql = pair.writer, inGlobalTx = true)
        // Spring TransactionTemplate 绑定到 pair.writer 对应的 DataSource
        return doInTx(pair, txCtx, body)
    }

    /** 按 authTenant 路由 */
    fun <R> withTxForTenant(opCtx: OperationContext, tenantId: UUID, body: (OperationContext) -> R): R {
        val pair = router.forTenant(tenantId)
        val txCtx = opCtx.copy(globalTxSql = pair.writer, inGlobalTx = true)
        return doInTx(pair, txCtx, body)
    }
}
```

### ModuleCtxFactory

```kotlin
@Component
class ModuleCtxFactory(private val router: ClusterRouter) {

    fun forApp(opCtx: OperationContext): ModuleCtx {
        val sql = chooseSql(opCtx, router.forApp(opCtx.mustGetAppId()))
        return ModuleCtx(op = opCtx, sql = sql, inTransaction = opCtx.inGlobalTx)
    }

    fun forTenant(opCtx: OperationContext, tenantId: UUID): ModuleCtx {
        val sql = chooseSql(opCtx, router.forTenant(tenantId))
        return ModuleCtx(op = opCtx, sql = sql, inTransaction = opCtx.inGlobalTx)
    }

    private fun chooseSql(opCtx: OperationContext, pair: ClusterSqlPair): KSqlClient = when {
        opCtx.globalTxSql != null -> opCtx.globalTxSql  // 全局事务内，复用事务连接
        opCtx.preferReader -> pair.reader
        else -> pair.writer
    }
}
```

### 数据流

```
DataFetcher:
  opCtx.preferReader = !isMutation (默认)
  globalTx.withTx(opCtx):
    → router.forApp(appId) → ClusterSqlPair
    → pair.writer 开事务 → opCtx.globalTxSql = pair.writer

Facade:
  ModuleCtxFactory.forApp(opCtx):
    → globalTxSql != null → 复用事务 writer（事务内）
    → preferReader=true → pair.reader（query 无事务）
    → preferReader=false → pair.writer（mutation 无全局事务时）
```

### OperationContext

```kotlin
data class OperationContext(
    val req: RequestContext,
    val opName: String? = null,
    val isMutation: Boolean = false,
    val preferReader: Boolean = !isMutation,
    val globalTxSql: KSqlClient? = null,
    val inGlobalTx: Boolean = false,
)
```

### DataLoader 场景
mutation 中 opCtx.preferReader=false + inGlobalTx=true → DataLoader 走 globalTxSql（writer），读到最新数据。

---

## 7. CrudRepoTemplate

```kotlin
@Repository
class TodoRepository {
    companion object {
        private val tpl = CrudRepoTemplate(Todo::class, appId = "appId")
        val FILTERABLE = listOf(TodoProps.TITLE, TodoProps.DONE, ...)
    }
    fun findById(mc: ModuleCtx, appId: UUID, id: UUID) = tpl.findById(mc, appId, id)
    fun save(mc: ModuleCtx, entity: Todo) = tpl.save(mc, entity)
}
```

---

## 8. 类型复用 & DTO 原则

1. **Input** → DGS codegen 生成，全链路透传
2. **Output** → Jimmer entity 直出
3. **Result** → `XxxResult { success, entity? }`（DGS 生成）
4. **通用** → `Page<T>`, `OperationResult`（共享 class）
5. **不手写 DTO/Mapper**

---

## 9. Mutation Result

- 返回 `XxxResult { success: Boolean!, entity: Xxx }` 避免前端二次查询
- DataFetcher 按 selectionSet 判断是否回查 entity
- 只含 success 的用通用 `OperationResult`

---

## 10. set/unset 语义

```graphql
input UpdateTodoInput {
    id: UUID!
    set: UpdateTodoSetInput
    unset: [TodoUnsetField!]
}
```
- set 有值 → 更新；set 中不出现 → 不动
- unset 列出 → SET NULL
- 冲突 → unset 优先

---

## 11. FilterGroup 通用查询

- 列表查询优先支持 FilterGroup
- FILTERABLE 用 TypedProp 强类型白名单
- 可与专用 filter input 共存

---

## 12. Context 分层

```
RequestContext      HTTP 请求级    构造于: AuthInterceptor/DFE 解析
    ↓
OperationContext    Operation 级   构造于: DataFetcher (ctxProvider)
    ↓
ModuleCtx           模块调用级     构造于: Facade (ModuleCtxFactory)
```

Handler/Repo 不能自己构造 ModuleCtx，只能接收。

---

## 13. 模块 & 目录重命名

### 模块映射

| 旧名 | 新名 | 理由 |
|------|------|------|
| todo | **demo** | 示例模块 |
| feedback | **cms** | 内容管理语义更广 |
| iap | **payment** | 支付语义 |
| antique + collection → 合并 | **ai** | AI 扫描 + 收藏统一 |
| appconfig | **app** | 简洁 |
| storage | **storage** | 不变 |
| auth | **auth** | 不变 |

### 目录重命名映射

**bff/graphql/customer/**

| 当前 | 目标 |
|------|------|
| `todo/` | `demo/` |
| `feedback/` | `cms/` |
| `iap/` | `payment/` |
| `scan/` + `collection/` | `ai/`（合并） |
| `storage/` | 不变 |
| `auth/` | 不变 |

**entity/**

| 当前 | 目标 |
|------|------|
| `todo/` | `demo/` |
| `feedback/` | `cms/` |
| `iap/` | `payment/` |
| `ai/` + `scan/` | `ai/`（scan/ 合并进 ai/） |
| `storage/` | 不变 |
| `auth/` | 不变 |
| `app/` | 不变 |

**modules/** — 已对齐（demo, ai, payment, cms, storage, app, auth）

---

## 14. GraphQL Operation 命名

### 规则
```
${q|m}_${module}_${action}
```

### 示例

| Operation | 说明 |
|-----------|------|
| `q_demo_findTodoById` | 按 ID 查 |
| `q_demo_findTodos` | FilterGroup 查询 |
| `m_demo_createTodo` | 创建 |
| `m_demo_updateTodo` | 更新 |
| `m_demo_deleteTodo` | 删除 |
| `q_ai_findScans` | 扫描列表 |
| `m_ai_createScan` | 发起扫描 |
| `m_pay_verifyPurchase` | 购买验证 |
| `m_auth_loginGoogle` | Google 登录 |

### 约定
- action 动词开头：find/create/update/delete/verify/login/logout
- 复数：`findXxxs`
- 单个：`findXxxById`
- batch：`batchDeleteTodos`

---

## 15. DataLoader

- 所有关联字段通过 DataLoader 批量加载
- 顶级 Query 也走 DataLoader
- caching = false（只 batching，防 mutation 间脏读）

---

## 执行顺序

### Phase 1: 基础设施

| # | 改动 | 风险 |
|---|------|------|
| 1 | SvcCtx → ModuleCtx 全局替换 | 低 |
| 2 | ClusterRegistry 暴露 writerSql/readerSql | 低 |
| 3 | OperationContext 加 preferReader | 低 |
| 4 | ModuleCtxFactory chooseSql 逻辑 | 中 |
| 5 | DGS typeMapping entity 直出 | 低 |
| 6 | 注册 Jimmer ImmutableModuleV3 到 DGS ObjectMapper | 低 |

### Phase 2: 模块重组（逐个，每个独立可编译）

顺序：demo → cms → app → storage → payment → ai → auth

每个模块：
1. 目录重命名（bff + entity）
2. Handler 改名 → XxxAggHandler
3. 删除 toDto()，DataFetcher 直返 entity
4. Facade 去掉 TxRunner，纯 mc 构造 + 转发
5. mutation DataFetcher 加 globalTx.withTx
6. GraphQL schema: payload → result + operation 重命名 `q_/m_`
7. partialUpdate 加 unset 优先逻辑
8. 列表查询补 FilterGroup
9. 关联字段注册 DataLoader
10. @Bean 间接注册 → @Service/@Component
11. Repo 迁移到 CrudRepoTemplate
12. 编译验证

### Phase 3: 收尾

1. 删除旧 BaseAppCrudRepository / BaseCrudRepository
2. 删除所有 toDto() 残留
3. 确认 Operation 命名全部对齐
4. 更新 ARCHITECTURE.md + AGENTS.md
5. 全量测试

---

## 验证清单

- [ ] `./gradlew :core-api:compileKotlin` 通过
- [ ] mutation 走 writer / query 走 reader
- [ ] mutation 写后查走 writer
- [ ] GlobalTxRunner 内所有操作走同一 sql
- [ ] DataLoader 在 mutation 中走 writer
- [ ] Jimmer entity 直出 GraphQL，未声明字段不暴露
- [ ] 无 toDto() 残留
- [ ] 无 Config 间接注册
- [ ] 无跨层调用
- [ ] Mutation 返回 XxxResult
- [ ] selectionSet 按需回查 entity
- [ ] partialUpdate unset 优先
- [ ] 列表查询支持 FilterGroup
- [ ] 关联字段 DataLoader
- [ ] Operation 命名 `q_/m_` 前缀
- [ ] bff/entity 目录名对齐模块新名
- [ ] Handler 命名 XxxAggHandler
- [ ] Facade 无 TxRunner/cache 逻辑
