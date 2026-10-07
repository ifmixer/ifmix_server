# 创建接口幂等性评估与决策记录

> **状态**：最终决策（2026-09-30）
> **目标模块**：`ifmix_server / core-api`
> **结论**：当前不建设通用创建幂等机制；业务主键继续由服务端生成 UUIDv7，接受低频重复记录和重复外部调用。
> **后续实施清单**：`docs/design/install/install-customer-hardening.md`

---

## 1. 背景与真实目标

最初希望为 `createScan`、`createInstall`、`createAnonymousCustomer`、反馈和工单等创建型 mutation 增加幂等能力，避免客户端重试产生重复数据或重复 AI 调用。

讨论后确认，当前更重要的目标不是 Exactly Once，而是：

> install/customer 凭证创建失败、响应丢失、SecureStore 异常或临时网络故障不能让整个 App 永久卡死；网络恢复后，用户应能通过重试继续使用。

重复数据的业务损害目前较低：

- 重复 Install、匿名 Customer 可以由清理任务回收；
- 重复反馈、工单可以由运营或清理脚本处理；
- 少量重复扫描和 AI 调用由频率限制、配额和成本监控约束；
- 用户主动重试导致的重复可以接受。

因此不值得为低频重复引入持久化幂等状态机、响应重放、租约恢复和客户端 operation journal。

---

## 2. 最终决策

### 2.1 不建设通用创建幂等

本期明确不实现：

- `idempotencyKey` / `Idempotency-Key`；
- `core_idempotency_record`；
- request/response snapshot；
- `requestHash`；
- `PROCESSING / SUCCEEDED / FAILED` 通用幂等状态机；
- `ALREADY_EXISTS` 作为创建重试协议；
- 客户端指定业务主键；
- 客户端 pending-operation journal；
- 创建结果自动查询与重放；
- 基础设施层对业务 mutation 的透明自动重试。

### 2.2 业务主键仍由服务端生成

所有业务实体继续使用服务端 `UuidV7.generate()`：

- 保持数据库主键时间有序；
- 保持现有按 ID 排序的游标分页语义；
- 不信任客户端时钟或 UUID 版本；
- 避免客户端 ID 同时承担业务身份和重试控制两种职责。

### 2.3 接受的边界

以下情况是明确接受的取舍，不视为待补功能：

- 创建成功但响应丢失后，用户再次操作可能创建第二条记录；
- createInstall 重试可能留下无绑定的 Install；
- createAnonymousCustomer 重试可能留下无业务数据的匿名 Customer；
- createScan 重试可能再次调用 AI、再次消耗额度；
- feedback/support request 可能重复提交；
- 无法在服务端和外部 AI provider 之间提供严格 Exactly Once。

---

## 3. 评估过的方案

### 3.1 Redis 锁或结果缓存

典型方案：

```text
SETNX idempotencyKey
→ 执行业务
→ 缓存结果
→ 重复请求返回缓存
```

**优点**：

- 接入速度快；
- 并发拦截性能高；
- 适合短时间防抖。

**缺点**：

- Redis 与 PostgreSQL 之间没有原子事务；
- 进程可能在业务成功后、缓存结果前崩溃；
- TTL 过短会重复执行，过长会积累状态；
- AI 执行时间超过锁 TTL 时需要续租和 fencing token；
- Redis 故障时必须在 fail-open 与 fail-closed 之间取舍；
- 仍不能解决 AI provider 已处理、服务端未收到响应的歧义。

**决策**：不采用。Redis 继续用于限流和缓存，不作为创建幂等真相源。

### 3.2 统一 PostgreSQL 幂等表

典型模型：

```text
(projectId, operation, owner, idempotencyKey) UNIQUE
status = PROCESSING / SUCCEEDED / FAILED
resourceId / responseSnapshot
leaseExpiresAt / expiresAt
```

**优点**：

- 跨实例一致；
- 能表达处理中、成功和失败；
- 可以按 Customer/Install 做 owner scope；
- 能在 AI 前原子 claim，防止普通并发双跑。

**缺点**：

- 需要状态机、过期清理、卡死恢复和租约；
- AI 完成后、结果落库前崩溃仍无法判断 AI 是否执行；
- 要做到自动接管需接受再次调用 AI，或让记录永久失败；
- token 返回接口还要处理敏感响应重放；
- 客户端需要持久化 key 和原 payload；
- 显著增加测试矩阵和线上运维复杂度。

**决策**：当前不采用。只有重复成本达到明确阈值时再重新评估。

