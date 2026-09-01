// Standalone verification of 阶段4 login decision table (R1).
// Mirrors AuthAggHandler.Companion.decideLoginAction EXACTLY.
// Run: kotlinc -script docs/plans/verify_login_decision.kts
import java.util.UUID

sealed interface LoginAction {
    data object PromoteOrCreate : LoginAction
    data class NoOp(val owner: UUID) : LoginAction
    data class Merge(val fromId: UUID, val toId: UUID) : LoginAction
    data object Conflict : LoginAction
}

// === mirrors production decideLoginAction (field names adapted for script scope) ===
fun decideLoginAction(cur: UUID?, curAnonymous: Boolean, existing: UUID?): LoginAction = when {
    existing == null -> LoginAction.PromoteOrCreate
    existing == cur -> LoginAction.NoOp(existing)
    cur != null && curAnonymous -> LoginAction.Merge(fromId = cur, toId = existing)
    else -> LoginAction.Conflict
}
// =======================================

fun check(name: String, cond: Boolean) {
    if (!cond) { System.err.println("FAIL: $name"); kotlin.system.exitProcess(1) }
    println("PASS: $name")
}

val cur = UUID.randomUUID()
val existing = UUID.randomUUID()

// ① relation 不存在 → 转正/新建（含 cur==null 兼容）
check("branch1 anon promote", decideLoginAction(cur, true, null) == LoginAction.PromoteOrCreate)
check("branch1 non-anon promote", decideLoginAction(cur, false, null) == LoginAction.PromoteOrCreate)
check("branch1 cur=null create", decideLoginAction(null, true, null) == LoginAction.PromoteOrCreate)

// ② existing == cur → NoOp
val b2 = decideLoginAction(cur, true, cur)
check("branch2 noop type", b2 is LoginAction.NoOp)
check("branch2 noop owner", (b2 as LoginAction.NoOp).owner == cur)

// ③ cur 匿名 & existing != cur → Merge，方向 fromId=cur → toId=existing（R1）
val b3 = decideLoginAction(cur, true, existing)
check("branch3 merge type", b3 is LoginAction.Merge)
val m3 = b3 as LoginAction.Merge
check("branch3 R1 from=cur", m3.fromId == cur)
check("branch3 R1 to=existing", m3.toId == existing)

// ④ cur 非匿名 & existing != cur → Conflict
check("branch4 conflict", decideLoginAction(cur, false, existing) == LoginAction.Conflict)

// R1 穷举：合并方向绝不反向
repeat(50) {
    val a = UUID.randomUUID(); val b = UUID.randomUUID()
    val m = decideLoginAction(a, true, b) as LoginAction.Merge
    check("R1 fuzz from", m.fromId == a)
    check("R1 fuzz to", m.toId == b)
}

println("ALL DECISION-TABLE CHECKS PASSED")
