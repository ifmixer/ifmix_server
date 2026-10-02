# Release

core-api 发布版本记录（倒序）。版本号即 git tag；「线上」列标当前生产实际运行的版本。

| 版本 | tag commit | 日期 | 线上 | 说明 |
|------|-----------|------|:----:|------|
| 未发布 | `feature/install` | — |  | install 体系（见下） |
| v1.0.3 | `2a3bbf0` | 2026-09-21 | ✅ | 当前线上版本。**不支持 install**（无 `m_install_*`、token 无 `iid`/`type` claim、`install_id` 为客户端 `x-install-id` 原值） |
| v1.0.2 | `8743e80` | 2026-09-21 |  | R2 objectKey 路径改 `/p/` |

> `v1.1.1`（`3a68f59`，2026-09-20）早于 v1.0.2，疑似误打的 tag，不计入版本序列。

## 未发布（`feature/install`）

相对 v1.0.3 的主要变化，发布前逐项确认：

- **install 体系**：`m_install_createInstall` / `m_install_updateInstall`；token 增加 `type`（5=install / 10=customer）与 `iid` claim；`core_install` + `core_install_customer_relation`（V5/V6）。
- **老 app 兼容**：`app.auth.legacy-install-id-fallback`（默认 `true`）——token 无 `iid` 时 createAnonymous / login / refresh / 写入退回 `x-install-id` header。老 app 全部升级后关闭。
- **扫描计数迁表**：`core_customer.scan_count / deep_research_count` → `core_ai_customer_scan_metrics`（V9，旧列暂留，发布完成后另起迁移删除）。
- **AI key 池**：轮询 + Redis 分布式冷却；`core_ai_agnes_key` → `core_ai_api_key`（V8）。
- **错误透出**：线上（`app.expose-errors=false`）5xx 只返回通用文案。
- **日志**：MDC 上下文 `[rid pid iid cid ip bot plat av ov loc cur cty]`；请求头/响应头 `x-req-id`；`key=value` 格式、耗时统一 `duration=Nms`。

发布注意：
- **DB 迁移顺序**：先 `flywayMigrate`（V4–V9）再发新代码；V6 改 `install_id` 列类型（text→uuid），旧代码在迁移后写入会失败，需短暂停机或先停旧实例。
- `AUTH_JWT_PRIVATE_KEY` 必须配置（缺失启动即失败）。
