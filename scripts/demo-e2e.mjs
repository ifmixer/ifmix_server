#!/usr/bin/env node
/**
 * demo E2E：createIosInstall → updateInstall → createAnonymous → Todo CRUD 全流程。
 *
 * 前提：
 *   - 本地 server 已启动（SPRING_PROFILES_ACTIVE=local，默认 http://localhost:3001）
 *   - DB 有 project（默认 `antique`，可用 PROJECT_ID 覆盖）
 *   - local profile：wire-crypto mode=optional、allow-raw-query=true → 明文 curl/脚本可调
 *
 * 用法：node scripts/demo-e2e.mjs
 * 可选环境变量：BASE_URL（默认 http://localhost:3001）、PROJECT_ID（默认 antique）
 */

const BASE_URL = process.env.BASE_URL ?? 'http://localhost:3001'
const PROJECT_ID = process.env.PROJECT_ID ?? 'antique'
const GQL = `${BASE_URL}/customer/core/gql`
// 每次运行唯一前缀，避免上次失败残留数据干扰断言
const RUN = `e2e-${Date.now().toString(36)}`

let step = 0
let failures = 0

/** 携带的上下文（随流程推进更新：installToken → customerToken） */
const session = {
  installId: null,
  installToken: null,
  customerId: null,
  customerToken: null,
  refreshToken: null,
}

/** x-req-meta header（明文 dev 通道的 RequestMeta；key 用普通字段名，不含凭证） */
function meta() {
  return JSON.stringify({
    projectId: PROJECT_ID,
    clientPlatform: 'ios',
    appVersion: '1.0.0',
    otaVersion: '1',
    locale: 'zh-CN',
    currency: 'USD',
    country: 'US',
  })
}

/** 当前生效的凭证（install token → customer token），走标准 Authorization header。 */
function authHeader() {
  return session.customerToken ?? session.installToken
}

async function gql(name, query, variables = {}) {
  step++
  const label = `[${String(step).padStart(2, '0')}] ${name}`
  const t0 = Date.now()
  const headers = { 'content-type': 'application/json', 'x-req-meta': meta() }
  const auth = authHeader()
  if (auth) headers['authorization'] = `Bearer ${auth}`
  let res
  try {
    res = await fetch(GQL, {
      method: 'POST',
      headers,
      body: JSON.stringify({ query, variables }),
    })
  } catch (e) {
    failures++
    console.log(`✗ ${label} — 请求失败: ${e.cause?.code ?? e.message}`)
    throw e
  }
  const httpStatus = res.status
  const body = await res.json()
  const ms = Date.now() - t0
  if (httpStatus !== 200) {
    failures++
    console.log(`✗ ${label} — HTTP ${httpStatus}: ${JSON.stringify(body).slice(0, 400)}`)
    throw new Error(`${name} HTTP ${httpStatus}`)
  }
  if (body.errors?.length) {
    failures++
    const errs = body.errors
      .map((e) => {
        const ext = e.extensions ?? {}
        return `${ext.code ?? ''} ${e.message}${ext.retryAfterSec != null ? ` (retryAfter ${ext.retryAfterSec}s)` : ''}`
      })
      .join(' | ')
    console.log(`✗ ${label} — GraphQL error: ${errs}`)
    throw new Error(`${name}: ${errs}`)
  }
  console.log(`✓ ${label} (${ms}ms)`)
  return body.data
}

function expect(cond, label, actual) {
  if (cond) return
  failures++
  console.log(`✗ assert: ${label}${actual !== undefined ? ` — 实际: ${JSON.stringify(actual)}` : ''}`)
  throw new Error(`assert failed: ${label}`)
}

const TODO_FIELDS = `
  id title done note meta itemCount pendingCount finishCount createdAt updatedAt
  items { id content done note createdAt updatedAt }
  recommend { sectionId sectionName viewCount recItems { recId title priority } }
`