### 3.3 在各业务表增加 `idempotency_key`

**优点**：

- 不需要通用状态表；
- 对纯数据库创建可用唯一约束去重；
- 业务结果与 key 在同一事务内。

**缺点**：

- 每个模块重复实现 claim/replay/清理；
- createScan 的 AI 在数据库写入前执行，仅加唯一列不能阻止重复 AI；
- token 接口仍无法安全恢复响应；
- 业务表被传输机制字段污染；
- 后续策略变化需要逐表迁移。

**决策**：不采用。

### 3.4 客户端指定业务主键 + `INSERT_ONLY`

该方案曾作为首选草案：客户端传入 `id`，服务端使用 `SaveMode.INSERT_ONLY`；主键冲突返回 `ALREADY_EXISTS`。

**优点**：

- 不增加幂等表；
- 数据库唯一约束可阻止同一主键落两行；
- 对可按 ID 查询的纯数据库对象实现简单。

**缺点**：

1. **只能防重复落库，不能防重复副作用。** createScan 若只是“先查 ID、再调用 AI”，两个并发请求仍可能同时查到不存在并各自调用 AI。
2. **token 接口无法恢复。** createInstall/createAnonymousCustomer 首次成功但响应丢失时，客户端没有 token；再次请求只得到 409，换 ID 又会创建重复记录。
3. **破坏 UUIDv7 约束。** 当前游标分页依赖服务端 UUIDv7 的时间顺序；客户端 UUIDv4 或错误时钟会破坏排序。
4. **读副本延迟。** 409 后立即查询可能在 reader 暂时查不到，从而误判并再次创建。
5. **恢复逻辑复杂。** 客户端必须在发请求前持久化 ID、payload 和状态，直到业务结果也成功落地。
6. **Install 存在双 ID 歧义。** 内部主键与 JWT iid 若不是同一个值，客户端指定内部主键对凭证恢复没有意义。
7. **错误映射风险。** 不能把所有数据库唯一冲突都当作幂等命中，否则会掩盖真实约束错误。

**决策**：放弃。客户端不控制业务主键，也不增加 `ALREADY_EXISTS` 创建协议。

### 3.5 先插入 ScanRecord，再运行 AI

讨论过先创建 `PROCESSING` ScanRecord，再在事务外运行 AI，最后更新结果。

**优点**：

- 并发请求可以观察同一业务记录；
- 配合唯一幂等 key 可在 AI 前确定唯一执行者；
- 结果状态对用户可见。

**缺点**：

- 单独预插记录仍需要一个独立幂等 key；
- 进程在 AI 执行中崩溃会留下卡死记录；
- 需要 watchdog、超时状态和恢复策略；
- 自动恢复仍可能再次调用已经执行过的 AI；
- 当前扫描是同步接口，引入 PROCESSING 会要求前端增加恢复轮询。

**决策**：当前不为防重复改造扫描状态机。若未来将扫描改为异步任务，这一方案可与任务队列一起重新评估。

### 3.6 不做通用幂等，接受重复（最终采用）

**优点**：

- 没有新的跨组件状态；
- 不会因为幂等记录卡死导致客户端无法恢复；
- 不需要响应重放敏感 token；
- 不改变业务主键和游标分页；
- 与当前业务损害和团队规模匹配。

**缺点**：

- 响应不确定时，用户重试可能产生重复；
- 少量外部 AI 成本无法避免；
- 需要清理、限流和监控兜底。

**决策**：采用。

---

## 4. 凭证引导的可用性设计

不做通用幂等不等于忽略异常。凭证引导采用“允许重复、确保最终能重新创建”的策略。

### 4.1 新 Customer 必须绑定 Install

目标不变量：

```text
core_install.id = API installId = JWT iid

install token:
  type = 5
  iid = core_install.id

customer token:
  type = 10
  sub = customerId
  iid = core_install.id
```

新版本创建匿名 Customer 必须携带 installToken，并在同一事务中完成：

```text
创建 Customer
→ 创建 refresh token
→ 绑定 Install↔Customer
→ 签发含 iid 的 customer token
```

login 和 refresh 同样携带 installToken，确保新签发的 customer token 始终有 iid，并维护 Install↔Customer 关系。

> 本轮选择强制方案，不实现 legacy 无 iid 的业务写入灰度。旧 token 必须先通过 installToken+refreshToken 刷新为含 iid 的新 token（v1.0.6 起 legacy 回退已删除）。

### 4.2 客户端允许重复创建凭证

