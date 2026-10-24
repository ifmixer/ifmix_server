# Cloud Config keys 契约（前端 / App agent 用）

- 维护方：前端 + 后端共同维护（改 key 必改本文档）
- 机制与运维（表结构 / 缓存 / 门槛定向 / SQL 手册）见 [cloud-config.md](cloud-config.md)
- 状态：持续维护

## 这份文档是什么

登记云配置**当前支持的所有 key**：语义、value 类型、客户端内置默认。前端 agent 发起
`q_project_cloudConfig_get(keys: [...])` 时，keys 从这里取；消费逻辑与兜底默认也按这里实现。

> 注意区分两层：**本文档 = key 契约**（语义与类型，稳定真相源）；
> **DB 当前行 = 运行时状态**（当前值、可能带 platform / ota 门槛覆盖）。
> 语义以本文档为准，某个环境「现在实际生效什么」以 DB 为准（查询 SQL 见 cloud-config.md §6）。

## 拉取契约（详见 cloud-config.md §5）

- `values` 只含**请求了且生效**的 key：缺失 / 未请求 / 被 platform-ota 门槛过滤的 key 不出现；
- key 不出现 → 用**客户端内置默认**（下表「内置默认」列）；
- 拉取失败 / 超时 → **全部走内置默认**，绝不阻塞启动；
- `configVersion` 变化才重算依赖配置的逻辑。

## key 约定

- 命名 camelCase（如 `storeReviewMode`）；
- **语义或 value 类型变更 = 新 key**（如 `fooV2`），不原地改旧 key 语义（旧 OTA bundle 还在读旧语义）；
- 服务端不校验 value schema——value 结构由客户端按本表解释，结构演进靠新 key。

### 新增一个 key 的流程

1. 本文档「keys 清单」登记一行（语义 / 类型 / 取值 / 内置默认 / 消费方 / 引入版本）；
2. 服务端按 cloud-config.md §6 SQL 插行（`platform=0`、内置默认值、`note` 写引入原因）；
3. 前端：把 key 加进请求 `keys` + 实现消费逻辑 + 写内置默认兜底。

## keys 清单

| key | value 类型 | 语义 | 取值 | 内置默认（拉取失败/缺失时） | 消费方 | 引入 |
|-----|-----------|------|------|---------------------------|--------|------|
| `storeReviewMode` | Boolean | 当前是否处于 App Store 审核中。true = 审核模式：App 端应调整审核敏感行为（隐藏/替换不符合审核规则的功能入口等） | `true` / `false` | `false` | App 审核分支 | v1.0.8 |

### 运行时操作备注

- `storeReviewMode` **默认无 DB 行 = false**（不在审核中），客户端拉不到即走内置默认；
- 提审时插入一行置 true，过审后改回 false（SQL 模板见 cloud-config.md §6，project_id 按环境替换）：

```sql
-- 提审：打开审核模式
INSERT INTO core_project_cloudconfigitem
    (id, project_id, key, value, platform, enabled, note, created_at, updated_at)
VALUES (gen_random_uuid(), '<project_slug>', 'storeReviewMode', 'true'::jsonb, 0, true,
        '提审开启', now(), now())
ON CONFLICT (project_id, key, platform)
DO UPDATE SET value = EXCLUDED.value, enabled = true, note = EXCLUDED.note, updated_at = now();

-- 过审：关闭（保留行，改值）
UPDATE core_project_cloudconfigitem
SET value = 'false'::jsonb, note = note || E'\n过审关闭', updated_at = now()
WHERE project_id = '<project_slug>' AND key = 'storeReviewMode' AND platform = 0;
```

## 变更记录

以本文件的 git 历史为准；每个 key 在「引入」列标注版本，语义/类型变更必须另起新 key 并在此追加新行。
