# RPC 试点实现计划 — 客户端（antique / client-sdk）：rpcOp 传输层 + demo action

> **给接手的 agent**：先读 `packages/client-sdk/AGENTS.md` → ifmix_server 仓库 `docs/design/api-protocol/graphql-to-http-rpc-openapi.md` §一（协议定稿，唯一真相源）→ 本文件。契约冲突时以 §一 为准并回改本文件。逐任务更新「状态」，不要重做已完成任务。
> - 仓库：`/Users/jason/ai/myprojects/antique`；改动集中在 `packages/client-sdk/packages/api/`；分支 `feature/graphql-to-rpc`（自 master 切出——**wire v3 客户端已完成并合入 master**；若已建 feature/rpc-pilot，直接 `git branch -m feature/graphql-to-rpc`）。
> - 服务端配套见 ifmix_server 仓库 `docs/design/api-protocol/rpc-pilot-server.md`（不归你改）。
> - **硬性边界：只做传输层 + demo 的 8 个 action。其余 action 不动，gqlOp 路径全量保留，trusted documents/codegen 流程不删，现有测试一行行为都不许变（挪位置可以）。**

## 0. 一页纸摘要

把 `graphql.ts` 里的 `sendMaybeEncrypted` 抽成协议无关的 `transport.ts`（gqlOp 改为调用它，行为不变），新增 `rpcOp`：`POST {baseUrl}/customer/core/rpc/{reqName}`，请求体 `{meta, input}`，响应 `Envelope{code, msg, data}`（HTTP status = code 前三位）。凭证走 `meta.accessToken`（RPC 请求**不再带** Authorization header）。然后给 demo 的 8 个 action 加 RPC 封装（`guarded()` 刷新重试语义与 gql 版一致）。

## 0.1 协议契约速查（与 proposal §一 一致）

```
URL:      POST {baseUrl}/customer/core/rpc/{reqName}    （如 m_demo_createTodo）
请求体:   {"meta": {...}, "input": {...}}               （加密时 wire v3 封装，明文时 JSON）
meta 字段: reqId, projectId, accessToken(纯 token，无 Bearer), locale, currency, country,
          userTz(IANA 名，可选), appVersion, otaVersion, clientPlatform('ios'|'android'|'web'),
          deviceModel(可选), osVersion(可选)
          不传: installId / refreshToken / ts
header:   Content-Type: application/json（明文）或 application/octet-stream（加密）；
          x-req-id（与 meta.reqId 同值，服务端日志/错误响应回显依赖它）；x-wirep-version: 3（加密时）
响应:     {"code":"200000","msg":"success","data":{...}}；HTTP status = code 前三位
错误:     code 非 2 开头 → ApiError(code, msg, {retryAfter: Retry-After 响应头})
          非 JSON → ApiError(SERVICE_UNAVAILABLE)
加密:     复用 wireCrypto.ts；415/400003 降级、响应解密失败不重发、进程级 wireUnsupported 全部不变
```

## 1. 并行执行计划（多 subagent 加速）

| WP | 内容 | 独占文件集 | 依赖 | 并行性 |
|----|------|-----------|------|--------|
| **C1** | transport 抽取 + 版本头改名 | `transport.ts`（新建）、`graphql.ts`（只做抽取与改名）、`wireCrypto.ts`（注释）、`graphql.test.ts`、`wireCrypto.test.ts` | 无 | Wave 1 |
| **C2** | rpcOp 传输层 | `rpc.ts`（新建）、`rpc.test.ts`（新建）、`codes.ts`（只读）、`envelope.ts`（只读）、`package.json`（exports 追加） | C1 | Wave 2 |
| **C3** | client.ts 集成 + demo RPC 封装 | `client.ts`（追加，不改既有方法）、`rpcDemo.ts`（新建）、`client.rpc.test.ts`（新建）、`testUtils.ts`（只读） | C2 | Wave 3 |
| **C4** | RPC 加密链路测试 | `rpc.wire.test.ts`（新建） | C2 | Wave 3 |
| **C5** | 本地联调 E2E + 试点报告 | 文档回写 | C3 + 服务端就绪 | Wave 4（串行） |

**WP 状态**：C1 done (2026-10-06) · C2 done (2026-10-06) · C3 done (2026-10-06) · C4 done (2026-10-06) · C5 partial (2026-10-06): 文档回写 done; 本地 E2E 待服务端 bootRun(localhost:3001 未起、服务端 worktree 无实现 commit)

