# Konvert 全量替换手写 mapper — 服务端实施计划

> **给接手的 agent**：先读 `AGENTS.md` → `docs/design/proposals/graphql-to-http-rpc-openapi.md` §一 → 本文件。所有映射形态、注解、文件路径均已定稿，**不做任何设计决策**；遇到与本文不符的代码现实，停下写报告。
> - 仓库：/Users/jason/orca/workspaces/ifmix_server/server-graphql-to-rpc（分支 `feature/graphql-to-rpc`）。Konvert 4.5.1 已引入且 cs 试点已通过（commit `b2038bd`，见 `dto/cs/SubmitFeedbackInput.kt` 的 `@KonvertTo` 用法）。
> - 注解包名：`io.mcarle.konvert.api.*`（**不是** `.annotations`）。语法细节以官方文档校准：https://mcarleio.github.io/konvert/annotations/mapping.html
> - 硬边界：不改 wire 契约字段；不改 Handler/Repository 行为；不动 demo/auth 之外的 wire 类型；每个 K 阶段独立 commit（前缀 `[konvert][K*]`）并跑 `./gradlew :core-api:test` 全绿。

## 0. 目标与原则

把所有「纯字段搬运」的 mapper 换成 Konvert 生成，消除"新增字段忘拷贝"的静默丢失。**保留手写**的只有两类：含业务逻辑分支的构造（如 attest 的 attestationStatus 决策）、跨实体聚合的编排（批量查/组装在 QueryService，Konvert 只吃编排的产物）。

**关键决策树（遇到即按此执行，不是开放问题）**：
- Konvert 对 **Jimmer entity（接口）作为源** 的支持未验证 → K2 的第一个任务就是验证它（`TodoItem` 源）。**若编译不过或生成代码语义错误**：demo/ai 的 entity 源映射全部回退手写（恢复被删文件），只保留 data-class→data-class 的 Konvert（K1/K4），并在报告里记录"Konvert 不支持接口源"。
- Konvert 生成代码会读取**所有**映射属性 → 对稀疏 Jimmer 实体（部分 fetch）会抛 UnloadedException。因此本计划中所有 entity→Res 的映射，其 `@Mapping` 声明必须与该查询的 fetch 形状严格一致（每个 K 阶段已逐条写明）。

## 1. K1：全局自定义转换器（新建 `dto/common/KonvertConverters.kt`）

全项目唯一的手写转换器文件，供 Konvert 经 `qualifiedBy` 复用：

```kotlin
package com.ifmix.core.api.dto.common

import io.mcarle.konvert.api.KonvertFrom
import io.mcarle.konvert.api.KonvertTo
import java.time.Instant

/** Instant → ISO-8601 UTC 字符串（与手写 toString() 等价）。 */
@KonvertTo(String::class, qualifiedNames = ["iso-string"])
fun Instant.toIsoString(): String = toString()

/** epoch millis → Instant（auth MeRes.tierExpiresAt 用）。语法以官方文档校准：@KonvertFrom 标在目标类型转换函数上。 */
@KonvertFrom(Long::class, qualifiedNames = ["millis-instant"])
fun Long.toInstantFromMillis(): Instant = Instant.ofEpochMilli(this)
```

（若 `@KonvertFrom(Long::class)` 的实际语法与此不符——例如需要 `@Konverter` 内声明——按官方文档校正语法，**转换器语义不变**。）

## 2. K2：demo 模块（第一个 entity 源试点 + 多参数聚合）

### 2.1 新建 `dto/demo/DemoKonvertMappers.kt`

```kotlin
@Konverter
interface DemoKonvertMappers {
    // 单源：TodoItem（Jimmer entity 接口）→ TodoItemRes。⚠️ 这是 Konvert 接口源可行性验证点。
    fun toRes(source: TodoItem): TodoItemRes

    // 嵌套子映射（TodoRecommend 是 @Serialized 值对象 data class；RecItem 是其内嵌 data class）
    fun toRes(source: TodoRecommend): TodoRecommendRes

    // 多参数聚合：todo 为源；items/counts 用 expression 从参数取
    @Mapping(target = "items", expression = "items.map { org.ifmix…（见下）}")
    @Mapping(target = "itemCount", expression = "counts.itemCount")
    @Mapping(target = "pendingCount", expression = "counts.pendingCount")
    @Mapping(target = "finishCount", expression = "counts.finishCount")
    fun toRes(source: Todo, items: List<TodoItemRes>, counts: TodoItemCounts): TodoRes
}
```

