# RPC 全量迁移计划 — 客户端（antique / client-sdk）：demo 之后的所有修改

> **给接手的 agent**：先读 `packages/client-sdk/AGENTS.md` → ifmix_server 仓库 `docs/design/proposals/graphql-to-http-rpc-openapi.md` §一（协议唯一真相源，含最新 URL/命名定稿）→ `rpc-pilot-client.md`（demo 试点，模式先例）→ 本文件。冲突时以 proposal §一 与本文件 §1 的 action 名表为准。
> - 仓库：`/Users/jason/ai/myprojects/antique`；继续在 `feature/graphql-to-rpc` 分支上按阶段提交，commit 前缀 `[rpc][R*]`。每阶段一个（或一组）commit。
> - 前置：demo 试点（C1–C5）已验收合并；服务端按 `rpc-pilot-server.md` / 后续模块规格文档并行实施，**每个 R 阶段开始前确认对应服务端模块已就绪（联调可用），否则该阶段挂起等待，不要先改后弃**。
> - **硬边界：gqlOp 路径与 trusted documents/codegen 全量保留到 R5；每迁移一个模块只是"新增 RPC 封装 + client 方法"，不改既有 gql 方法的行为；attest/auth/install/pay/noti 包的会话语义不动。**

## 0. 现状与总览

- demo 8 个 action 客户端已走 RPC（`rpc.ts`/`rpcDemo.ts`/`client.ts` `todo*Rpc` 方法），但用的是**旧命名**（`m_demo_createTodo`），本计划 R0 统一改为四段新命名。
- URL 已定稿：`POST /rpc/customer/core/{actionName}`，`actionName = {q|m}_{module}_{resource}_{action}`（proposal §一；module=resource 重叠允许，如 `m_install_install_*`）。
- 迁移模式与 pilot 完全一致：每 action 一条 `rpcOp` 封装 + `client.ts` 加 `*Rpc` 方法（走 `guarded()`）+ 手写类型 + mock fetch 测试；gql 版本保留到 R5。
- 服务端按模块出规格文档（Controller/DTO/测试），**action 名以本文件 §1 的表为单一真相**；两端冲突以本表为准并回改文档。

## 1. action 名总表（单一真相）

### 1.1 demo（R0 改名，客户端已实现）

| 旧名（已实现） | 新名 |
|---|---|
| q_demo_findTodoById | `q_demo_todo_getById` |
| q_demo_findTodosByIds | `q_demo_todo_getByIds` |
| q_demo_findTodos | `q_demo_todo_list` |
| m_demo_createTodo | `m_demo_todo_createOne` |
| m_demo_updateTodo | `m_demo_todo_updateOne` |
| m_demo_batchUpdateTodoItems | `m_demo_todo_updateItems` |
| m_demo_deleteTodo | `m_demo_todo_deleteOne` |
| m_demo_deleteTodoByIds | `m_demo_todo_deleteMany` |

### 1.2 其余 31 个 action（按 R1–R3 阶段迁移）

