package com.ifmix.core.api.dto.auth

import com.ifmix.core.api.modules.auth.handler.UserDto
import io.mcarle.konvert.api.Konvert
import io.mcarle.konvert.api.Konverter
import io.mcarle.konvert.api.Mapping
import io.mcarle.konvert.api.converter.LONG_TO_INT_CONVERTER

data class ProbeSrc(val expiresIn: Long)
data class ProbeDst(val expiresIn: Int)
data class ProbeSrc2(val accessToken: String, val user: UserDto)
data class ProbeDst2(val accessToken: String, val user: UserInfoRes)

@Konverter
interface AuthKonvertProbe {
    @Konvert(mappings = [Mapping(target = "expiresIn", enable = [LONG_TO_INT_CONVERTER])])
    fun probeA(source: ProbeSrc): ProbeDst

    fun probeB(source: ProbeSrc2): ProbeDst2
}
