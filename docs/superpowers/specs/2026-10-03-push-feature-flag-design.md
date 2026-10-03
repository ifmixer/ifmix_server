# Push Feature Flag（前端 hard code + mutation 传参）设计文档

- 日期：2026-10-03
- 模块：`core-api` / `modules/ai`（flag 随 mutation 入参进 ctx、发 push 前判断）
- 相关前端：`/Users/jason/ai/myprojects/antique`
- 依赖：`2026-10-03-scan-async-notification-push-design.md`、`2026-10-03-deep-research-push-notification-design.md`
- 取代：本文件此前的 "Firebase Remote Config" 方案（后端读 Server-side RC）——改为更简单的前端 hard code + mutation 传参

## 1. 背景与目标

两个 push 功能（scan result、deep research）需要可控的开关，用于关停或灰度。**本期最简方案**：flag 在**前端 hard code**（布尔常量），通过 **Expo OTA** 热更新即可开关；前端在 createScan / runDeepResearch 时把 flag 作为 mutation 参数传给后端，后端据此决定发不发 push。

**不引入后端 Firebase Remote Config**（原方案的 Server-side RC + 缓存全部去掉）。Firebase Remote Config 作为**前端**未来的切换点：以后要动态/灰度时，前端 OTA 一版把 hard code 常量换成读 RC，**后端传参链路不变**。

### 1.1 能力边界（必须说清——这不是后端 kill switch）

本期开关是 **"当前前端 JS bundle 对新建任务的 push enrollment 标记"**，**不是**后端权威的运营即时关停开关。具体地，OTA 里把常量改成 `false` **不能**立即/追溯阻断以下情况的 push：
- **已创建但未完成的任务**：后端已在 TaskContext 快照了创建时的旧 flag 值；
- **未拉到该 OTA 的离线用户**；
- **未重启到新 JS bundle 的用户**（旧 runtime）；
- **被修改的客户端**主动传 `featureFlags.*PushEnabled=true` 的请求。

它只影响"安装了该 OTA 的客户端**新发起**的任务是否请求 push"。这不是安全漏洞——用户最多影响自己发起任务是否收到通知；但它**不能承担运营安全阀/可信灰度/按用户规则控制**的职责。如未来需要这些能力，再增加**后端权威配置源**（如 Server-side Remote Config）。

## 2. 方案与控制模型

```
实际发 push =
    featureFlag_from_request   （前端 hard code，随 mutation 传入，存进任务 ctx）
AND user_noti_enabled          （设备级用户开关，install 字段）
AND [业务既有条件]              （如 deep research 仅 finalized=true）
```

- **featureFlag**：前端 hard code 的布尔，createScan/runDeepResearch 时作为 input 传入 → 后端存进 ScanTaskContext / DeepResearchTaskContext → 后台发 push 前判断。
- **per-request 语义 + 接受衰减**：flag 只影响**本次发起**的任务。flag 关闭（OTA 一版）后，新发起的任务不发 push；已创建的任务用创建时的 flag 值；**不追溯已开 user 开关的存量用户**（接受自然衰减）。
- user 开关、业务条件不变（依赖文档定义）。

## 3. 关键设计决策（已确认）

| # | 决策 | 选择 |
|---|------|------|
| 1 | flag 载体 | 前端 **hard code 布尔常量**，Expo OTA 更新 |
| 2 | 传递方式 | 随 createScan / runDeepResearch 的 **mutation 入参**传入，存进任务 ctx |
| 3 | 后端是否读 RC | **否**，后端完全不碰 Remote Config，只认 mutation 传入的 flag |
| 4 | input 类型 | **各 mutation 独立** FeatureFlags input（避免共用类型让前端面对无关字段） |
| 5 | 缺省/旧客户端 | flag null/缺省 → **false**（不发，防旧客户端不传时误发） |
| 6 | 存量用户 | **接受衰减**（per-request，不追溯） |
| 7 | Firebase RC | 作为**前端**未来切换点（OTA 换 hard code→读 RC）；后端这次不引入 RC 依赖 |

