package com.ifmix.core.api.entity.common

/**
 * IDP（身份提供商）类型。跨 Idp / IdpIdentity 共用，故放 common。
 *
 * typealias 而非 enum：编译后即 Int，对 Jimmer(KSP)/GraphQL(DGS) 透明，
 * 蓝绿发布老节点读到未知 code 走 when else 降级不崩。
 *
 * 编码（0 保留，从 10 起步长 10）：10=APPLE, 20=GOOGLE。
 */
typealias IdpType = Int

object IdpTypes {
    const val APPLE: IdpType = 10
    const val GOOGLE: IdpType = 20
}
