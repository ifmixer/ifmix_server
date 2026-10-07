package com.ifmix.core.api.infra.auth

import com.ifmix.core.api.entity.common.ActorTypes
import com.ifmix.core.api.infra.http.ApiError
import com.ifmix.core.api.infra.http.ErrorCode
import com.ifmix.core.api.infra.http.RequestMeta
import com.ifmix.core.api.infra.http.RequestHeaders
import com.ifmix.core.api.infra.http.WireCryptoFilter
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.mock.web.MockHttpServletRequest
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * RequestParser 单测：解析 + 校验（抛错）全在 parser 内。
 * 两个信源：加密通道 = 解密 body（ATTR_DECRYPTED_BODY，信封 `{authorization, meta, query, variables}`）；
 * 明文 dev 通道（wire mode=optional）= `x-req-meta` / `Authorization` header。
 */
class RequestParserTest {
    private val jwt = mock<AuthJwtService>()
    private val mapper = JsonMapper.builder().build()

    /** optional 模式：header 回落可用（与 local profile 一致）。 */
    private val parser = RequestParser(jwt, mapper, wireMode = "optional")
    /** required 模式：headers 一律不读（线上语义）。 */
    private val requiredParser = RequestParser(jwt, mapper, wireMode = "required")
    private val lenient = RequestParser(jwt, mapper, wireMode = "optional", strict = false)

    private val projectId = "test-app"
    private val actorId = "00000000-0000-0000-0000-000000000001"

    /** 加密通道 request：解密 body 缓存到 ATTR_DECRYPTED_BODY（模拟 WireCryptoFilter）。 */
    private fun bodyReq(payload: String, vararg headers: Pair<String, String>) =
        MockHttpServletRequest().apply {
            headers.forEach { (k, v) -> addHeader(k, v) }
            setAttribute(WireCryptoFilter.ATTR_DECRYPTED_BODY, payload.toByteArray())
        }

    /** 明文 dev 通道 request：无加密 body，只有 headers。 */
    private fun plainReq(vararg headers: Pair<String, String>) =
        MockHttpServletRequest().apply { headers.forEach { (k, v) -> addHeader(k, v) } }