| 阶段 | 旧名（GraphQL） | 新名 |
|---|---|---|
| R1 | m_media_presignUpload | `m_media_media_presignUpload` |
| R1 | m_media_presignDownload | `m_media_media_presignDownload` |
| R1 | m_cs_submitFeedback | `m_cs_feedback_createOne` |
| R1 | m_cs_createSupportRequest | `m_cs_supportRequest_createOne` |
| R1 | q_cs_mySupportRequests | `q_cs_supportRequest_list` |
| R1 | q_cs_mySupportRequestById | `q_cs_supportRequest_getById` |
| R2 | m_customer_createAnonymousCustomer | `m_customer_customer_createAnonymous` |
| R2 | m_install_createInstall | `m_install_install_create` |
| R2 | m_install_updateInstall | `m_install_install_updateOne` |
| R2 | m_install_attestExisting | `m_install_install_attest` |
| R2 | m_install_recoverInstall | `m_install_install_recover` |
| R2 | m_install_createAttestChallenge | `m_install_install_createAttestChallenge` |
| R2 | m_auth_login | `m_auth_session_login` |
| R2 | m_auth_logout | `m_auth_session_logout` |
| R2 | m_auth_refreshToken | `m_auth_session_refresh` |
| R2 | q_auth_me | `q_auth_session_me` |
| R2 | m_auth_deleteAccount | `m_auth_account_deleteOne` |
| R3 | m_pay_verifyIapPurchase | `m_pay_iap_verify` |
| R3 | m_ai_createScan | `m_ai_scan_createOne` |
| R3 | m_ai_updateScan | `m_ai_scan_updateOne` |
| R3 | m_ai_deleteScan | `m_ai_scan_deleteOne` |
| R3 | m_ai_batchUpdateScan | `m_ai_scan_updateMany` |
| R3 | q_ai_findMyScans | `q_ai_scan_list` |
| R3 | q_ai_findMyScanById | `q_ai_scan_getById` |
| R3 | q_ai_getScanStatus | `q_ai_scan_getStatus` |
| R3 | m_ai_runDeepResearch | `m_ai_deepResearch_run` |
| R3 | q_ai_getDeepResearchStatus | `q_ai_deepResearch_getStatus` |
| R3 | q_ai_getDefaultCollection | `q_ai_collection_getDefault` |
| R3 | m_ai_addCollectionItem | `m_ai_collectionItem_add` |
| R3 | m_ai_removeCollectionItems | `m_ai_collectionItem_removeMany` |
| R3 | q_ai_findCollectionItemsByCursor | `q_ai_collectionItem_list` |

动词表约束：`getById / getByIds / list / createOne / createMany / updateOne / updateMany / deleteOne / deleteMany`，具体操作允许专名（`presignUpload / presignDownload / attest / recover / refresh / login / logout / me / createAnonymous / run / getStatus / getDefault / add / verify / updateItems`）；不兼容形状变更加 `V2` 后缀。

## 2. 阶段计划

### R0 demo 改名 + 试点联调收尾（第一个 gate）

1. `rpcDemo.ts` 内 8 个 reqName 字符串改为 §1.1 新名；函数名保持不变（函数名是客户端 API，不必跟 action 改名）。
2. `client.rpc.test.ts` 的 URL 断言、`rpc.test.ts` 如有涉及同步改。
3. 服务端 `rpc-pilot-server.md` 已按新名实现（S3/S4），本地联调跑通 pilot 的 E2E 清单：8 action 明文+加密、`q_demo_todo_list({findOptions:{limit:3}})` 验 gzip、400003 降级、差异表、wire 指标。
4. 输出试点联调报告，**停下等 review（gate 1）**。

### R1 media + cs（6 actions）

1. 新增 `rpcMedia.ts` / `rpcCs.ts`（模式照抄 `rpcDemo.ts`：手写类型对齐服务端模块 DTO）；`package.json` exports 追加 `./rpcMedia`、`./rpcCs`。
2. `client.ts` 追加方法（旧 gql 方法不动）：`storagePresignUploadRpc`、`storagePresignDownloadRpc`、`csSubmitFeedbackRpc`、`csCreateSupportRequestRpc`、`csMySupportRequestsRpc`、`csMySupportRequestByIdRpc`——入参/返回与 gql 版语义一致（枚举 Int、DateTime string）。
3. 测试：每 action URL+meta+input 断言一条、信封错误一条、（media）>4KB 响应 gzip 一条。
4. 验收：全量测试 + typecheck 绿；**gate：与服务端做模块级联调，差异表为空后进 R2**。

### R2 install + customer + auth 身份链路（8 actions）

⚠️ 本阶段动会话管线，规则最严格：

1. **`raw` 层切换**：`client.ts` 内 `raw.anonymous` / `raw.refresh` / login / logout / me / deleteAccount 的内部实现从 gqlOp 换为 rpcOp（新名见 §1.2），**对 session.ts 的输入输出契约一个字段都不许变**（`toTokens` 输入形状不变）。gql 版封装保留但不再被 raw 层调用（标记 `@deprecated 试点后删除`）。
2. 补 `makeInstallRpcOpts`（pilot 裁定 2 预留的 authorization 参数已就位）：`m_install_*` 与 anonymous 创建走 install-token 模式。
3. attest 三件套（createAttestChallenge / createInstall / attestExisting / recover）：走 `installCoordinator`/`installProof` 既有状态机，仅换传输；proof 子树照旧走 `redactForLog` 脱敏。
4. 测试重点：冷启动匿名引导链（createInstall → createAnonymous → 业务请求）、401002 refresh 重试、401003 → resetToAnonymous、install token 恢复链（recover/attestExisting 的 403001/403002/409001/404001 错误码语义与 gql 版逐一对齐）。
5. E2E：真机/模拟器走完整身份引导 + 登录 + refresh + logout + deleteAccount。**gate 2：身份链路联调报告**（这是风险最高的阶段，必须实测降级：key 未配时引导链全程明文可用）。

