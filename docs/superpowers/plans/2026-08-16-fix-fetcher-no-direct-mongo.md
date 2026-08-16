# 修复：Fetcher 不应直接调用 MongoTemplate

## 原则

Fetcher → Service → Repo → MongoTemplate。Fetcher 只调用 Service 层。

## 需要修复的文件

### 1. `CustomerCollectionFetcher.kt`

**问题：** `loadCollectionItems` 方法直接调用 `mongo.find`。

**修复方案：** `CollectionService.listItems` 当前返回 `Page<ScanRecordDocument>`，但 `CustomerCollectionFetcher.collectionItems` 需要构建 `CollectionItemType`（包含 id, collectionId, scanRecordId, scanRecord, createdAt）。

**执行步骤：**

1. 在 `CollectionService` 中新增方法 `listItemsWithRecords`：

```kotlin
/**
 * 列出收藏条目（包含 item 元信息 + 关联的 scan record），用于 GraphQL CollectionItemType 构建。
 */
fun listItemsWithRecords(ctx: RequestContext, req: ListItemsReq): Pair<List<Pair<CollectionItemDocument, ScanRecordDocument?>>, Boolean> {
    val cid = resolveCollectionId(ctx, req.collectionId)
    val limit = (req.limit ?: CursorQueryInput.DEFAULT_LIMIT).coerceIn(1, CursorQueryInput.MAX_LIMIT)
    // 复用 itemRepo 的分页逻辑，但返回 item 文档而非 scan record
    // 需要在 CollectionItemRepository 添加 findItemsByCursor 方法
}
```

2. 在 `CollectionItemRepository` 中新增 `findItemsByCursor` 方法（返回 `CollectionItemDocument` 列表）：

```kotlin
fun findItemsByCursor(ctx: RequestContext, collectionId: String, cursor: String?, limit: Int): List<CollectionItemDocument> {
    val query = Query(base(ctx, collectionId).and("deletedAt").`is`(null))
    if (!cursor.isNullOrBlank() && ObjectId.isValid(cursor)) {
        query.addCriteria(Criteria.where("_id").lt(ObjectId(cursor)))
    }
    query.with(Sort.by(Sort.Direction.DESC, "_id"))
    query.limit(limit + 1)
    return mongo.find(query, CollectionItemDocument::class.java)
}
```

3. 在 `CollectionService` 中实现 `listItemsWithRecords`：

```kotlin
fun listItemsWithRecords(ctx: RequestContext, req: ListItemsReq): Triple<List<CollectionItemDocument>, Map<String, ScanRecordDocument>, Boolean> {
    val cid = resolveCollectionId(ctx, req.collectionId)
    val limit = (req.limit ?: CursorQueryInput.DEFAULT_LIMIT).coerceIn(1, CursorQueryInput.MAX_LIMIT)
    val items = itemRepo.findItemsByCursor(ctx, cid, req.cursor, limit)
    val hasMore = items.size > limit
    val pageItems = if (hasMore) items.dropLast(1) else items

    // 批量加载关联的 scan records
    val scanIds = pageItems.mapNotNull { it.scanRecordId?.toHexString() }.distinct()
    val scanMap = if (scanIds.isNotEmpty()) {
        // 通过 AntiqueService 或直接 ScanRecordRepository 批量查
        // 建议在 AntiqueService 中加 findByIds 方法
        mongo.find(
            Query(Criteria.where("_id").`in`(scanIds.map { ObjectId(it) }).and("appId").`is`(ctx.appId).and("deletedAt").`is`(null)),
            ScanRecordDocument::class.java,
        ).associateBy { it.id }
    } else emptyMap()

    return Triple(pageItems, scanMap, hasMore)
}
```

**注意：** 上面 `CollectionService` 内部仍然有 `mongo.find` 调用来批量查 scan records。这应该通过 `AntiqueService.findByIds(ctx, ids)` 来做。如果 `AntiqueService` 没有这个方法，需要加一个。

4. 重写 `CustomerCollectionFetcher.collectionItems`：

```kotlin
@DgsQuery
fun collectionItems(
    @InputArgument collectionId: String?,
    @InputArgument cursor: String?,
    @InputArgument limit: Int?,
    dfe: DgsDataFetchingEnvironment,
): CollectionItemConnection {
    val ctx = getContext(dfe)
    val effectiveLimit = (limit ?: 20).coerceIn(1, 100)
    val req = ListItemsReq(collectionId = collectionId, cursor = cursor, limit = effectiveLimit)
    val (items, scanMap, hasMore) = collectionService.listItemsWithRecords(ctx.requestContext, req)

    return CollectionItemConnection(
        items = items.map { item ->
            val scanRecord = scanMap[item.scanRecordId?.toHexString()]?.toScanRecordType()
            item.toCollectionItemType(scanRecord)
        },
        nextCursor = if (items.isNotEmpty()) items.last().id else null,
        hasMore = hasMore,
    )
}
```

5. 从 `CustomerCollectionFetcher` 构造函数中移除 `mongo: MongoTemplate` 和 `itemRepo: CollectionItemRepository` 参数，只保留 `collectionService: CollectionService`。

---

### 2. `AdminAppConfigFetcher.kt`（已修复）

已重写完毕，所有 mongo 调用移到 `AppConfigRepo`。但 `AppConfigRepo.toggleRevision` 新方法需要确保编译通过：

- 确保文件顶部有 `import com.ifmix.api.core.common.http.RequestContext`
- 确保有 `import org.bson.types.ObjectId`

---

### 3. `CollectionService.addItem` 中的 mongo 直接调用

当前 `CollectionService.addItem` 内部有：
```kotlin
mongo.updateFirst(..., ScanRecordDocument::class.java)
```
用于标记 scan record 的 `collected=true`。

**修复：** 通过 `AntiqueService.markCollected(ctx, scanRecordId, true)` 代替直接 mongo 调用。如果 `AntiqueService` 没有此方法，需要加一个：

```kotlin
// AntiqueService
fun markCollected(ctx: RequestContext, scanRecordId: String, collected: Boolean) {
    scanRecordRepo.update(ctx, scanRecordId, Update().set("collected", collected))
}
```

然后 `CollectionService` 构造函数注入 `AntiqueService`（或对应的 `ScanRecordRepo`），替换 `mongo` 直接调用。

---

## 验证

修复完成后运行：
```bash
# 确认没有 fetcher 直接引用 MongoTemplate
grep -rn "MongoTemplate\|mongo\." core-api/src/main/kotlin/com/ifmix/api/core/graphql/ | grep -v "//\|import"
# 应该输出为空

./gradlew :core-api:compileKotlin
./gradlew :core-api:bootRun  # 确认启动成功
```
