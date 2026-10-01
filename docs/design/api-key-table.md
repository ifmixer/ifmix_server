# AI Key 表通用化：`core_ai_agnes_key` → `core_ai_api_key`

状态：**已完成（2026-10-01）**——Kotlin 改名 + V8 迁移（表改名 + `provider` 列 + 索引改名）+ 导入脚本切换均已落地。

## 背景

`core_ai_agnes_key` 是 Agnes 专用的 API key 表（infra 级全局资源）。key 池机制（加载、冷却、轮换，见 [ai-api-key-pool](ai-api-key-pool.md)）本身与 Agnes 无关，将来接其他 AI provider（自建代理、其他 OpenAI-compatible 服务等）需要同一张表承载多来源的 key。

方案：表改名为通用的 `core_ai_api_key`，新增 `provider` 列区分来源。**改动不大**：1 个迁移 + 1 个导入脚本 + 若干文档（Kotlin 侧改名已完成），全部机械性改动，无业务逻辑变化，无 GraphQL/客户端影响（gitnexus：`AgnesKey` 上游影响 13 点，MEDIUM，全部收敛在 ai 模块内）。

## 目标结构

```sql
ALTER TABLE public.core_ai_agnes_key RENAME TO core_ai_api_key;
ALTER TABLE public.core_ai_api_key
    ADD COLUMN provider smallint DEFAULT 10 NOT NULL;   -- 码表 ApiProviders，10=AGNES
ALTER INDEX public.agnes_key_uq RENAME TO api_key_uq;   -- key 唯一索引同步改名
```

- 表名保持 `core_` 前缀（V2 已把建表时的 `ai_agnes_key` 改名为 `core_ai_agnes_key`，沿用该惯例）。
- `provider` 用 `smallint` 对齐同表 `type` 列的既有惯例（Kotlin 侧照旧 `Int`）。
- 码表：`object ApiProviders { const val AGNES = 10 }`，放 `entity/ai/` **独立文件**（Jimmer KSP 坑：`object` 与 `@Entity` 同文件会静默跳过实体代码生成）。
- 实体 `AiApiKey`（已从 `AgnesKey` 改名，2026-10-01），仍继承 `BaseEntity`（全局级别不变）；`@Table` 暂指向 `core_ai_agnes_key`，V8 上线后改为 `core_ai_api_key`。
- `type` 列（10=PERSONAL / 20=ENTERPRISE）语义不变——它是 agnes key 自身的类型，与 `provider` 正交。
- DTO：`AiApiKeyStore.AiApiKeyDoc`（已随 Kotlin 改名完成）。

## V8 迁移，不动 V1

- V1 建表 + **3169 条 INSERT 数据**，且已应用到现有库（checksum）。编辑 V1 会导致 checksum 漂移且数据无法迁移。
- V8 `RENAME` 对已有环境是原地改名，数据自然跟过去；新环境走 V1（旧名建表+插入）→ V8（改名），两条路径终态一致。
- V8 里 `provider DEFAULT 10`：存量行全部落为 AGNES，无需 backfill UPDATE。

## 触点清单

### Kotlin（2026-10-01 完成，随去 agnes 化）

| 文件 | 改动 |
|---|---|
| `entity/ai/AgnesKey.kt` → `AiApiKey.kt` | 类改名；码表 `AgnesKeyTypes` → `AiApiKeyTypes`；新增 `provider: Int` 与 `entity/ai/ApiProviders.kt`（独立文件，避开 KSP 坑）；`@Table` 对齐 `core_ai_api_key` |
| `AgnesKeyRepository.kt` → `AiApiKeyRepository.kt` | 改名；删除死代码 `findAvailable`/`markUnavailable`（冷却语义由 ai-api-key-pool 方案的 Redis 承担；`unavailable_until` 列 deprecated，后续迁移再删） |
| `AgnesKeyStore.kt` → `AiApiKeyStore.kt`（含 `AgnesKeyDoc` → `AiApiKeyDoc`） | 改名 |
| `AgnesChatClientFactory.kt` → `AiChatClientFactory.kt` | 改名 |
| `AiConfig.kt` / `SpringAiScanRunner.kt` / `ScanPrompt.kt` | 引用与配置键同步；`loadKeys` 按 `provider = AGNES` 过滤 |
| 配置 | `app.agnes.*` → `app.ai.*`（`model-fallback-order`/`prompt-version`/`call-timeout-sec`），删除死配置 `scanKeyRateLimit` 与 `preDeductPerAttempt` |

### 数据库与脚本（2026-10-01 完成，V8）

| 项 | 改动 |
|---|---|
| `V8__rename_agnes_key.sql` | 表改名 + `provider smallint DEFAULT 10 NOT NULL` + 索引 `agnes_key_uq` → `api_key_uq` |
| `scripts/agnes_keys/import_from_register.sh` | 目标表 `core_ai_api_key`，INSERT 显式写 `provider=10`（原脚本仍写 V2 改名前的 `ai_agnes_key`，属潜伏 bug，一并修复） |
| 文档 | `ARCHITECTURE.md`、`DATABASE.md`、`agnes-key-import.md`、`ai-api-key-pool.md` |

> 遗留：主键索引名仍为 `agnes_key_pkey`（RENAME 不改约束名，纯命名问题，不影响功能，不值得单独迁移）。

## 注意点

1. **导入脚本与迁移的耦合**（已处理）：V8 与脚本同一次提交切换目标表；原脚本写的是 V2 改名前的 `ai_agnes_key`（潜伏 bug），一并修复。
2. **V1 不要动**（checksum + 已应用环境）。V8 对已有环境原地改名，数据自然迁移。
3. KSP 重新生成 props/draft/fetcher，编译器兜底漏改处；现有测试不触及该表，无需改测试。
4. 将来接新 provider 的差异（key 格式校验、导入配对规则）在导入脚本层处理，表结构不用再动；key-pool 的 Redis 前缀届时可加 provider 维度（如 `aikey:{cd}:{provider}:{keyId}`）。

## 关联

- key 池负载均衡与分布式冷却：[ai-api-key-pool](ai-api-key-pool.md)
- 导入与配对规则：[agnes-key-import](../scripts/agnes-key-import.md)
- 实体：`entity/ai/AiApiKey.kt`（`AiApiKeyTypes`：10=PERSONAL / 20=ENTERPRISE）