## 4. 后端设计

### 4.1 GraphQL input（各自独立）

```graphql
input ScanFeatureFlagsInput {
  "scan 结果 push 是否启用（前端 hard code 当前值）；省略/false 则后端不发 scan push"
  scanResultPushEnabled: Boolean
}
input NewScanInput {
  # ... 现有字段 ...
  featureFlags: ScanFeatureFlagsInput
}

input DeepResearchFeatureFlagsInput {
  "deep research push 是否启用（前端 hard code 当前值）；省略/false 则后端不发"
  deepResearchPushEnabled: Boolean
}
input RunDeepResearchInput {
  # ... 现有字段 ...
  featureFlags: DeepResearchFeatureFlagsInput
}
```

### 4.2 ctx 透传

- `ScanTaskContext` 加 `scanResultPushEnabled: Boolean`（createScanTask 从 input.featureFlags?.scanResultPushEnabled ?: false 取）。
- `DeepResearchTaskContext` 加 `deepResearchPushEnabled: Boolean`（createDeepResearchTask 同理）。
- 默认 false（决策 5）：input.featureFlags 为 null 或字段为 null → false。

### 4.3 发 push 前判断（业务方）

**用 `if` 包裹发送，不要用 `if (!flag) return` 跳出整个成功流程**（前端 agent #5）——否则日后在发 push 后新增的成功日志/指标/清理逻辑会被一起跳过。gate 只应包住"发 push"这一件事。

- `ScanTaskService.doRun` 成功回写（`finalized=true`）后：
  ```kotlin
  if (ctx.scanResultPushEnabled) {
      scanAggHandler.buildScanNotificationRequest(ctx, basicResult)?.let(notificationFacade::sendToInstall)
  }
  // 此处之后若有其它成功后逻辑，不受 flag 影响
  ```
- `DeepResearchTaskService.doRun` 的 `finalized=true` 分支内：
  ```kotlin
  if (ctx.deepResearchPushEnabled) {
      // 组装 NotificationRequest 并 sendToInstall
  }
  ```
- flag 判断在**业务方**（与依赖文档一致，notification 模块保持中立）。

## 5. 前端设计（供前端 agent review）

### 5.1 hard code 常量（含首发值，前端 agent #2）

`features/push/flags.ts`，**首发默认 false**，验证完整链路后再 OTA 改 true（这是本方案唯一真正可控的渐进发布方式）：
```ts
export const SCAN_RESULT_PUSH_ENABLED = false;
export const DEEP_RESEARCH_PUSH_ENABLED = false;
```
- createScan / runDeepResearch 调用时，把对应常量放进 `featureFlags` input 传后端。

### 5.2 全前端入口都受同一 flag gate（前端 agent #3，不可绕过）

当前存在多个 push/权限路径，**每一处都必须受 flag gate**，不能有旁路：

| 入口 | gate 条件 |
|------|-----------|
| scan 发起（开关可见/触发授权/弹通知说明） | `SCAN_RESULT_PUSH_ENABLED === true` |
| DeepResearch 发起（同上） | `DEEP_RESEARCH_PUSH_ENABLED === true` |
| 设置页 scan 通知开关可见 | `SCAN_RESULT_PUSH_ENABLED === true` |
| 设置页 DeepResearch 通知开关可见 | `DEEP_RESEARCH_PUSH_ENABLED === true` |
| `usePushRegistration` 的 Superwall ACTIVE 边沿 | 不得绕过——scan flag=false 时不弹 scan 通知说明/不请求系统授权 |
| 系统注册（token/FID/install topic 上报） | **至少一个** push flag 开启 **且** 用户实际允许对应功能 |

- **不复制两套 token/FID/topic 注册流程**：两功能**共享**设备级系统授权与 FCM 注册，仅 Install 的 user preference（`scan_result_noti_enabled`/`deep_research_noti_enabled`）分开。
- flag off → 对应功能整体不对用户暴露（不显示开关、不请求权限、不引导设置 user 开关）。

