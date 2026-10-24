# Cloud Config（云配置 · 客户端冷启动按 keys 拉取）

- 日期：2026-10-24
- 状态：已实现（v1.0.8）
- 模块：`core-api` / `modules/project`（聚合 CloudConfig）
- 关联：[push-feature-flag](../notification/push-feature-flag.md) §1.1 预留的「后端权威配置源」——本文即其落地

## 1. 背景与目标

客户端行为开关目前散在两处：前端 hard code 常量（push-feature-flag 方案，OTA 才能改）和
`core_project_configrevision`（app 身份/IAP 凭据，服务端自用，不下发）。运营需要一个
**服务端权威的键值配置**：app 冷启动时拉一次，据此调整 app 内部行为（push 开关、功能灰度、
运营开关、参数下发等），改配置不用发版/OTA。

定位：**通用 remote config**，只做「key → 任意 JSON 值」的下发与定向；key 的语义由客户端约定，
服务端不解释 value。敏感凭据仍归 `core_project_serverconfig`（绝不下发），两者互不相干。

## 2. 能力边界（v1）

| 能力 | v1 | 说明 |
|------|----|------|
| 按 keys 拉取 | ✅ | 请求带 `keys` 列表，只返回所求；key 的集合由客户端按版本约定（服务端不校验 key 名） |
| 平台定向 | ✅ | 同 key 可配 `platform=0`（全部）与单平台两行，单平台覆盖全部 |
| ota 版本门槛 | ✅ | `min_ota_version` / `max_ota_version`，请求 `otaVersion` 不满足则不下发该行 |
| 匿名访问 | ✅ | 冷启动时可能尚无 customer token，接口不要求登录（meta 仍硬校验 projectId/platform） |
| 运营写入口 | SQL 直改 | 不做 admin mutation（无 manager BFF），见 §6 手册 |
| per-user 百分比灰度 | ❌ | 未来演进（需要用户分桶，冷启动匿名期拿不到稳定 uid） |
| 多版本 / 历史快照 | ❌ | 一行 = 当前值；软回滚靠改回旧值 + `note` 留痕。真要历史时加独立 history 表（§10-5），不走 configrevision 整包复制 |
| admin mutation / 审计 | ❌ | 未来跟 manager BFF 一起做 |
| 条件拉取（304） | ❌ | 客户端可自行缓存 `configVersion` 对比；服务端不省响应体（响应本来就小） |

## 3. 表结构

`core_project_cloudconfigitem`（project 级，一个 key 一行；进 V1 基线）：

| 列 | 类型 | 说明 |
|----|------|------|
| `id` | uuid PK | UuidV7 |
| `project_id` | varchar(64) NOT NULL | app slug |
| `key` | varchar(128) NOT NULL | 配置键。客户端约定命名（建议 camelCase，如 `scanPushEnabled`） |
| `value` | jsonb NOT NULL | 任意 JSON 值（布尔/数字/字符串/对象/数组均可） |
| `platform` | smallint NOT NULL DEFAULT 0 | **0=全部平台（默认）**，10=ios, 20=android, 30=web；单平台行覆盖同 key 的 0 行 |
| `min_ota_version` | varchar(32) NULL | otaVersion ≥ min 才下发（新版本门槛，如 `1-23-0`） |
| `max_ota_version` | varchar(32) NULL | otaVersion ≤ max 才下发（旧版本上限） |
| `enabled` | boolean NOT NULL DEFAULT true | false = 停用（不删除，保留现场） |
| `note` | text NOT NULL DEFAULT '' | 运维备注（为什么改、何时改） |
| `created_at` / `updated_at` | timestamptz NOT NULL | updated_at 参与 configVersion 计算 |

索引：

- `core_project_cloudconfigitem_uq` UNIQUE `(project_id, key, platform)` —— 同 key 同平台只一行。
- `core_project_cloudconfigitem_project_enabled_idx` ON `(project_id) WHERE enabled` —— 读路径过滤。

platform 码表（v1 新增登记，module 局部常量，放 `entity/project/CloudConfigItem.kt`）：