**并行方式**：同 WP 内任务串行；不同 WP 文件零交集。同一工作目录只能串行执行，真并行需 `git worktree` 各自检出后按 Wave rebase 回 `feature/graphql-to-rpc`。每个 Wave 结束跑 `pnpm --filter @ifmix/client-sdk-api test && pnpm --filter @ifmix/client-sdk-api typecheck`。**每个 WP 严禁改动所有权表之外的文件**；需要他人文件改动的，写进 WP 报告由 C5 统一处理。

以下每个 WP 一节：目标、逐文件规格（精确签名与行为）、验收。

---

## 2. WP-C1：transport 抽取 + 版本头改名

1. 新建 `packages/client-sdk/packages/api/src/api/transport.ts`，把 `graphql.ts` 的以下成员**原样搬入**（不改行为）：
   - `sendMaybeEncrypted`（graphql.ts:655-705，签名不变）。
   - 进程级降级开关 `let wireUnsupported = false` 与 `resetWireFallback()`（graphql.ts:634-638）。
   - `retryAfterFrom`（graphql.ts:151-155 附近，改为 `export`）、`nowMs`（:640-648，改为 `export`）。
   - `SENSITIVE_LOG_KEYS`、`redactSecretString`、`redactProofSubtree`、`redactForLog`（graphql.ts:553-617）——移入 transport.ts 并 `export`。
   - `WireMetrics` 类型如定义在 wireCrypto.ts 则不动；transport.ts 从 wireCrypto.ts import。
2. `graphql.ts` 改为从 transport.ts import 上述成员；**`export function resetWireFallback()` 必须继续从 `./graphql` 可导入**（re-export，现有测试 import 路径不变）。
3. 版本头改名：graphql.ts 原第 679 行 `'x-proto-version': '3'` → `'x-wirep-version': '3'`；`wireCrypto.ts` 头部注释与常量注释同步；`wireCrypto.test.ts:275` 附近对 `x-proto-version` 的断言改为 `x-wirep-version`；全仓 grep 确认 api 包无 `x-proto-version` 残留（schema 快照与文档除外）。
4. `gqlOp` 的行为、URL、日志格式不变；`graphql.test.ts` 只允许改上述 header 名断言。

**验收**：`pnpm --filter @ifmix/client-sdk-api test` 全绿（含 14 个既有测试文件）；typecheck 通过。

## 3. WP-C2：rpcOp 传输层

### 3.1 新建 `rpc.ts`

```ts
import { newReqId } from './reqId';
import { ApiError, ApiCode } from './envelope';        // envelope.ts 重导出 codes 的 ApiError（现状如此，import 路径以现有文件为准）
import { sendMaybeEncrypted, retryAfterFrom } from './transport';
import type { WireKey, WireMetrics } from './wireCrypto';

/** RPC 请求 meta（proposal §一 RequestMeta 的客户端形态；undefined 字段 JSON.stringify 自动省略）。 */
export type RpcMeta = {
  reqId?: string;
  projectId?: string;
  accessToken?: string;      // 纯 token，无 Bearer 前缀
  locale?: string;
  currency?: string;
  country?: string;
  userTz?: string;
  appVersion?: string;
  otaVersion?: string;
  clientPlatform?: string;   // 'android' | 'ios' | 'web'
  deviceModel?: string;
  osVersion?: string;
};

export type RpcOpts = {
  baseUrl: string;
  meta: RpcMeta;
  headers?: Record<string, string>;   // 额外头（一般不用；x-req-id 与 Content-Type 由 rpcOp 注入）
  fetch?: typeof fetch;
  wire?: { key: WireKey };
  onWireMetrics?: (m: WireMetrics) => void;
};

export async function rpcOp<T>(reqName: string, input: unknown, opts: RpcOpts): Promise<T>
```

`rpcOp` 执行流程（逐条实现，对齐 gqlOp 既有语义）：

