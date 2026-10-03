# Push Feature Flag（Firebase Remote Config）设计文档

- 日期：2026-10-03
- 模块：`core-api` / `infra/push`（Remote Config 读取）、`modules/ai`（业务方消费 flag）
- 相关前端：`/Users/jason/ai/myprojects/antique`
- 依赖：`2026-10-03-scan-async-notification-push-design.md`（FcmConfig/FirebaseApp、notification 模块）、`2026-10-03-deep-research-push-notification-design.md`（DeepResearch push）

## 1. 背景与目标

两个 push 功能（scan result、deep research）需要**项目级运营开关**，可远程配置开/关，用于一键关停或**未来灰度**（按版本/百分比/地区）。因后续确有灰度需求，采用 **Firebase Remote Config**（而非自写 config——灰度等于重造 Remote Config 的条件化下发）。

## 2. 三层控制模型

实际是否发某个 push，由三层 AND 决定：

```
实际发 push =
    remote_config_flag_on   （项目级运营开关，Remote Config，未来可灰度）
AND user_noti_enabled       （设备级用户开关，install 字段，用户设置）
AND [业务既有条件]           （如 deep research 仅 finalized=true）
```

- **项目级 flag**：`push_scan_result_enabled` / `push_deep_research_enabled`（Remote Config 布尔）。
- **设备级开关**：`scan_result_noti_enabled` / `deep_research_noti_enabled`（已在 install，依赖文档定义）。
- 两者正交：flag 控制整个功能是否对外提供（运营/灰度），user 开关是个人选择。

## 3. 关键设计决策（已确认）

| # | 决策 | 选择 |
|---|------|------|
| 1 | 配置源 | **Firebase Remote Config**（复用 FCM 的 FirebaseApp/凭据），因未来要灰度 |
| 2 | 一致性 | **最终一致可接受**（不要求严格立即全停）→ 后端带本地缓存拉取 |
| 3 | flag 判断位置 | **业务方**（ScanTaskService / DeepResearchTaskService）判断，不放通用 notification 模块（flag 决策未来会跟用户/灰度/其他维度相关，属业务策略） |
| 4 | flag 键名 | `push_scan_result_enabled` / `push_deep_research_enabled` |
| 5 | 前端 | 启动/切前台拉一次 Remote Config → 决定 UI（是否显示开关、是否请求权限、是否引导设置 user 开关） |
| 6 | 默认值 | flag 缺失/拉取失败时的 fallback（见 §4.3），建议**默认 false**（新功能未显式开启则不发），避免配置缺失误发 |

## 4. 后端设计

### 4.1 Remote Config 读取（`infra/push/`）

- **复用 FcmConfig 的 FirebaseApp**（scan push 设计已初始化）。新增 `infra/push/RemoteConfigService.kt`（`@Component`）封装 Server-side Remote Config 读取。
- 用 Firebase Admin SDK 的 server template：`FirebaseRemoteConfig.getServerTemplate()` → `evaluate()` → 取布尔参数。
- **本地缓存**（最终一致，决策 2）：拉取结果缓存 `app.noti.remote-config.cache-ttl`（默认如 10min），过期后重新拉。缓存失败/过期拉取失败时沿用上次值或 fallback（§4.3）。
- 接口：`isEnabled(flagKey: String): Boolean`。

### 4.2 业务方消费（决策 3）

- `ScanTaskService` 发 scan push 前：`if (!remoteConfig.isEnabled("push_scan_result_enabled")) return`（跳过 push，不影响 scan 落库）。
- `DeepResearchTaskService` 发 push 前：`if (!remoteConfig.isEnabled("push_deep_research_enabled")) return`（在 finalized=true 分支内、调 notificationFacade 之前）。
- flag 判断在**业务方**，notification 模块只管 user 开关 + 寻址发送（保持中立）。

### 4.3 fallback / 容错

- flag 不存在 / Remote Config 拉取失败 / 无缓存：返回 **false**（默认不发，决策 6）——配置缺失不误发，运营需显式开启。
- `app.noti.fcm.enabled=false`（local/test，依赖文档）时：RemoteConfigService 走 no-op，`isEnabled` 恒返回**本地配置默认值**（见 §4.4），不实际连 Firebase。