| Code | 名称 |
|------|------|
| 0 | ALL（全部平台，**默认值**；此列显式例外于「0 保留不用」惯例） |
| 10 | IOS |
| 20 | ANDROID |
| 30 | WEB |

与 `entity/common` 的 `Platforms`（10=APPLE, 20=GOOGLE，商店主体）**不是一回事**，不要混用。

## 4. 装配规则

请求带 `keys`（其余取自 meta）：`projectId` / `clientPlatform` / `otaVersion`。

```
缓存载入（粒度 project + platform + otaVersion，见 §7）：
    行 = filter(DB 行, project=pid, enabled=true, platform IN (0, 请求平台))
       → ota 门槛过滤（min/max，逐段数值比较）         ← 进缓存前完成
返回装配（每次请求内存做，纯函数）：
    生效值 = 同 key 合并：platform≠0 行覆盖 platform=0 行
           → 只取 keys 要求的项（缺失/未知 key 不出现、不报错）
    configVersion = 本次返回条目 max(updated_at) epoch millis 十进制字符串；空 → "0"
```

- **ota 版本门槛**：`min_ota_version` / `max_ota_version` 逐段数值比较（`.` 与 `-` 都作分隔，
  兼容 otaVersion 形如 `1-23-3`；缺失段补 0，段内取前导数字、非数字段按 0）。
  `otaVersion` 缺失（meta 没带）→ 缓存里只保留无门槛行（保守：门槛行代表「新行为」，
  没有版本证据就不下发）。
- **configVersion**：只统计**本次返回**的条目——客户端请求的 keys 固定时，version 只随这些
  key 变化，作缓存/重算判断不误报。客户端当 opaque 版本号用（日志对比 / 本地缓存失效判断），
  不解析含义。
- 请求的 key 无一生效 → 返回 `{ configVersion: "0", values: {} }`，不报错（冷启动必须成功）。

## 5. API 契约

```graphql
"云配置拉取结果。请求的 key 无一生效时返回 { configVersion: \"0\", values: {} }，不报错"
type CloudConfigResult {
    "配置版本：本次返回条目最大 updatedAt 的 epoch millis 十进制字符串；无返回条目为 \"0\"。opaque，仅作对比/日志"
    configVersion: String!
    "生效配置：仅含请求的 key。单平台行覆盖同 key 全平台行；ota 版本门槛不满足的行不出现在此"
    values: JSON!
}

extend type Query {
    "拉取云配置。keys 必传，只返回要求的 key（缺失/被门槛过滤的 key 不出现、不报错）。冷启动可用（不要求登录）；projectId/clientPlatform/otaVersion 取自 meta"
    q_project_cloudConfig_get(keys: [String!]!): CloudConfigResult!
}
```

- reqName：`q_project_cloudConfig_get`（四段式：module=project, resource=cloudConfig, action=get）。
- **匿名可用**：`fromDfe(dfe, requireActorType = null)`；带 token 也校验（过期/无效照常抛），
  但不要求存在。token 是否有效不影响下发内容（v1 无 per-user 配置）。
- 前端 persisted query：`customer.json` 已加一条（等前端 build 生成后以其为准）。

### 客户端约定

- **key 清单与 value 契约（前端用）见 [cloud-config-keys](cloud-config-keys.md)**——当前支持哪些
  key、语义、类型、客户端内置默认、新增 key 流程都在那里维护；本文档只讲机制。
- 冷启动拉一次：把**当前运行时会消费的 key 集合**作为 `keys` 传入；`values` 与内置默认合并
  （服务端值优先）。拉取失败/超时走内置默认，**永远不阻塞启动**。
- 客户端升级（新 OTA）想消费新 key：直接把新 key 加进 `keys` 即可，服务端无注册步骤；
  旧 OTA 不请求新 key，天然隔离。
- 按 `configVersion` 变化决定是否重算依赖配置的逻辑；value 结构变更靠 key 演进
  （如 `fooV2`），服务端不做 value schema 校验。