async function main() {
  console.log(`demo-e2e → ${GQL}  (projectId=${PROJECT_ID})`)

  // ===== 1. Install：createIosInstall（无 proof；attest 未启用 → attestationStatus=30）=====
  const create = await gql(
    'createIosInstall',
    `mutation ($input: CreateInstallInput!) {
       m_auth_install_createIosInstall(input: $input) { installId installToken attestationStatus }
     }`,
    {
      input: {
        deviceInfo: { model: 'iPhone17,3', os: 'iOS 26.0', script: 'demo-e2e' },
        storeType: 10,
      },
    },
  )
  const install = create.m_auth_install_createIosInstall
  expect(install.installToken?.length > 20, 'createIosInstall 返回 installToken', install)
  expect(install.attestationStatus === 30, 'attestationStatus=30（attest 未启用）', install.attestationStatus)
  session.installId = install.installId
  session.installToken = install.installToken
  console.log(`    installId=${install.installId}`)

  // ===== 2. updateInstall（installToken 鉴权）=====
  const upd = await gql(
    'updateInstall',
    `mutation ($input: UpdateInstallInput!) {
       m_auth_install_updateOne(input: $input) { success }
     }`,
    { input: { firebaseInstallId: 'demo-fid-123', fcmToken: 'demo-fcm-token', scanResultNotiEnabled: false } },
  )
  expect(upd.m_auth_install_updateOne.success === true, 'updateInstall success=true')

  // ===== 3. createAnonymous（installToken 携带可信 iid）=====
  const anon = await gql(
    'createAnonymous',
    `mutation {
       m_auth_customer_createAnonymous {
         customerId accessToken refreshToken refreshExpiresAt expiresIn
         customer { id anonymous }
       }
     }`,
  )
  const a = anon.m_auth_customer_createAnonymous
  expect(a.customerId, 'createAnonymous 返回 customerId', a)
  expect(a.customer?.anonymous === true, 'customer.anonymous=true')
  session.customerId = a.customerId
  session.customerToken = a.accessToken
  session.refreshToken = a.refreshToken
  console.log(`    customerId=${a.customerId} expiresIn=${a.expiresIn}s`)

  // ===== 4. me =====
  const me = await gql(
    'me',
    `query { q_auth_session_me { user { id } tier tierActive } }`,
  )
  expect(me.q_auth_session_me.user.id === a.customerId, 'me.user.id == customerId', me)

  // ===== 5. Todo create（嵌 items + recommend + meta）=====
  const recSectionId = crypto.randomUUID()
  const created = await gql(
    'todo createOne',
    `mutation ($input: CreateTodoInput!) {
       m_demo_todo_createOne(input: $input) { todo { ${TODO_FIELDS} } }
     }`,
    {
      input: {
        title: `${RUN}-todo-1`,
        done: false,
        note: 'initial note',
        recommend: {
          sectionId: recSectionId,
          sectionName: 'demo section',
          viewCount: 3,
          recItems: [{ recId: crypto.randomUUID(), title: 'rec item', priority: 1, createdAt: new Date().toISOString() }],
        },
        items: [
          { content: 'item-1', done: false },
          { content: 'item-2', done: true },
        ],
      },
    },
  )
  const todo = created.m_demo_todo_createOne.todo
  expect(todo.title === `${RUN}-todo-1`, 'createOne 返回 title', todo)
  expect(todo.items.length === 2, '嵌套 items 写入 2 条', todo.items)
  expect(todo.itemCount === 2 && todo.pendingCount === 1 && todo.finishCount === 1, '计数正确', todo)
  const todoId = todo.id
  console.log(`    todoId=${todoId}`)

  // ===== 6. 查询：getMyById / getMyByIds / listMy（FilterGroup）=====
  const got = await gql(
    'todo getMyById',
    `query ($id: UUID!) { q_demo_todo_getMyById(id: $id) { ${TODO_FIELDS} } }`,
    { id: todoId },
  )
  expect(got.q_demo_todo_getMyById.id === todoId, 'getMyById 命中')

  const gotMany = await gql(
    'todo getMyByIds',
    `query ($ids: [UUID!]!) { q_demo_todo_getMyByIds(ids: $ids) { id title } }`,
    { ids: [todoId] },
  )
  expect(gotMany.q_demo_todo_getMyByIds.length === 1, 'getMyByIds 命中 1 条')

  const listed = await gql(
    'todo listMy（filter+分页）',
    `query ($findOptions: CommonFindOptions) {
       q_demo_todo_listMy(findOptions: $findOptions) {
         items { id title done }
         pageInfo { nextCursor hasMore }
       }
     }`,
    {
      findOptions: {
        filter: { and: [{ field: { field: 'title', op: 'LIKE', value: `${RUN}%` } }] },
        sortBy: 'id',
        sortDirection: 'DESC',
        limit: 10,
      },
    },
  )
  expect(listed.q_demo_todo_listMy.items.some((t) => t.id === todoId), 'listMy 过滤命中新建 todo', listed)

  // ===== 7. 更新：set + unset NOTE =====
  const set1 = await gql(
    'todo updateMyById set',
    `mutation ($input: UpdateTodoInput!) {
       m_demo_todo_updateMyById(input: $input) { success todo { id title done note } }
     }`,
    { input: { id: todoId, set: { title: `${RUN}-todo-1 (updated)`, done: true, note: 'note v2' } } },
  )
  expect(set1.m_demo_todo_updateMyById.todo.title === `${RUN}-todo-1 (updated)`, 'set 生效')
  expect(set1.m_demo_todo_updateMyById.todo.note === 'note v2', 'note set 生效')

  const unset1 = await gql(
    'todo updateMyById unset NOTE',
    `mutation ($input: UpdateTodoInput!) {
       m_demo_todo_updateMyById(input: $input) { success todo { id note } }
     }`,
    { input: { id: todoId, unset: ['NOTE'] } },
  )
  expect(unset1.m_demo_todo_updateMyById.todo.note === null, 'note unset 后为 null', unset1.m_demo_todo_updateMyById)

  // ===== 8. 子实体：todoItem updateMyMany（create/update/delete）=====
  const itemId1 = todo.items[0].id
  const itemOps = await gql(
    'todoItem updateMyMany',
    `mutation ($input: UpdateTodoItemsMutationInput!) {
       m_demo_todoItem_updateMyMany(input: $input) { success }
     }`,
    {
      input: {
        create: [{ todoId, content: 'item-3', done: false }],
        update: [{ id: itemId1, set: { done: true, note: 'done by e2e' } }],
      },
    },
  )
  expect(itemOps.m_demo_todoItem_updateMyMany.success === true, 'updateMyMany success')

  const afterItems = await gql(
    'todo 复查子实体',
    `query ($id: UUID!) { q_demo_todo_getMyById(id: $id) { itemCount pendingCount finishCount items { id content done note } } }`,
    { id: todoId },
  )
  const t2 = afterItems.q_demo_todo_getMyById
  expect(t2.itemCount === 3, 'create 后 itemCount=3', t2)
  expect(t2.items.find((i) => i.id === itemId1)?.done === true, 'item update 生效')
  const item3 = t2.items.find((i) => i.content === 'item-3')
  expect(item3, 'item create 生效')

  const itemDel = await gql(
    'todoItem updateMyMany delete',
    `mutation ($input: UpdateTodoItemsMutationInput!) {
       m_demo_todoItem_updateMyMany(input: $input) { success }
     }`,
    { input: { delete: [item3.id] } },
  )
  expect(itemDel.m_demo_todoItem_updateMyMany.success === true, 'item delete success')

  const afterDel = await gql(
    'todo 复查删除后计数',
    `query ($id: UUID!) { q_demo_todo_getMyById(id: $id) { itemCount items { id } } }`,
    { id: todoId },
  )
  expect(afterDel.q_demo_todo_getMyById.itemCount === 2, 'delete 后 itemCount=2', afterDel)

  // ===== 9. 再建一条 → deleteMyOne / deleteMyMany =====
  const created2 = await gql(
    'todo createOne（第二条）',
    `mutation ($input: CreateTodoInput!) { m_demo_todo_createOne(input: $input) { todo { id title } } }`,
    { input: { title: `${RUN}-todo-2` } },
  )
  const todoId2 = created2.m_demo_todo_createOne.todo.id

  const delOne = await gql(
    'todo deleteMyOne',
    `mutation ($id: UUID!) { m_demo_todo_deleteMyOne(id: $id) { success modifiedCount } }`,
    { id: todoId2 },
  )
  expect(delOne.m_demo_todo_deleteMyOne.success === true, 'deleteMyOne success')

  const delMany = await gql(
    'todo deleteMyMany',
    `mutation ($ids: [UUID!]!) { m_demo_todo_deleteMyMany(ids: $ids) { success modifiedCount } }`,
    { ids: [todoId] },
  )
  expect(delMany.m_demo_todo_deleteMyMany.success === true, 'deleteMyMany success')

  const listAfterDel = await gql(
    'todo listMy（删除后应为空）',
    `query ($findOptions: CommonFindOptions) {
       q_demo_todo_listMy(findOptions: $findOptions) { items { id } pageInfo { hasMore } }
     }`,
    { findOptions: { filter: { and: [{ field: { field: 'title', op: 'LIKE', value: `${RUN}%` } }] } } },
  )
  expect(listAfterDel.q_demo_todo_listMy.items.length === 0, '删除后 list 为空', listAfterDel)

  // ===== 10. logout（refresh token 软删）=====
  const out = await gql(
    'logout',
    `mutation ($input: LogoutInput!) { m_auth_session_logout(input: $input) { success } }`,
    { input: { refreshToken: session.refreshToken } },
  )
  expect(out.m_auth_session_logout.success === true, 'logout success')

  console.log(`\n全部 ${step} 步通过 ✓`)
}

main().catch(() => {
  console.log(`\n失败：${failures} 处（共 ${step} 步）`)
  process.exit(1)
})
