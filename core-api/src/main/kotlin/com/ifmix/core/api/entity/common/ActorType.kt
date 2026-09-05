package com.ifmix.core.api.entity.common

/**
 * 主体类型（actor type）。
 *
 * typealias 而非 enum：编译后即 Int，对 Jimmer(KSP)/GraphQL(DGS) 透明，
 * 且蓝绿发布时老节点读到未知 code 只走 when else 降级，不会像 enum 那样崩溃。
 * 全链路 Int 透传（见 AGENTS.md 约定），此别名只提升签名可读性，不带来类型安全。
 *
 * 编码：10=CUSTOMER（C 端）, 20=MANAGER（B 端，规划中）。
 */
typealias ActorType = Int

object ActorTypes {
    const val CUSTOMER: ActorType = 10
    const val MANAGER: ActorType = 20
}