### R3 pay + ai（14 actions）

1. `rpcPay.ts` / `rpcAi.ts` + client 方法（`iapVerifyPurchaseRpc`、`scanCreateRpc`、`scanUpdateRpc`、`scanDeleteRpc`、`scanBatchUpdateRpc`、`scanFindByCursorRpc`、`scanFindByIdRpc`、`scanGetStatusRpc`、`deepResearchRunRpc`、`deepResearchGetStatusRpc`、`collectionGetDefaultRpc`、`collectionAddItemRpc`、`collectionRemoveItemsRpc`、`collectionListItemsRpc`）。
2. AI 模块红线（与服务端规格对齐，不得改变语义）：createScan/runDeepResearch 的配额/限流错误码（429000/429001/503000）映射到既有 `ApiError.isRateLimited/isQuotaExceeded`；大响应（DR 结果 >4KB）验证 gzip 分支；轮询类（getStatus）不加新重试机制。
3. pay：IAP verify 的 402000 映射不变；**webhook 路径与本项目无关，不碰**。
4. 验收：全量测试绿 + 模块联调差异表为空。**gate 3**。

### R4 OpenAPI 契约与 codegen 切换

1. 服务端全量 OpenAPI 就绪（springdoc，operationId = actionName）后：客户端 payload 类型从手写切换为 OpenAPI codegen 生成（生成物放 `src/api/rpc/generated/`；`rpcXxx.ts` 的封装函数与 `client.ts` 方法保留，仅替换类型来源）。
2. 加 OpenAPI snapshot 测试：action 名表 §1 逐一存在于 schema。
3. 删除 persisted-queries manifest 生成流程（`gen:persisted-queries`）与 gql codegen（`gen:gql`）——**gqlOp 运行时代码仍在，R5 才删**。
4. app 层接线收尾：`getUserTz/getDeviceModel/getOsVersion` 接入（Intl 时区 + 设备信息），wire 指标全量上报。

### R5 删 GraphQL（与服务端阶段 7 同步执行，最终 gate）

1. 服务端确认 34+8 action 全部 RPC 验收后，客户端删除：`graphql.ts`（gqlOp 及全部 gql 封装）、`graphql/generated/`、persisted manifest、`x-req-id` 之外的 gql 专用逻辑；`transport.ts` 保留（rpcOp 在用）。
2. 全量回归 + 真机冒烟（身份引导、scan、DR、IAP、push 深链）。
3. 文档回写：README/AGENTS.md 删 gql 章节；proposal §一 状态改「已实施」；**最终 review（gate 4）前不得删任何服务端 GraphQL 代码**。

## 3. 纪律（每阶段通用）

- 文件所有权：每模块一个新 `rpc{Module}.ts` + 对应 `.test.ts`；`client.ts` 只追加不修改；既有测试文件除 R0 规定的 URL 断言外零改动。
- 每 action 三条保底测试：URL+`Authorization` header 为 null、meta 字段齐全（reqId/accessToken 同值断言）、信封错误码映射。
- 每阶段结束：`pnpm --filter @ifmix/client-sdk-api test && typecheck` 全绿 + commit（`[rpc][R*]`）+ 更新本文档状态行 + 联调差异表；**gate 未过不进下一阶段**。
- 发现契约偏差：停下写报告，回改 proposal §一 并同步服务端文档，不得两端各自迁就。

## 4. 明确不做

- 不删 gqlOp/trusted documents/codegen（R5 才删，且与服务端阶段 7 同步）；不动 webhook；不改 session.ts/installCoordinator 状态机语义（只换传输）；不加新原生依赖；不做请求层幂等/重试新机制；app 业务层（features/）在本计划内不动（切 SDK 方法内部实现，调用面不变）。