1. `const reqId = opts.meta.reqId ?? newReqId()`；headers = `{ 'Content-Type': 'application/json', 'x-req-id': reqId, ...opts.headers }`。
2. `const meta = { ...opts.meta, reqId }`（reqId 强制回填，meta 与 header 同值）。
3. `const body = JSON.stringify({ meta, input })`。
4. `const { res, text } = await sendMaybeEncrypted(fetchImpl, url, headers, body, reqName, opts.wire && !wireUnsupported ? opts.wire : undefined, opts.onWireMetrics)`——`url = `${opts.baseUrl}/customer/core/rpc/${reqName}``；`fetchImpl = opts.fetch ?? fetch`；wire 降级开关**直接用 transport.ts 里那个**（与 gqlOp 共享进程级状态，不新建）。
5. `JSON.parse(text)` 失败 → `throw new ApiError(ApiCode.SERVICE_UNAVAILABLE, `non-JSON response (status ${res.status})`)`（对齐 graphql.ts:740-747）。
6. 信封错误：`if (typeof json.code === 'string' && !json.code.startsWith('2')) throw new ApiError(json.code, json.msg ?? json.code, { retryAfter: retryAfterFrom(res) })`（对齐 graphql.ts:752-756；retryAfterSec 的 extensions 通道是 GraphQL 专属，RPC 走 Retry-After 头，服务端已配套输出）。
7. `if (!res.ok) throw new ApiError(ApiCode.INTERNAL, `HTTP ${res.status}`)`。
8. `return json.data as T`（成功但 data 为 null 时原样返回 null，由调用方按类型断言）。
9. `__DEV__` 日志：与 gqlOp 同格式（`[rpcOp] → m_demo_xxx POST ...`），meta 与 input 过 `redactForLog`（从 transport.ts import），headers 里 accessToken 类值不进日志（RPC 本就不放 header，打 `meta: <redacted-keys>` 摘要即可）。

### 3.2 新建 `rpc.test.ts`（mock fetch 注入，风格同 client.test.ts）

1. 成功路径：`rpcOp('q_demo_findTodoById', {id:'...'}, {baseUrl, meta:{projectId:'a', accessToken:'t'}})` → 断言 URL、`x-req-id` header 存在、body `{meta:{reqId 与 header 同值, projectId:'a', accessToken:'t'}, input:{id}}`、返回 `json.data`。
2. 信封错误：响应 `{code:'429000', msg:'rate limited'}` + HTTP 429 + `Retry-After: 30` → `ApiError.code==='429000'`、`isRateLimited===true`、`retryAfter===30`。
3. 非 JSON 响应 → SERVICE_UNAVAILABLE。
4. meta.reqId 缺省自动生成、显式传入时与 header 同值。
5. 降级共享：先让服务端返回 400003，再发第二个请求应为明文（`wireUnsupported` 进程级生效），且 gqlOp 的 `resetWireFallback()` 能重置 rpcOp 的状态（证明共享）。

### 3.3 `package.json` exports 追加

```json
"./rpc":      { "types": "./src/api/rpc.ts",      "default": "./src/api/rpc.ts" },
"./rpcDemo":  { "types": "./src/api/rpcDemo.ts",  "default": "./src/api/rpcDemo.ts" }
```

（rpcDemo.ts 在 WP-C3 创建；exports 先占位也可以，但 typecheck 前必须已存在。）

**验收**：`rpc.test.ts` 全绿；既有 14 个测试文件不动一行仍绿。

## 4. WP-C3：client.ts 集成 + demo RPC 封装

### 4.1 `client.ts` 追加（不改既有成员）

1. `ServerApiOptions` 追加三个可选 getter（client.ts:256-300 接口内，紧跟 getOtaVersion）：
   ```ts
   getUserTz?: () => string | undefined;      // IANA 时区名
   getDeviceModel?: () => string | undefined;
   getOsVersion?: () => string | undefined;
   ```
2. 新增 `buildRpcMeta`（与 buildContextHeaders 平行，client.ts:311-338 风格），凭证逻辑**逐字对齐**它的 token 分支：
   ```ts
   function buildRpcMeta(opts: ServerApiOptions, peek: CredentialPeek, authorization: AuthorizationMode = 'auto'): RpcMeta {
     const token = authorization === 'none' ? undefined
       : authorization === 'install' ? peek.install()?.installToken
       : peek.customerToken() ?? peek.install()?.installToken;
     return {
       projectId: opts.projectId,
       clientPlatform: opts.platform,
       locale: opts.getLang?.(), currency: opts.getCurrency?.(), country: opts.getCountry?.(),
       appVersion: opts.getAppVersion?.(), otaVersion: opts.getOtaVersion?.(),
       userTz: opts.getUserTz?.(), deviceModel: opts.getDeviceModel?.(), osVersion: opts.getOsVersion?.(),
       accessToken: token,
     };
   }
   ```