    /** 加密信封便捷构造。 */
    private fun envelope(meta: String? = null, authorization: String? = null, rest: String = ""): String {
        val parts = mutableListOf<String>()
        authorization?.let { parts.add(""""authorization":"$it"""") }
        meta?.let { parts.add(""""meta":$it""") }
        if (rest.isNotEmpty()) parts.add(rest)
        return "{${parts.joinToString(",")}}"
    }

    private fun bearer(v: VerifiedToken?) = whenever(jwt.verify("tok")).thenReturn(v)

    // ── parseMeta：projectId ──
    @Test fun `projectId valid`() {
        assertThat(parser.parseMeta(bodyReq(envelope(meta = """{"projectId":"$projectId"}"""))).projectId).isEqualTo(projectId)
    }
    @Test fun `projectId malformed throws (hard, strict-independent)`() {
        assertThrows<ApiError> { parser.parseMeta(bodyReq(envelope(meta = """{"projectId":"1bad"}"""))) }
        assertThrows<ApiError> { lenient.parseMeta(bodyReq(envelope(meta = """{"projectId":"1bad"}"""))) }
    }
    @Test fun `projectId absent null`() {
        assertThat(parser.parseMeta(bodyReq("""{"query":1}""")).projectId).isNull()
    }

    // ── parseMeta：clientPlatform（硬校验）──
    @Test fun `clientPlatform malformed throws even lenient`() {
        assertThrows<ApiError> { parser.parseMeta(bodyReq(envelope(meta = """{"clientPlatform":"BOGUS"}"""))) }
        assertThrows<ApiError> { lenient.parseMeta(bodyReq(envelope(meta = """{"clientPlatform":"BOGUS"}"""))) }
    }
    @Test fun `clientPlatform valid kept as-is`() {
        assertThat(parser.parseMeta(bodyReq(envelope(meta = """{"clientPlatform":"android"}"""))).clientPlatform).isEqualTo("android")
    }
    @Test fun `clientPlatform absent null`() {
        assertThat(parser.parseMeta(bodyReq("""{}""")).clientPlatform).isNull()
    }

    // ── parseMeta：locale / currency / country 归一 + 校验 ──
    @Test fun `currency normalized to uppercase`() {
        assertThat(parser.parseMeta(bodyReq(envelope(meta = """{"currency":"usd"}"""))).currency).isEqualTo("USD")
    }
    @Test fun `currency malformed throws`() {
        assertThrows<ApiError> { parser.parseMeta(bodyReq(envelope(meta = """{"currency":"usdd"}"""))) }
    }
    @Test fun `country normalized to uppercase`() {
        assertThat(parser.parseMeta(bodyReq(envelope(meta = """{"country":"cn"}"""))).country).isEqualTo("CN")
    }
    @Test fun `country malformed throws`() {
        assertThrows<ApiError> { parser.parseMeta(bodyReq(envelope(meta = """{"country":"CHN"}"""))) }
    }
    @Test fun `locale normalized to supported set`() {
        val meta = parser.parseMeta(bodyReq(envelope(meta = """{"locale":"zh-cn"}""")))
        assertThat(meta.locale).isEqualTo("zh-CN")
        assertThat(parser.parseMeta(bodyReq(envelope(meta = """{"locale":"en-US"}"""))).locale).isEqualTo("en")
        assertThat(parser.parseMeta(bodyReq(envelope(meta = """{"locale":"pt-BR"}"""))).locale).isEqualTo("pt")
        assertThat(parser.parseMeta(bodyReq(envelope(meta = """{"locale":"ja-JP"}"""))).locale).isEqualTo("ja")
    }
    @Test fun `locale chinese simplified vs traditional`() {
        fun localeOf(v: String) = parser.parseMeta(bodyReq(envelope(meta = """{"locale":"$v"}"""))).locale
        // 简体
        assertThat(localeOf("zh")).isEqualTo("zh-CN")
        assertThat(localeOf("zh-Hans")).isEqualTo("zh-CN")
        assertThat(localeOf("zh-SG")).isEqualTo("zh-CN")
        // 繁体
        assertThat(localeOf("zh-TW")).isEqualTo("zh-TW")
        assertThat(localeOf("zh-HK")).isEqualTo("zh-TW")
        assertThat(localeOf("zh-Hant-HK")).isEqualTo("zh-TW")
    }
    @Test fun `locale outside collapse set passes through as-is (all languages supported)`() {
        // 归一集之外的语言不归并、不丢弃：原样透传
        assertThat(parser.parseMeta(bodyReq(envelope(meta = """{"locale":"ko"}"""))).locale).isEqualTo("ko")
        assertThat(parser.parseMeta(bodyReq(envelope(meta = """{"locale":"ru-RU"}"""))).locale).isEqualTo("ru-RU")
        assertThat(parser.parseMeta(bodyReq(envelope(meta = """{"locale":"th-TH"}"""))).locale).isEqualTo("th-TH")
    }
    @Test fun `optional field absent returns null`() {
        assertThat(parser.parseMeta(bodyReq("""{}""")).currency).isNull()
    }

    // ── parseMeta：version 原样透传（不校验格式）──
    @Test fun `version fields pass through as-is`() {
        val meta = parser.parseMeta(bodyReq(envelope(meta = """{"appVersion":"v1.2-beta","otaVersion":"1-23-3"}""")))
        assertThat(meta.appVersion).isEqualTo("v1.2-beta")
        assertThat(meta.otaVersion).isEqualTo("1-23-3")
        assertThat(parser.parseMeta(bodyReq("""{}""")).appVersion).isNull()
    }

    // ── parseMeta：信源选择（body 优先 / header 回落仅 optional / required 不读 header）──
    @Test fun `header fallback parses x-req-meta in optional mode`() {
        val meta = parser.parseMeta(plainReq(RequestHeaders.REQ_META to """{"projectId":"$projectId","currency":"usd"}"""))
        assertThat(meta.projectId).isEqualTo(projectId)
        assertThat(meta.currency).isEqualTo("USD")
    }
    @Test fun `required mode ignores x-req-meta header`() {
        assertThat(requiredParser.parseMeta(plainReq(RequestHeaders.REQ_META to """{"projectId":"$projectId"}""")).projectId).isNull()
    }
    @Test fun `body meta wins, header ignored when body present`() {
        val meta = parser.parseMeta(
            bodyReq(envelope(meta = """{"projectId":"$projectId"}"""), RequestHeaders.REQ_META to """{"projectId":"header-app"}"""),
        )
        assertThat(meta.projectId).isEqualTo(projectId)
    }
    @Test fun `invalid x-req-meta json throws 400000`() {
        val ex = assertThrows<ApiError> { parser.parseMeta(plainReq(RequestHeaders.REQ_META to "not-json")) }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.INVALID_REQUEST)
    }
    @Test fun `meta structural violations throw 400000`() {
        assertThrows<ApiError> { parser.parseMeta(bodyReq(envelope(meta = """"x""""))) }          // meta 非对象
        assertThrows<ApiError> { parser.parseMeta(bodyReq(envelope(meta = """{"locale":{"nested":1}}"""))) } // 值非字符串
        assertThrows<ApiError> { parser.parseMeta(bodyReq("""{"meta":{"big":"${"x".repeat(9000)}"}}""")) }   // 超限
    }
    @Test fun `meta parsed once per request (cached on attribute)`() {
        val r = bodyReq(envelope(meta = """{"projectId":"$projectId"}"""))
        assertThat(parser.parseMeta(r).projectId).isEqualTo(projectId)
        // 第二次直接读缓存：换掉 body 也不影响
        r.setAttribute(WireCryptoFilter.ATTR_DECRYPTED_BODY, envelope(meta = """{"projectId":"other"}""").toByteArray())
        assertThat(parser.parseMeta(r).projectId).isEqualTo(projectId)
    }
    @Test fun `no body and no header returns empty meta (required mode too)`() {
        assertThat(parser.parseMeta(plainReq()).projectId).isNull()
        assertThat(requiredParser.parseMeta(plainReq()).projectId).isNull()
    }
    @Test fun `reqId sanitized (control chars stripped, trimmed, length capped)`() {
        fun reqIdOf(v: String) = parser.parseMeta(bodyReq(envelope(meta = """{"reqId":"$v"}"""))).reqId
        assertThat(reqIdOf("app-AbC_123")).isEqualTo("app-AbC_123")
        assertThat(reqIdOf("a b\\nINFO fake")).isEqualTo("a bINFO fake")
        assertThat(reqIdOf("x".repeat(500))!!.length).isEqualTo(128)
        assertThat(parser.parseMeta(bodyReq(envelope(meta = """{"reqId":"   "}"""))).reqId).isNull()
    }

    // ── 线上（strict=false）：格式非法不报错，返回 null（打 WARN log）──
    @Test fun `lenient mode does not throw on bad format, returns null`() {
        assertThat(lenient.parseMeta(bodyReq(envelope(meta = """{"currency":"usdd"}"""))).currency).isNull()
        assertThat(lenient.parseMeta(bodyReq(envelope(meta = """{"country":"CHN"}"""))).country).isNull()
        assertThat(lenient.parseMeta(bodyReq(envelope(meta = """{"locale":"!!bad"}"""))).locale).isNull()
    }
    @Test fun `lenient mode still returns valid values normally`() {
        val meta = lenient.parseMeta(bodyReq(envelope(meta = """{"currency":"usd","locale":"zh-cn"}""")))
        assertThat(meta.currency).isEqualTo("USD")
        assertThat(meta.locale).isEqualTo("zh-CN")
    }
    @Test fun `strict mode throws on malformed locale`() {
        // 无法解析出 language subtag → 格式非法 → strict 抛
        assertThrows<ApiError> { parser.parseMeta(bodyReq(envelope(meta = """{"locale":"!!bad"}"""))) }
    }
    @Test fun `lenient mode malformed locale returns null`() {
        assertThat(lenient.parseMeta(bodyReq(envelope(meta = """{"locale":"!!bad"}"""))).locale).isNull()
    }

    // ── parseAuthorization：信源与 Bearer 剥离 ──
    @Test fun `authorization from encrypted body, bearer stripped`() {
        assertThat(parser.parseAuthorization(bodyReq(envelope(authorization = "Bearer tok")))).isEqualTo("tok")
        assertThat(parser.parseAuthorization(bodyReq(envelope(authorization = "bearer tok")))).isEqualTo("tok")
        assertThat(parser.parseAuthorization(bodyReq(envelope(authorization = "tok")))).isEqualTo("tok")
        assertThat(parser.parseAuthorization(bodyReq(envelope(authorization = "Bearer")))).isNull()
    }
    @Test fun `authorization absent in body returns null`() {
        assertThat(parser.parseAuthorization(bodyReq("""{"query":1}"""))).isNull()
    }
    @Test fun `authorization non-string throws`() {
        assertThrows<ApiError> { parser.parseAuthorization(bodyReq("""{"authorization":9}""")) }
    }
    @Test fun `authorization header fallback only in optional mode`() {
        assertThat(parser.parseAuthorization(plainReq("Authorization" to "Bearer header-tok"))).isEqualTo("header-tok")
        assertThat(requiredParser.parseAuthorization(plainReq("Authorization" to "Bearer header-tok"))).isNull()
    }
    @Test fun `body authorization wins over header`() {
        assertThat(
            parser.parseAuthorization(
                bodyReq(envelope(authorization = "body-tok")).apply { addHeader("Authorization", "Bearer header-tok") },
            ),
        ).isEqualTo("body-tok")
    }

    // ── parseToken：抛错全在此（token 来自 parseAuthorization）──
    @Test fun `no token and requireActorType (login required) throws UNAUTHORIZED`() {
        val ex = assertThrows<ApiError> {
            parser.parseToken(bodyReq(envelope(meta = """{"projectId":"$projectId"}""")), requireActorType = ActorTypes.CUSTOMER)
        }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.UNAUTHORIZED)
    }
    @Test fun `no token and not required returns null`() {
        assertThat(parser.parseToken(bodyReq(envelope(meta = """{"projectId":"$projectId"}""")), requireActorType = null)).isNull()
    }
    @Test fun `valid token returns VerifiedToken`() {
        bearer(VerifiedToken(actorId = actorId, projectId = projectId, actorType = ActorTypes.CUSTOMER, anonymous = true))
        val a = parser.parseToken(
            bodyReq(envelope(meta = """{"projectId":"$projectId"}""", authorization = "tok")),
            requireActorType = ActorTypes.CUSTOMER,
        )!!
        assertThat(a.actorId).isEqualTo(actorId)
        assertThat(a.anonymous).isTrue()
    }
    @Test fun `expired token throws TOKEN_EXPIRED regardless of require`() {
        whenever(jwt.verify("tok")).thenThrow(TokenExpiredException())
        val ex = assertThrows<ApiError> { parser.parseToken(bodyReq(envelope(authorization = "tok")), requireActorType = null) }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.TOKEN_EXPIRED)
    }
    @Test fun `invalid token throws UNAUTHORIZED`() {
        bearer(null)
        val ex = assertThrows<ApiError> { parser.parseToken(bodyReq(envelope(authorization = "tok")), requireActorType = null) }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.UNAUTHORIZED)
    }

    @Test fun `install token yields credential-only Actor when actor is optional`() {
        bearer(VerifiedToken(
            actorId = null,
            projectId = projectId,
            actorType = ActorTypes.CUSTOMER,
            tokenType = AuthJwtService.TOKEN_TYPE_INSTALL,
            installId = "00000000-0000-0000-0000-000000000002",
        ))
        val a = parser.parseToken(
            bodyReq(envelope(meta = """{"projectId":"$projectId"}""", authorization = "tok")),
            requireActorType = null,
        )!!
        // install token 不是登录主体：actorId=null，但凭证信息（iid/type）随 Actor 带出
        assertThat(a.actorId).isNull()
        assertThat(a.installId).isEqualTo("00000000-0000-0000-0000-000000000002")
        assertThat(a.tokenType).isEqualTo(AuthJwtService.TOKEN_TYPE_INSTALL)
    }

    @Test fun `install token without subject is rejected when customer actor is required`() {
        bearer(VerifiedToken(
            actorId = null,
            projectId = projectId,
            actorType = ActorTypes.CUSTOMER,
            tokenType = AuthJwtService.TOKEN_TYPE_INSTALL,
            installId = "00000000-0000-0000-0000-000000000002",
        ))
        val ex = assertThrows<ApiError> {
            parser.parseToken(
                bodyReq(envelope(meta = """{"projectId":"$projectId"}""", authorization = "tok")),
                requireActorType = ActorTypes.CUSTOMER,
            )
        }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.UNAUTHORIZED)
    }

    @Test fun `customer token with iid claim exposes installId on Actor`() {
        bearer(VerifiedToken(
            actorId = actorId,
            projectId = projectId,
            actorType = ActorTypes.CUSTOMER,
            tokenType = AuthJwtService.TOKEN_TYPE_CUSTOMER,
            installId = "00000000-0000-0000-0000-000000000002",
        ))
        val a = parser.parseToken(
            bodyReq(envelope(meta = """{"projectId":"$projectId"}""", authorization = "tok")),
            requireActorType = ActorTypes.CUSTOMER,
        )!!
        assertThat(a.actorId).isEqualTo(actorId)
        assertThat(a.installId).isEqualTo("00000000-0000-0000-0000-000000000002")
        assertThat(a.tokenType).isEqualTo(AuthJwtService.TOKEN_TYPE_CUSTOMER)
    }

    @Test fun `token aud mismatch (cross-app) throws UNAUTHORIZED`() {
        // token.aud != meta.projectId → UNAUTHORIZED（防跨 app 重放）
        bearer(VerifiedToken(actorId = actorId, projectId = "other-app", actorType = ActorTypes.CUSTOMER))
        val ex = assertThrows<ApiError> {
            parser.parseToken(
                bodyReq(envelope(meta = """{"projectId":"$projectId"}""", authorization = "tok")),
                requireActorType = ActorTypes.CUSTOMER,
            )
        }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.UNAUTHORIZED)
    }
    @Test fun `wrong actorType throws FORBIDDEN`() {
        bearer(VerifiedToken(actorId = actorId, projectId = projectId, actorType = ActorTypes.MANAGER))
        val ex = assertThrows<ApiError> {
            parser.parseToken(
                bodyReq(envelope(meta = """{"projectId":"$projectId"}""", authorization = "tok")),
                requireActorType = ActorTypes.CUSTOMER,
            )
        }
        assertThat(ex.errorCode).isEqualTo(ErrorCode.FORBIDDEN)
    }