注意事项（逐条执行，勿改语义）：
1. `TodoRes.createdAt/updatedAt`、`TodoItemRes.createdAt/updatedAt`、`TodoRecommendRes` 内时间字段均为 `String`，源为 `Instant` → Konvert 自动匹配 K1 的 `iso-string` 转换器；如未自动匹配，在对应函数上加 `@Konfig` 或 per-field `@Mapping(qualifiedBy = ["iso-string"])`（语法以官方文档为准）。
2. `TodoRecommend.RecItem` 是 `TodoRecommend` 的**内嵌** data class：`TodoRecItemRes` 的映射函数参数类型写全名 `TodoRecommend.RecItem`。
3. `items` 的 expression：List 元素映射直接引用本接口已声明的 `toRes`（Konvert 自动组合 List 元素映射；若 expression 内不能引用，改写为 `@Mapping(target="items")` 不带 expression，依赖 Konvert 对 `List<TodoItem>→List<TodoItemRes>` 的元素级自动组合——两种写法**先试自动组合，不行再用 expression**，此为语法校准不是设计决策）。
4. 多参数函数若 4.5.1 生成失败：`counts` 的三个字段改在 `@Konverter` 外由调用方 `copy(itemCount=…)` 补齐，`toRes` 退化为双参数（todo+items）——**此为明确回退路径，不是决策**。

### 2.2 改调用方 + 删手写

- 删 `dto/demo/DemoApiMappers.kt` 整个文件。
- `bff/api/customer/demo/DemoQueryService.kt`：`DemoApiMappers.toDto(...)` 的调用点（`assemble()` 内）改为 `DemoKonvertMappers.toRes(todo, itemsRes, counts)`——**items 的 List<TodoItemRes> 与 counts 的取值/补 0 编排保留在 assemble() 内原样**（Konvert 不接管编排）。
- `DemoController.kt` 的 `facade.create(...)` 后组装：同上改调用。
- 测试 `test/.../dto/demo/DemoApiMappersTest.kt`：改名为 `DemoKonvertMappersTest.kt`，原字段对齐断言全部保留（断言对象换成生成 mapper 的输出，验证与手写版本逐字段一致）。

### 2.3 验收
`:core-api:test` 全绿；`TodoRes`/`TodoItemRes` 字段与手写版逐字段一致（测试断言保证）；commit `[konvert][K2]`。

## 3. K3：ai 模块

### 3.1 新建 `dto/ai/AiKonvertMappers.kt`

```kotlin
@Konverter
interface AiKonvertMappers {
    // ImageRef 是 @Serialized 值对象 data class（key/category）→ 纯 data class 转换
    fun toRes(source: ImageRef): ImageRefRes

    // 单源 entity（完整加载路径：getById 详情查询）
    @Mapping(target = "basicResult", expression = "source.basicResult")
    @Mapping(target = "latestDeepResearch", expression = "latestDeepResearch")
    fun toDetailRes(source: ScanRecord, latestDeepResearch: ScanDeepResearchRes?): ScanRecordRes

    // 轻量（collectionItem_list 批量组装，findByIdsListView 不加载 basicResult）：
    // basicResult 必须 constant null——绝不触碰 source.basicResult（稀疏实体，Konvert 全属性读取会炸）
    @Mapping(target = "basicResult", constant = "null")
    @Mapping(target = "latestDeepResearch", expression = "latestDeepResearch")
    fun toLiteRes(source: ScanRecord, latestDeepResearch: ScanDeepResearchRes?): ScanRecordRes

    // 列表（findMyScans 的 ScanRecordListView，basicResult 未加载）→ 同上 constant null
    @Mapping(target = "basicResult", constant = "null")
    fun toListRes(source: ScanRecord): ScanRecordListRes

    fun toRes(source: ScanDeepResearch): ScanDeepResearchRes
}
```