3. 新增三个 opts 工厂（对齐 makeGqlOpts/makeAuthlessGqlOpts/makeInstallGqlOpts，client.ts:463-494）：`makeRpcOpts`（auto）/ `makeAuthlessRpcOpts`（none）/ `makeInstallRpcOpts`（install），均含 `baseUrl: resolveBaseUrl()`、`fetch: opts.fetch`、`wire: opts.getWireKey?.() ? { key: opts.getWireKey()! } : undefined`、`onWireMetrics: opts.onWireMetrics`、`meta: buildRpcMeta(...)`。
4. 新增 8 个 client 方法（追加到 client.ts:1011-1058 的 todo 方法区旁边，**方法名带 Rpc 后缀**，原方法不动）：
   ```ts
   todoCreateOneRpc, todoUpdateOneRpc, todoUpdateItemsRpc, todoDeleteByIdRpc,
   todoDeleteByIdsRpc, todoFindByIdRpc, todoFindByCursorRpc, todoFindByIdsRpc
   ```
   每个方法 = `guarded(() => rpcXxx(input, makeRpcOpts(options)))`（`guarded` 在 client.ts:782-798，401002→refresh→重试、会话失效→resetToAnonymous 的语义自动继承，**不要**另写刷新逻辑）。入参/返回类型见 4.2 的函数签名。

### 4.2 新建 `rpcDemo.ts`（8 个 RPC 封装 + 手写类型）

类型对齐服务端 `dto/demo`（rpc-pilot-server.md §4.1/4.2），DateTime 全部 string：

```ts
export interface RpcTodoRecItem { recId: string; title: string | null; priority: number; createdAt: string; updatedAt: string | null; }
export interface RpcTodoRecommend { sectionId: string; sectionName: string; viewCount: number | null; recItems: RpcTodoRecItem[] | null; }
export interface RpcTodoItem { id: string; content: string; done: boolean; note: string | null; createdAt: string; updatedAt: string | null; }
export interface RpcTodo {
  id: string; title: string; done: boolean; note: string | null;
  meta: Record<string, unknown> | null; recommend: RpcTodoRecommend | null;
  items: RpcTodoItem[]; itemCount: number; pendingCount: number; finishCount: number;
  createdAt: string; updatedAt: string | null;
}
export interface RpcPageInfo { nextCursor: string | null; hasMore: boolean; }
export interface RpcTodoPage { items: RpcTodo[]; pageInfo: RpcPageInfo; }
export interface RpcActionResult { success: boolean; modifiedCount?: number | null; }
```

输入类型（与 GraphQL variables 同形）：

```ts
export interface RpcCreateTodoInput { title: string; done?: boolean; note?: string; recommend?: ...; items?: { content: string; done?: boolean; note?: string }[]; }
export interface RpcUpdateTodoInput { id: string; set?: { title?: string; done?: boolean; note?: string; recommend?: ... } | null; unset?: string[]; }   // unset: 'NOTE'|'RECOMMEND'
export interface RpcUpdateTodoItemsInput { create?: { todoId: string; content: string; done?: boolean; note?: string }[]; update?: { id: string; set?: {...} | null; unset?: string[] }[]; delete?: string[]; }
export interface RpcFindOptions { filter?: unknown; cursor?: string; sortBy?: string; sortDirection?: 'ASC' | 'DESC'; limit?: number; }   // filter 试点透传 unknown（demo 不用 filter）
```

8 个函数（每行一个，模式统一）：

```ts
export const rpcFindTodoById     = (input: { id: string }, o: RpcOpts) => rpcOp<RpcTodo>('q_demo_findTodoById', input, o);
export const rpcFindTodos        = (input: { findOptions?: RpcFindOptions }, o: RpcOpts) => rpcOp<RpcTodoPage>('q_demo_findTodos', input, o);
export const rpcFindTodosByIds   = (input: { ids: string[] }, o: RpcOpts) => rpcOp<RpcTodo[]>('q_demo_findTodosByIds', input, o);
export const rpcCreateTodo       = (input: RpcCreateTodoInput, o: RpcOpts) => rpcOp<{ todo: RpcTodo }>('m_demo_createTodo', input, o);
export const rpcUpdateTodo       = (input: RpcUpdateTodoInput, o: RpcOpts) => rpcOp<{ success: boolean; todo: RpcTodo | null }>('m_demo_updateTodo', input, o);
export const rpcBatchUpdateTodoItems = (input: RpcUpdateTodoItemsInput, o: RpcOpts) => rpcOp<{ success: boolean }>('m_demo_batchUpdateTodoItems', input, o);
export const rpcDeleteTodo       = (input: { id: string }, o: RpcOpts) => rpcOp<RpcActionResult>('m_demo_deleteTodo', input, o);
export const rpcDeleteTodoByIds  = (input: { ids: string[] }, o: RpcOpts) => rpcOp<RpcActionResult>('m_demo_deleteTodoByIds', input, o);
```