## 6. 运维手册（SQL 直改）

无 admin 界面；改完即时生效上限 = 缓存 TTL（5 分钟）。所有写操作都应更新 `updated_at`
（`now()`）并写 `note`，否则 configVersion 不前进、客户端缓存判断失真。

```sql
-- 新增/覆盖一个全平台开关（upsert：同 (project_id, key, platform) 唯一）
INSERT INTO core_project_cloudconfigitem
    (id, project_id, key, value, platform, enabled, note, created_at, updated_at)
VALUES (gen_random_uuid(), 'ifmix', 'scanPushEnabled', 'false'::jsonb, 0, true,
        '首发默认关', now(), now())
ON CONFLICT (project_id, key, platform)
DO UPDATE SET value = EXCLUDED.value, enabled = true, note = EXCLUDED.note, updated_at = now();

-- 单平台覆盖（ios 单独开）
INSERT INTO core_project_cloudconfigitem
    (id, project_id, key, value, platform, enabled, note, created_at, updated_at)
VALUES (gen_random_uuid(), 'ifmix', 'scanPushEnabled', 'true'::jsonb, 10, true,
        'ios 先行', now(), now())
ON CONFLICT (project_id, key, platform)
DO UPDATE SET value = EXCLUDED.value, enabled = true, note = EXCLUDED.note, updated_at = now();

-- 新版本门槛（仅 OTA ≥ 1-23-0 下发）
UPDATE core_project_cloudconfigitem
SET min_ota_version = '1-23-0', note = note || E'\n2026-10-24 设门槛', updated_at = now()
WHERE project_id = 'ifmix' AND key = 'scanPushEnabled' AND platform = 0;

-- 运营关停（保留现场，不删行）
UPDATE core_project_cloudconfigitem
SET value = 'false'::jsonb, note = note || E'\n2026-10-24 关停', updated_at = now()
WHERE project_id = 'ifmix' AND key = 'scanPushEnabled';

-- 停用某行（不下发，等效于删除）
UPDATE core_project_cloudconfigitem SET enabled = false, updated_at = now()
WHERE project_id = 'ifmix' AND key = 'xxx';

-- 查看当前生效配置（某平台视角）
SELECT key, value, platform, min_ota_version, max_ota_version, updated_at
FROM core_project_cloudconfigitem
WHERE project_id = 'ifmix' AND enabled = true AND platform IN (0, 10)
ORDER BY key;
```

## 7. 缓存

- **粒度 `(projectId, platform, otaVersion)`**：key `cloudconfig:{projectId}:{platform}:{otaVersion}`
  （otaVersion 缺失用 `_` 占位）。value = `List<RowSnapshot>`（key/value 原文/platform/ota 门槛/
  updatedAtMillis 的纯 data class，不缓存 Jimmer entity——动态代理不适合走 Jackson）。
  **ota 门槛过滤在 loader 里完成后才进缓存**——同一 (project, platform, otaVersion) 的门槛
  结果相同，门槛结果可安全入缓存。
- **keys 不参与缓存**：缓存里是门槛过滤后的全量生效行，keys 只在返回装配（纯函数）时过滤。
  同一份缓存服务所有 keys 组合，客户端加 key 不产生缓存抖动。
- TTL：CacheAside 默认 5 分钟；otaVersion 空间 = runtime × build × otaSeq 的活跃集合，有限，
  过期条目靠 TTL 自然淘汰。
- 版本门槛之后的一切（平台覆盖、keys 过滤、configVersion、value 解析）在每次请求内存做
  （行数小，微秒级）。
- 写路径（SQL 直改）没有 evict 挂点 → **接受 ≤5 分钟陈旧**（§6 已声明）。未来上 admin
  mutation 时在写路径 evict。

## 8. 分层落点