### 4.4 配置

```yaml
app:
  noti:
    remote-config:
      cache-ttl: 10m          # 后端缓存 TTL（最终一致）
      # local/test fcm.enabled=false 时的本地默认（不连 Firebase）：
      local-defaults:
        push_scan_result_enabled: true   # 本地开发默认开，方便测
        push_deep_research_enabled: true
```

## 5. 前端设计（供前端 agent review）

- **启动 + 切前台**各拉一次 Remote Config（客户端 SDK，默认缓存/节流即可，无需对抗——最终一致）。
- 两个 flag 决定 UI：
  - flag off → **不显示**对应"结果通知"开关、不请求通知权限、不引导设置 user 开关。
  - flag on → 正常走依赖文档的授权/开关交互。
- 前端 flag 与后端 flag 读同一个 Remote Config，天然一致（最终一致，容忍短暂拉取差）。
- **前端不把 flag 作为 createScan/DeepResearch 参数传后端**——后端自己读（决策 3，权威在后端侧 Remote Config，避免客户端传参被篡改且后端 push 是异步后台发的）。

## 6. 分层落点

| 层 | 文件 | 改动 |
|----|------|------|
| Infra | **新增** `infra/push/RemoteConfigService.kt`（`@Component`） | 封装 Server-side Remote Config 读取 + 本地缓存 + fallback；复用 FcmConfig 的 FirebaseApp |
| Infra | `infra/push/FcmConfig.kt` | 无需改（FirebaseApp 已初始化，RemoteConfigService 注入复用） |
| Service | `modules/ai/ScanTaskService.kt` | 发 push 前 `isEnabled("push_scan_result_enabled")` 门控 |
| Service | `modules/ai/DeepResearchTaskService.kt` | finalized=true 分支内、发 push 前 `isEnabled("push_deep_research_enabled")` 门控 |
| 配置 | `application*.yml` | `app.noti.remote-config.*`（cache-ttl + local-defaults） |
| 依赖 | `core-api/build.gradle.kts` | firebase-admin 已含 Remote Config（无需额外依赖，确认 SDK 版本含 server template API） |

## 7. 错误处理与边界

| 场景 | 处理 |
|------|------|
| flag off | 业务方跳过 push，不影响 scan/DeepResearch 落库 |
| Remote Config 拉取失败/无缓存 | isEnabled 返回 false（默认不发，§4.3） |
| flag 不存在 | 同上，返回 false |
| local/test（fcm.enabled=false） | 走 local-defaults，不连 Firebase |
| 缓存未过期 | 用缓存值，不实时拉（最终一致，决策 2） |

## 8. 测试计划（非框架、最小可运行校验）

1. **flag off 跳过**：push_scan_result_enabled=false → ScanTaskService 不调 notificationFacade；scan 仍 SUCCESS。
2. **flag on 正常**：true → 走原发送流程（配合 user 开关）。
3. **三层 AND**：flag on + user 开关 off → 不发；flag off + user 开关 on → 不发；两者 on → 发。
4. **fallback**：RemoteConfigService 拉取抛异常 → isEnabled 返回 false（假实现验证）。
5. **缓存**：TTL 内多次 isEnabled 只拉取一次 Remote Config（mock server template 验证调用次数）。
6. **DeepResearch flag 独立**：push_deep_research_enabled 与 scan flag 独立生效。
7. **local no-op**：fcm.enabled=false → isEnabled 返回 local-defaults 值，不连 Firebase。

## 9. 实现期验证点

1. Firebase Admin SDK 的 Server-side Remote Config API（`getServerTemplate`/`evaluate`）在项目所用 SDK 版本是否可用；若版本过低需升级或改用 REST。
2. RemoteConfigService 缓存实现（简单 `AtomicReference<CachedValue>` + TTL，或复用项目既有缓存机制）；并发拉取去重。
3. 前端 Remote Config 客户端 SDK 的接入（Antique 是否已有 Firebase RC 客户端，或需新增）——需前端确认。
4. local-defaults 与 production 必须显式配置的边界：production fcm.enabled=true 但 Remote Config 不可达时的降级（建议 false，不误发）。