**注意与 GraphQL 版的返回差异（这是协议变化，不是 bug）**：q_demo_findTodoById GraphQL 版对未找到抛错，RPC 版服务端返回 HTTP 404 + code 404000 → rpcOp 抛 `ApiError`（`isSessionInvalid` 为 false，不会误伤会话状态）；deleteTodo 的 GraphQL `ActionResult.success` 在 RPC 中同样恒 true，失败走错误信封。

### 4.3 新建 `client.rpc.test.ts`（mock fetch 注入 + testUtils 的 memoryInstallStore/memoryTokenStore）

1. 头部与 body 断言（镜像 client.test.ts:51-102 用例 1）：`api.todoCreateOneRpc(...)` → URL `${BASE}/customer/core/rpc/m_demo_createTodo`；**`Authorization` header 必须为 null**；`x-req-id` 存在；body.meta 含 projectId/clientPlatform('ios')/locale/currency/country/appVersion/otaVersion/accessToken('access-1')，body.input 与传入对象逐字段相等。
2. install-only 模式：无 customer token 时 meta.accessToken === 'install-token-1'。
3. 401002 → `guarded` 触发 refresh：mock 第一次返回 401 信封 `{code:'401002'}`，`m_auth_refreshToken`（gql 路径，mock 返回新 token）成功后重试 RPC 成功——断言 refresh 被调一次、最终拿到数据。
4. 分页：todoFindByCursorRpc 返回 `{items:[...], pageInfo:{nextCursor:null, hasMore:false}}` 原样解包。
5. 8 个 action 各一条 URL 断言（路径名逐一核对）。

**验收**：api 包全部测试（14 旧 + 2 新）与 typecheck 全绿；apps/antique 的 jest（app 侧）不受影响。

## 5. WP-C4：RPC 加密链路测试（`rpc.wire.test.ts`，可与 WP-C3 并行）

复用 `wireCrypto.test.ts` 的独立 mock 服务端实现（:100-150 的 SERVER_PRIV/serverOpenRequest/serverSealResponse，**直接 import 或复制该套实现到本文件**，不要反向 import 生产代码内部）：

1. `api(getWireKey: () => TEST_KEY)` + `todoFindByIdRpc`：断言请求 `content-type === 'application/octet-stream'`、`x-wirep-version === '3'`、密文可被 serverOpenRequest 解出且 body 内 `meta.reqId === header['x-req-id']`；响应为 serverSealResponse 加密的信封，客户端解密后拿到数据。
2. 降级：服务端对加密请求返回明文 `{"code":"400003"}` 400 → rpcOp 明文重发成功；`resetWireFallback()` 后再次加密。
3. 响应解密失败 → 抛 SERVICE_UNAVAILABLE 且不重发（对齐 wireCrypto.test.ts 既有断言风格）。

**验收**：与 WP-C3 的改动合并后全量测试绿（两 WP 文件零交集，rebase 无冲突）。

## 6. WP-C5：本地联调 E2E + 收尾（串行）

1. 服务端 `bootRun`（ifmix_server 仓库，PG + Redis + kid=1 开发 key；local preset `http://localhost:3001` 或按 `apps/antique/src/lib/env.ts` 的 local preset）。
2. 临时 node 脚本（跑完删除）：直连 `rpcOp` 走通 8 个 action——明文 + 加密（DEV_WIRE_KEY）各一遍；`findTodos({findOptions:{limit: 3}})` 验证 >4KB 响应 gzip 分支；临时不配 key 的服务端场景验证 400003 降级一次。
3. 与服务端 agent 的差异表对齐：同用例 gql 版 vs rpc 版字段/错误码一致。
4. 回写文档：client-sdk README/AGENTS.md 增加rpcOp 一节（用法 + 「试点仅 demo，其余 action 仍走 gqlOp」）；发现契约偏差回改 proposal §一 并通知服务端文档。
5. **停下等 review**：输出试点报告（改动文件清单、测试结果、差异表、wire 指标 sealMs/openMs 抽样），用户确认前不得迁移其他 action、不得删 gqlOp/trusted documents。

## 7. 明确不做

- 不迁移 demo 以外 action；不改 `customer-gql.graphql`、generated types、persisted manifest、gen:gql 脚本。
- 不删 gqlOp/trusted documents；不动 attest/auth/install/pay/noti 包；apps/antique app 层不改（demo 无 UI 调用面；userTz/deviceModel/osVersion 的 app 接线留到全量迁移，meta 字段可空、服务端容忍缺失）。
- 不加新原生依赖；不做请求重试/幂等新机制（沿用 retry.ts 现状）。
