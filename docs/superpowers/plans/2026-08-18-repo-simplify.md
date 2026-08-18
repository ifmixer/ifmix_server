# Repo 简化：用 fetchInto + crud.insert 替代手写映射

> 日期: 2026-08-18

## 问题

多个 repo 有手动 `toModel` 映射（逐字段 `r.get(FIELD)`）和手写 insert（逐字段 `.values(...)`）。jOOQ 的 `fetchInto(Model::class.java)` 和 `crud.insert(ctx, TABLE, model)` 可自动完成。

## 需要改的文件（11 个）

| 文件 | 问题 |
|------|------|
| `ai/repo/ScanCollectionRepository.kt` | toModel + 手写 insert |
| `ai/repo/ScanRecordRepository.kt` | toModel + 手写 insert |
| `app/repo/AppConfigRepository.kt` | toModel + 手写 insert |
| `auth/repo/AppRefreshTokenRepository.kt` | toModel + 手写 insert |
| `auth/repo/AppUserRepository.kt` | toModel + 手写 insert |
| `auth/repo/AuthDeviceSecretRepository.kt` | toModel + 手写 insert |
| `auth/repo/AuthIdentityRepository.kt` | toModel + 手写 insert |
| `auth/repo/AuthProviderIdentityRepository.kt` | toModel + 手写 insert |
| `auth/repo/AuthTenantRepository.kt` | toModel + 手写 insert |
| `auth/repo/UserInstallBindingRepository.kt` | toModel + 手写 insert |
| `payment/repo/StoreNotificationRepository.kt` | toModel + 手写 insert |

## 改法

### 查询：`toModel` → `fetchInto`

```kotlin
// 改前
ctx.dsl.selectFrom(TABLE).where(...).fetch().map { toModel(it) }
companion object {
    fun toModel(r: Record) = MyEntity(
        id = r.get(TABLE.ID)!!,
        appId = r.get(TABLE.APP_ID)!!,
        ...
    )
}

// 改后
ctx.dsl.selectFrom(TABLE).where(...).fetchInto(MyEntity::class.java)
// 删除 toModel companion object
```

单条查询同理：`fetchOne()?.let { toModel(it) }` → `fetchOneInto(MyEntity::class.java)`

### 插入：手写字段 → `crud.insert`

```kotlin
// 改前
ctx.dsl.insertInto(TABLE, TABLE.ID, TABLE.APP_ID, TABLE.TITLE, TABLE.CREATED_AT, ...)
    .values(id, appId, title, now, ...)
    .execute()

// 改后
crud.insert(ctx, TABLE, MyEntity(id = id, appId = appId, title = title, createdAt = now, ...))
```

### 前提

entity data class 的字段名必须和 jOOQ Record 属性名（camelCase）对齐。`fetchInto` 和 `newRecord(TABLE, model)` 都按名字映射。

如有字段名不匹配（如 entity 叫 `imageKeys` 但 DB 列叫 `image_keys` → jOOQ Record 叫 `imageKeys`），需确认一致。

## 步骤

```
对每个文件：
1. 删除 toModel companion object
2. 所有 .fetch().map { toModel(it) } → .fetchInto(Entity::class.java)
3. 所有 .fetchOne()?.let { toModel(it) } → .fetchOneInto(Entity::class.java)
4. 所有手写 insertInto(...).values(...) → crud.insert(ctx, TABLE, entity)
5. 编译通过
```

可并行处理所有文件（互不依赖）。