    // ── checkActorRequirement：复用缓存 ctx 时的复检语义 ──
    @Test fun `checkActorRequirement enforces login and type`() {
        parser.checkActorRequirement(UUID.fromString(actorId), ActorTypes.CUSTOMER, ActorTypes.CUSTOMER) // ok
        val ex1 = assertThrows<ApiError> { parser.checkActorRequirement(null, null, ActorTypes.CUSTOMER) }
        assertThat(ex1.errorCode).isEqualTo(ErrorCode.UNAUTHORIZED)
        val ex2 = assertThrows<ApiError> { parser.checkActorRequirement(UUID.fromString(actorId), ActorTypes.MANAGER, ActorTypes.CUSTOMER) }
        assertThat(ex2.errorCode).isEqualTo(ErrorCode.FORBIDDEN)
        parser.checkActorRequirement(null, null, null) // 不要求 → 不抛
    }

    // ── peekActorId：不抛 ──
    @Test fun `peekActorId returns actorId for valid, null otherwise`() {
        bearer(VerifiedToken(actorId = actorId, projectId = projectId, actorType = ActorTypes.CUSTOMER))
        assertThat(parser.peekActorId(bodyReq(envelope(meta = """{"projectId":"$projectId"}""", authorization = "tok"))))
            .isEqualTo(UUID.fromString(actorId))
        assertThat(parser.peekActorId(plainReq())).isNull()
    }
}
