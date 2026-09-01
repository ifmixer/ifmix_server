// Standalone verification for AnonymousCleanupDecision.shouldDelete (phase 6).
// Runs against compiled main classes — bypasses the historically-rotten test source set.
// Run: see scripts/verify_anon_cleanup.sh
import com.ifmix.api.core.modules.customer.handler.AnonymousCleanupDecision
import com.ifmix.api.core.modules.customer.handler.AnonymousCleanupDecision.CustomerState

fun st(
    anonymous: Boolean = false,
    merged: Boolean = false,
    tombstoneExpired: Boolean = false,
    hasValidToken: Boolean = false,
    hasActiveSubscription: Boolean = false,
) = CustomerState(anonymous, merged, tombstoneExpired, hasValidToken, hasActiveSubscription)

fun check(name: String, expected: Boolean, s: CustomerState) {
    val actual = AnonymousCleanupDecision.shouldDelete(s)
    if (actual != expected) {
        System.err.println("FAIL: $name  expected=$expected actual=$actual  state=$s")
        failures++
    } else {
        println("ok: $name -> $actual")
    }
}

var failures = 0

fun main() {
    // 应删：未合并匿名僵尸（无有效 token）
    check("anon zombie no token", true, st(anonymous = true, hasValidToken = false))
    // 保留：匿名但有有效 token（仍可 attach）
    check("anon with token", false, st(anonymous = true, hasValidToken = true))
    // 应删：已合并 tombstone 超窗（anonymous 值无关）
    check("tombstone expired (anon=false)", true, st(merged = true, tombstoneExpired = true))
    check("tombstone expired (anon=true)", true, st(anonymous = true, merged = true, tombstoneExpired = true))
    // 保留：tombstone 未超窗
    check("tombstone within window", false, st(merged = true, tombstoneExpired = false))
    // 绝不删：正常已登录用户（anonymous=false 且未合并）
    check("normal user no token", false, st(anonymous = false, merged = false, hasValidToken = false))
    check("normal user with token", false, st(anonymous = false, merged = false, hasValidToken = true))
    // active 订阅一律跳过
    check("zombie + active sub", false, st(anonymous = true, hasValidToken = false, hasActiveSubscription = true))
    check("tombstone + active sub", false, st(merged = true, tombstoneExpired = true, hasActiveSubscription = true))

    if (failures > 0) {
        System.err.println("=== $failures assertion(s) FAILED ===")
        kotlin.system.exitProcess(1)
    }
    println("=== all 9 assertions PASSED ===")
}