| 层 | 文件 | 内容 |
|----|------|------|
| Schema | `schema/customer/project.graphqls` | `CloudConfigResult` + `q_project_cloudConfig_get(keys)` |
| DataFetcher | `bff/graphql/customer/project/ProjectFetcher.kt` | 匿名可读（requireActorType=null），无 GlobalTx（纯读），keys 透传 |
| Facade | `modules/project/CloudConfigItemFacade.kt` | 构造 ModuleCtx 转发 |
| Handler | `modules/project/handler/CloudConfigItemAggHandler.kt` | 缓存载入（含 ota 门槛过滤）+ `assemble`（平台覆盖/keys 过滤/configVersion，纯函数可单测） |
| Repo | `modules/project/repo/CloudConfigItemRepository.kt` | `findEnabledRows(projectId, platform)`（`or(platform = 0, platform = x)`） |
| Entity | `entity/project/CloudConfigItem.kt` | `@Serialized val value: JsonNode`（任意 JSON），platform 码表常量 |
| 迁移 | `db/migration/V1__init.sql` | 建表 + 两个索引 |

## 9. 测试

- `CloudConfigVersionTest`：otaVersion 比较器（补 0 / 多段 / `1-23-3` 格式 / 非数字段）。
- `CloudConfigItemAggHandlerTest`：`assemble`（keys 过滤 / 平台覆盖 / configVersion 按返回条目 /
  value 解析）+ `matchesOtaGate`（min/max / otaVersion 缺失）。

## 10. 未来演进

1. **admin mutation**（manager BFF 落地后）：upsert/disable + 写路径 evict 缓存，替代 SQL 直改。
2. **per-user 灰度**：加 `rollout_percent` 列，服务端按稳定 id（customer id / install iid）hash 分桶；
   匿名冷启动请求先回退平台级值，登录后再拿用户级值。
3. **边缘缓存**：reqName 在 path 中，CF 可对 `/customer/core/greq/q_project_cloudConfig_get`
   做 edge cache（需先解决 wire 加密与 CF 缓存的兼容）。
4. **定向扩展**：country/locale 维度（meta 现成），列与合并顺序同 platform 模式。
5. **历史 / 快照（若真出现「取特定版本」需求再做）**：三方案比较——
   - **整包 revision**（照抄 configrevision：每改一次复制整个配置 JSON 成新行）：审计/回滚/快照
     天然完整，但与本设计根本冲突——per-key 行结构（platform/ota 门槛定向、SQL 单 key upsert）
     会退化成大 JSON 里的长嵌套，运营改一个 key 要 read-modify-write 整包，易错。configrevision
     的场景是 IAP 凭据类低频整包人工维护，不适合高频运营开关。**不选**。
   - **同表多版本行**：key 不再唯一，要引入 active/version 维度，唯一索引、装配、SQL 手册全部
     复杂化，且当前没有消费方。**不选**。
   - **独立 history 表**（`core_project_cloudconfigitemhistory`，append-only，写路径 additive）：
     读路径完全不动；后续上 admin mutation 时顺带双写即可（SQL 直改期可用 PG 触发器兜底）。
     **选定路径**——出现排查「用户当时看到什么配置」的需求时再建。

### 附：为什么门槛用完整 otaVersion，不用 native appVersion / `runtime-build`

- 行为在 JS 层，发布单位是 OTA bundle；`otaSeq` 是最细发布粒度（`1-23-1` 关、`1-23-2` 开），
  native 维度做不到。
- `runtimeVersion-buildNumber-otaSeq` 三段全数字，数值单调性由 buildNumber 段主导（otaSeq 只在
  同 build 内递增），逐段数值比较语义正确；`runtimeVersion` 升位（1→2）天然大于所有旧段，方向
  正确。若未来 runtimeVersion 政策改为非数字（如 Expo fingerprint），leadingNumber 会把该段按 0
  处理——届时比较器需随政策一起调整（文档在此挂账）。
- 比较只发生在缓存 loader（miss 时），命中路径零比较开销，性能不是考量维度。
- 拆成 runtime/build/seq 三个门槛列只在「SQL 端过滤门槛」时有收益，而门槛过滤在内存/缓存层
  已解决；3 组 min/max 列让 SQL 运维手册翻倍。**不拆**。