逐字段核对（`ScanRecordRes` 全字段与 AiApiMappers 现状一一对应，缺一即报告）：
`id, createdAt(iso), updatedAt(iso), isPublic, status, errorCode, locale, country, currency, userDisplayName, userNotes, collected, hasDeepSearch, images(List<ImageRef>→List<ImageRefRes> 由元素映射自动组合), basicResult, latestDeepResearch`。**`ScanRecordRes` 没有 `errorDetails/clientIp/latestDeepResearchId/hasDeepSearch 之外的多余字段`——若编译发现源属性缺失，说明字段清单写错，停下报告。**

⚠️ 两个已知的形状差异，**不是决策**：
1. `constant = "null"` 若 Konvert 语法不支持 constant null → 该函数保留手写（从 AiApiMappers 原样搬为普通函数，不走 Konvert），其余函数照常 Konvert。
2. entity 接口源已在 K2 验证过；K3 直接沿用结论。

### 3.2 删手写 + 改调用方

- 删 `dto/ai/AiApiMappers.kt`（`imageRefToDto`/`deepResearchToDto`/`toDetailRes`/`toLiteRes`/`toListRes` 全部由生成 mapper 替代）。
- `bff/api/customer/ai/AiQueryService.kt` / `AiController.kt`：调用点改 `AiKonvertMappers.toDetailRes(...)` 等（`latestDeepResearch` 批量预取编排原样保留）。
- 测试：ai 既有单测中对 `AiApiMappers` 的引用全部改 `AiKonvertMappers`；字段断言保留。

### 3.3 验收
`:core-api:test` 全绿；commit `[konvert][K3]`。

## 4. K4：auth 模块

- `bff/api/customer/auth/AuthApiController.kt` 的私有 `LoginRes.toWire()`：删除，改为 Konvert。**注意同名类型**：module `LoginRes`（modules.auth.handler）与 wire `LoginRes`（dto.auth）同名——用 `@Konverter` 接口时参数类型写全名 `com.ifmix.core.api.modules.auth.handler.LoginRes`，返回 `com.ifmix.core.api.dto.auth.LoginRes`：
  ```kotlin
  @Konverter
  interface AuthKonvertMappers {
      fun toWire(source: com.ifmix.core.api.modules.auth.handler.LoginRes): com.ifmix.core.api.dto.auth.LoginRes
  }
  ```
  `MeRes` 组装（user{id,email} + tier/active + expiresAt Long→Instant）：expiresAt 是 epoch millis → Instant 转换（K1 的 `millis-instant`），user 是嵌套（module `MeRes.id/email` → wire `UserInfoRes`）——若 Konvert 表达式对跨参数/嵌套表达吃力，**MeRes 组装保留手写**（明确回退路径），只把 `toWire` 换 Konvert。
- 测试 `AuthApiControllerTest.kt` 断言不变（输出对象字段一致）。
- 验收 + commit `[konvert][K4]`。

## 5. 明确不改清单（禁止动手）

1. **install** `InstallApiController` 的三处 Res 构造：`attestationStatus`（10/20/30）与 `enabled` 是业务决策分支，不是字段搬运。
2. **customer** `createAnonymous` 的 Res 构造：含 miss→404 分支逻辑。
3. **pay** `VerifyIapPurchaseRes(...)`：由 verify 结果构造——先检查是否纯同名字段搬运：**是**则按 K4 同样方式 Konvert（这是本计划唯一允许的"检查后实施"项，结果写进报告）；含逻辑分支则保留手写。
4. **cs** 已完成（`b2038bd`），不再动。
5. 不引入 Konvert 以外的映射库；不改 Konvert 版本；不删 `dto/common/KonvertConverters.kt` 之外的任何转换器。

## 6. 最终验收

- `./gradlew :core-api:compileKotlin :core-api:test` 全绿；`grep -rn "object.*ApiMappers\|object.*Mappers" core-api/src/main/kotlin --include="*.kt"` 结果为空（手写 mapper object 清零，KonvertConverters 与 @Konverter 接口除外）。
- 输出报告：每模块改动文件、Konvert 生成代码与手写版的字段级等价核对表、决策树各分支的实际走向（接口源是否支持、constant null 语法、多参数语法）、回退点清单。