### 5.3 Firebase Remote Config：本期不接入（前端 agent #4）

`@react-native-firebase/remote-config` 虽已在 package.json，但本期**不 import、不初始化、不 fetch、不缓存**；后端**不**新增任何 Firebase Admin Remote Config 依赖/配置/服务。现在做"SDK 占位"无运行价值、反而混淆当前权威来源（当前权威就是 hard code 常量）。未来切换时 OTA 把常量读取改为 RC store 即可。

### 5.4 交付

- `_API_ENTRIES` / schema 类型 / persisted-query：createScan、runDeepResearch 的 input 增加 `featureFlags`。

## 6. 分层落点

| 层 | 文件 | 改动 |
|----|------|------|
| Schema | `schema/customer/ai.graphqls` | 加 `ScanFeatureFlagsInput` / `DeepResearchFeatureFlagsInput`；`NewScanInput` / `RunDeepResearchInput` 各加 `featureFlags` |
| DTO | `dto/ai/ScanTaskContext.kt` | 加 `scanResultPushEnabled: Boolean` |
| DTO | `dto/ai/DeepResearchTaskContext.kt` | 加 `deepResearchPushEnabled: Boolean` |
| Handler | `modules/ai/handler/ScanAggHandler.kt` | `createScanTask` 从 input.featureFlags 取 flag 填 ctx（默认 false） |
| Handler | `modules/ai/handler/ScanAggHandler.kt` | `createDeepResearchTask` 同理填 DeepResearchTaskContext |
| Service | `modules/ai/ScanTaskService.kt` | 发 push 前 `if (!ctx.scanResultPushEnabled) return` |
| Service | `modules/ai/DeepResearchTaskService.kt` | finalized=true 分支内发 push 前 `if (!ctx.deepResearchPushEnabled) return` |

> 后端**无** RemoteConfigService、无 `app.noti.remote-config.*` 配置、无 firebase-admin Remote Config 用法（相对原 RC 方案全部删除/不新增）。

## 7. GraphQL 交付清单

- 服务端：ai.graphqls 加两个 input 类型 + featureFlags 字段；customer.json 更新 createScan/runDeepResearch 入参；DGS codegen。
- 前端：`_API_ENTRIES` 的 createScan/runDeepResearch 加 featureFlags；重新生成 schema 类型 + persisted-query，1:1 对齐。

## 8. 错误处理与边界

| 场景 | 处理 |
|------|------|
| featureFlags 省略 / flag null | 视为 false，不发 push（决策 5，防旧客户端误发） |
| flag=false | 业务方跳过 push，不影响 scan/DeepResearch 落库 |
| flag=true + user 开关 off | 不发（三层 AND） |
| flag=true + user 开关 on + 业务条件满足 | 发 |
| flag 关闭后的存量已开用户 | 新任务不发；旧任务按创建时 flag；不追溯（接受衰减，决策 6） |

## 9. 测试计划（非框架、最小可运行校验）

1. **flag 透传**：createScanTask 从 input.featureFlags.scanResultPushEnabled 填入 ctx；省略 → ctx 为 false。
2. **flag off 跳过**：ctx.scanResultPushEnabled=false → ScanTaskService 不调 notificationFacade；scan 仍 SUCCESS。
3. **flag on 正常**：true + user 开关 on → 调 notificationFacade。
4. **三层 AND**：flag on + user off → 不发；flag off + user on → 不发。
5. **DeepResearch flag 独立**：deepResearchPushEnabled 与 scan flag 各自生效，不串。
6. **旧客户端兼容**：input 无 featureFlags → 默认 false，不发（不报错）。

## 10. 实现期验证点

1. 现有 createScan/runDeepResearch 的 input 加嵌套对象字段对 DGS codegen 的影响（可空嵌套 input）。
2. 本设计依赖前两份文档的 ScanTaskContext/DeepResearchTaskContext 已落地；在其基础上加字段。
3. 前端本期**纯 hard code，不接入 Firebase RC**（§5.3 已定）。
