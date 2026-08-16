# 计划：x-op-id Header 统一替代 persisted query hash + operationName

## 目标

客户端请求：
```http
POST /customer/graphql
x-op-id: GetTodos
Content-Type: application/json

{"variables":{"limit":10}}
```

- body 不再需要 `query`、`extensions.persistedQuery.sha256Hash`、`operationName`
- `x-op-id` 一个值搞定查询定位
- 向后兼容：没有 `x-op-id` 时走原有 hash 逻辑

---

## 当前流程

```
客户端 → TrustedDocumentFilter (OncePerRequestFilter, @Order(1))
       → 从 body 正则提取 sha256Hash
       → PersistedQueryStore.get(hash, bff) → 注入 query 到 body
       → GraphQLRouterController 执行
```

**PersistedQueryStore 格式**：
```json
{ "sha256hash": { "name": "GetTodos", "query": "query GetTodos(...) {...}" } }
```

key 是 hash，name 是 operationName。

---

## 改造方案

### 1. PersistedQueryStore 增加按 name 查找

```kotlin
interface PersistedQueryStore {
    fun getByHash(hash: String, bff: String): PersistedQueryEntry?
    fun getByName(name: String, bff: String): PersistedQueryEntry?
}
```

ClasspathPersistedQueryStore 的 `init()` 额外构建一个 `name → entry` 的 map：

```kotlin
private val storesByHash = mutableMapOf<String, Map<String, PersistedQueryEntry>>()
private val storesByName = mutableMapOf<String, Map<String, PersistedQueryEntry>>()

@PostConstruct
fun init() {
    for (bff in listOf("customer", "admin")) {
        // ... 读 JSON
        val byHash = mutableMapOf<String, PersistedQueryEntry>()
        val byName = mutableMapOf<String, PersistedQueryEntry>()
        root.properties().forEach { (hash, node) ->
            val entry = PersistedQueryEntry(name = ..., query = ...)
            byHash[hash] = entry
            byName[entry.name] = entry
        }
        storesByHash[bff] = byHash
        storesByName[bff] = byName
    }
}

override fun getByHash(hash: String, bff: String) = storesByHash[bff]?.get(hash)
override fun getByName(name: String, bff: String) = storesByName[bff]?.get(name)
```

### 2. TrustedDocumentFilter 优先读 x-op-id

在 `doFilterInternal` 开头加 x-op-id 分支：

```kotlin
override fun doFilterInternal(request, response, filterChain) {
    val uri = request.requestURI
    if (!uri.startsWith("/customer/graphql") && !uri.startsWith("/admin/graphql")) {
        filterChain.doFilter(request, response)
        return
    }

    val bff = if (uri.startsWith("/admin/")) "admin" else "customer"
    val opId = request.getHeader("x-op-id")

    if (opId != null) {
        // 新路径：x-op-id
        val entry = store.getByName(opId, bff)
        if (entry == null) {
            response.sendError(404, "Unknown operation: $opId")
            return
        }
        val bodyBytes = request.inputStream.readBytes()
        val newBody = buildBody(entry, bodyBytes)
        request.setAttribute("trusted.operation.name", entry.name)
        filterChain.doFilter(CachedBodyRequest(request, newBody), response)
        return
    }

    // 原有路径：从 body 提取 hash
    // ... 现有逻辑不变
}
```

### 3. buildBody 辅助方法

从原始 body 提取 variables，拼出完整 GraphQL request body：

```kotlin
private fun buildBody(entry: PersistedQueryEntry, originalBody: ByteArray): ByteArray {
    val bodyStr = String(originalBody, Charsets.UTF_8)
    val variables = extractVariables(bodyStr)
    val escaped = entry.query.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
    return """{"query":"$escaped","operationName":"${entry.name}","variables":$variables}"""
        .toByteArray(Charsets.UTF_8)
}

private fun extractVariables(body: String): String {
    if (body.isBlank()) return "{}"
    // 简单提取 "variables":{...} — 或用 Jackson
    return try {
        val node = mapper.readTree(body)
        node.get("variables")?.toString() ?: "{}"
    } catch (_: Exception) { "{}" }
}
```

### 4. 空 body 处理

客户端可能发：
- `{"variables":{"limit":10}}` — 正常
- `{}` — 无 variables
- 空 body — 完全靠 x-op-id

三种都要处理。

---

## 最终客户端协议

| 场景 | 请求 |
|------|------|
| 无参数 query | `POST /customer/graphql` + `x-op-id: GetTodos` + 空 body 或 `{}` |
| 带变量 | `POST /customer/graphql` + `x-op-id: GetTodo` + `{"variables":{"id":"abc"}}` |
| 旧客户端（兼容） | 原来的 `{extensions:{persistedQuery:{sha256Hash:"..."}}, ...}` 继续工作 |

---

## 5. operationId 注入 RequestContext

`RequestContext` 加字段：

```kotlin
data class RequestContext(
    val appId: ObjectId,
    val operationId: String? = null,   // ← 新增
    val installId: String? = null,
    // ...
)
```

来源优先级：
1. `x-op-id` header（新路径）
2. `request.getAttribute("trusted.operation.name")`（hash 路径解析出的 name）
3. body 里的 `operationName`（开发模式，未走 persisted query）

在 `RequestContextArgumentResolver` / `GraphQLContextBuilder` 构建 ctx 时读取：

```kotlin
val operationId = request.getHeader("x-op-id")
    ?: request.getAttribute("trusted.operation.name") as? String
```

用途：日志、metrics、限流、审计都可以按 operationId 标识。

---

## 文件改动清单

| 文件 | 改动 |
|------|------|
| `PersistedQueryStore.kt` | 接口加 `getByName`，实现加 byName 索引 |
| `TrustedDocumentFilter.kt` | 加 x-op-id 分支 + buildBody + Jackson 依赖 |
| `RequestContext.kt` | 加 `operationId: String?` 字段 |
| `RequestContextArgumentResolver.kt` 或 `GraphQLContextBuilder.kt` | 构建时读 x-op-id / attribute 填入 operationId |
| `PersistedQueryStoreTest.kt` | 加 getByName 测试 |

---

## 验证

- 单元测试：模拟 x-op-id header，验证 filter 注入正确 query + operationName
- 单元测试：x-op-id 不存在 → 404
- 单元测试：无 x-op-id → 走原有 hash 逻辑不变
- 集成测试：发真实请求 `POST /customer/graphql` + `x-op-id: GetTodos` + `{"variables":{}}` → 返回正确数据

---

## 后续可选

- 支持 GET 请求 `/customer/graphql?op=GetTodos&variables={...}` 用于 CDN 缓存
- 网关层按 x-op-id 做限流/权限
- 客户端 codegen 生成 operationName 常量