```text
createInstall 响应丢失
→ 再次 createInstall
→ 可能产生孤立 Install
→ 最终获得一个可用 installToken

createAnonymous 响应丢失
→ 使用当前 installToken 再次创建
→ 可能产生旧匿名 Customer
→ 新关系成为当前有效绑定
```

这些孤立记录由后台任务清理，不引入 token 重放机制。

### 4.3 失败只影响当前网络操作

- App 启动不创建凭证、不等待网络；
- 首次 scan/支付等必要网络操作才执行 install/customer bootstrap；
- 有效 customer session 不应被无关 install 更新失败阻塞；
- 临时网络错误、429、500、503 不清除现有 customer session；
- 只有明确的 refresh/session 失效错误才重建匿名身份；
- 服务端整体不可用时，远程功能失败，但本地历史和设置仍可用。

### 4.4 本地持久化失败

服务端已返回凭证但 SecureStore 暂时写入失败时：

- 当前进程先使用内存凭证；
- 记录错误并后台重试 SecureStore；
- 不降级到明文 AsyncStorage；
- App 重启后若凭证确实丢失，则重新创建，接受重复记录。

---

## 5. 保留的专项幂等与唯一约束

“不建设通用创建幂等”不影响已有、由业务自然键支撑的专项机制：

- IAP 按 `originalTransactionId` 去重；
- Deep Research 按 `scanRecordId` 更新；
- 收藏关系使用业务唯一约束；
- Install↔Customer 关系 bind/unbind 使用唯一约束并保持操作幂等；
- webhook 事件按平台事件 ID 去重；
- 配置、迁移、导入脚本继续使用各自的幂等语义。

这些机制与业务实体同源，不需要通用 idempotency key。

---

## 6. 独立安全与模型修正

以下事项不是幂等功能，仍必须单独完成：

1. `q_ai_scan_getById` 必须按 `projectId + customerId + id` 查询；当前仅按 project + id 不满足 “My” 语义。
2. `core_install.id`、API `installId` 和 JWT `iid` 收敛为同一个 UUID。
3. Customer 业务数据的可信 install ID 只能来自 token `iid`，不能来自 `x-install-id`。
4. 新签发的 customer token 必须包含 iid；新 Customer 业务记录的 install ID 目标态为非空。
5. 未来设备认证必须在统一授权层校验 verified install，不能仅依赖“请求带 installToken”。

---

## 7. 限流、清理与监控

### 7.1 现有防护

- 客户端扫描期间禁止重复点击；
- createInstall/createAnonymousCustomer 按 IP 限流；
- AI 扫描按 Customer 配额限制；
- mutation 不做基础设施透明自动重试；
- 匿名 Customer 已有清理任务。

### 7.2 Cleanup 延期

Customer/Install 清理脚本本期不修改，也不在本决策中定义 `RESOURCE_GUARD_TABLES`、候选条件或 resource 处理方式。重复记录暂时保留；cleanup 后续另起设计评审。

### 7.3 建议指标

- `createInstall` 次数 / DAU；
- anonymous Customer 创建次数 / Install；
- 凭证创建失败率及错误码分布；
- SecureStore 持久化失败率；
- 同一 Customer 短时间内相同 imageKey 的扫描次数；
- AI 调用成本中疑似重复占比。

---

## 8. 重新评估触发条件

只有出现以下任一情况，才重新讨论通用幂等：

- 重复 AI 调用形成可观成本；
- 疑似重复扫描达到约定阈值（例如 >0.5%）；
- 重复工单/反馈明显影响运营；
- 客诉集中于“失败后重试产生重复”；
- AI provider 原生支持 idempotency key；
- 扫描改为异步任务，已有可靠任务状态机和恢复机制。

重新评估时优先考虑“持久化幂等记录 + owner scope + 原子 claim”，而不是客户端控制业务主键。

---

## 9. 决策摘要

| 决策 | 结果 |
|------|------|
| 通用 idempotency key | 不实现 |
| 客户端指定业务主键 | 不允许 |
| requestHash | 不实现 |
| ALREADY_EXISTS 创建协议 | 不实现 |
| 响应/token 重放 | 不实现 |
| 创建 mutation 自动重试 | 不做；仅凭证 bootstrap 可有限重试 |
| 重复记录/AI | 当前接受，以限流、配额、清理和监控兜底 |
| 业务主键 | 服务端 UUIDv7 |
| 新匿名 Customer | 必须持有 installToken，并原子绑定 Install |
| customer token iid | 新签 token 必填 |
| 未来设备认证 | 在统一授权层按 verified install 强制 |
