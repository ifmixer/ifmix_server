package com.ifmix.core.api.infra.http

import com.ifmix.core.api.entity.common.ActorTypes
import com.ifmix.core.api.infra.auth.Actor
import com.ifmix.core.api.infra.auth.AuthJwtService
import com.ifmix.core.api.infra.auth.Locales
import com.ifmix.core.api.infra.auth.TokenExpiredException
import com.ifmix.core.api.infra.jimmer.ActionContextHolder
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * RPC 路径唯一解析入口：从 RPC 请求（meta + 真实 request）自包含地构造 [ActionContext]。
 * token 校验、meta 字段校验/归一全部在此（自含实现，无 header 时代适配层）。
 *
 * dev 态（local profile）：meta 在此处经 [DevRpcHeaderAdapter] 与 `Authorization` / `x-req-meta`
 * header 单点合并（header 作底、body 字段级优先，proposal「凭证与 meta 分离」§dev 态输入适配）；
 * prod 不注册适配器，meta 只来自 body。
 *
 * 校验失败即抛 [ApiError]。
 * 日志纪律：meta.accessToken 原文绝不进任何日志/异常 message（各条 message 为固定文案）。
 */
@Component
class ActionContextFactory(
    private val jwt: AuthJwtService,
    @param:Value("\${app.header-validation.strict:true}")
    private val strict: Boolean = true,
    /** 仅 local profile 注册；prod 无 bean 时为 null，fromRpc 行为不变。 */
    private val devHeaderAdapter: DevRpcHeaderAdapter? = null,
) {
    private val log = LoggerFactory.getLogger(ActionContextFactory::class.java)

    /**
     * 从 RPC 请求构造 ActionContext。body.meta 缺失时按空 [RequestMeta] 处理（明文 curl 调试场景）。
     *
     * 显式传 [isMutation] 时校验与 [reqName] 前缀的一致性（`m_` ⇔ 写）——
     * 不一致即抛 [IllegalStateException]（运行时锁死「前缀 ⇔ 读写」关系）。
     * 省略（null）时由 `actionName` 的 `m_` 前缀兜底推导。
     */
    fun fromRpc(
        request: HttpServletRequest,
        reqName: String,
        isMutation: Boolean? = null,
        body: ApiRequestBody<*>,
        requireActorType: ActorRequirement = ActorRequirement.CUSTOMER,
        requireProjectId: Boolean = true,
    ): ActionContext {
        val m = devHeaderAdapter?.merge(request, body?.meta) ?: (body?.meta ?: RequestMeta())

        // 前缀 ⇔ 读写一致性（取代原反射一致性测试的「path ⇔ isMutation」断言，实施单 §1.2-1）。
        val effectiveMutation = isMutation ?: reqName.startsWith("m_")
        if (isMutation != null && reqName.startsWith("m_") != isMutation)
            throw IllegalStateException(
                "actionName prefix mismatch: $reqName vs isMutation=$isMutation",
            )

        // ===== token（自含，直接吃 meta.accessToken；纯 token，无 Bearer 前缀）=====
        val rawToken = m.accessToken?.trim()?.takeIf { it.isNotEmpty() }
        var actor: Actor? = null
        var tokenInstallId: UUID? = null
        var tokenType: Int? = null
        when {
            rawToken == null -> {
                // 无 token：按 ActorRequirement 裁决
                if (requireActorType == ActorRequirement.CUSTOMER)
                    throw ApiError(ErrorCode.UNAUTHORIZED, "authentication required")
            }
            rawToken.startsWith("Bearer ") ->
                // 协议教育优于静默容忍：meta 里只放纯 token
                throw ApiError(ErrorCode.UNAUTHORIZED, "invalid token: send raw token in meta.accessToken")
            else -> {
                val verified = try {
                    jwt.verify(rawToken)
                } catch (_: TokenExpiredException) {
                    throw ApiError(ErrorCode.TOKEN_EXPIRED, "access token expired")
                } ?: throw ApiError(ErrorCode.UNAUTHORIZED, "invalid token: signature verification failed")

                // aud 校验：token 的 projectId(aud) 必须与 meta.projectId 一致，防跨 app 重放 token。
                val metaAppId = m.projectId?.takeIf { it.isNotBlank() }
                val tokenAppId = verified.projectId
                if (tokenAppId != null && metaAppId != null && tokenAppId != metaAppId)
                    throw ApiError(ErrorCode.UNAUTHORIZED, "invalid token: app mismatch")

                tokenType = verified.tokenType
                if (verified.tokenType == AuthJwtService.TOKEN_TYPE_INSTALL) {
                    // install token 只提供可信 iid，不是 actor，也没有 sub。
                    tokenInstallId = verified.installId?.let { tryUuid(it) }
                    if (requireActorType == ActorRequirement.CUSTOMER)
                        throw ApiError(ErrorCode.UNAUTHORIZED, "customer authentication required")
                } else {
                    // customer token 也携带 iid claim（type=5 与 type=10 的 token 都带 iid claim：
                    // 不提取则 RPC 下 mustGetTokenInstallId 恒 401000）。
                    tokenInstallId = verified.installId?.let { tryUuid(it) }
                    val actorId = verified.actorId?.let { tryUuid(it) }
                        ?: throw ApiError(ErrorCode.UNAUTHORIZED, "invalid token: missing or invalid subject")
                    actor = Actor(
                        actorId = actorId,
                        actorType = verified.actorType,
                        anonymous = verified.anonymous,
                        sessionId = verified.sessionId,
                    )
                    // customer token 也可能携带 iid claim（同上，type=5 与 type=10 都取）。
                    // scan/DR 的 install 层限流与 mustGetTokenInstallId 依赖它。
                    if (requireActorType == ActorRequirement.CUSTOMER && verified.actorType == ActorTypes.MANAGER)
                        throw ApiError(ErrorCode.FORBIDDEN, "actor type not allowed for this endpoint")
                }
            }
        }

        // ===== projectId（meta 通道）=====
        val projectId = m.projectId?.takeIf { it.isNotBlank() }
        if (requireProjectId && projectId == null)
            throw ApiError(ErrorCode.INVALID_REQUEST, "projectId is required")
        if (projectId != null && !projectId.matches(PROJECT_ID_RE))
            throw ApiError(ErrorCode.INVALID_REQUEST, "invalid projectId format")

        // ===== locale / currency / country（格式软校验，strict 控制严格程度）=====
        // locale：归一到受支持集，不在支持集/无法识别 → null（不抛错，线上语义）。
        val locale = m.locale?.takeIf { it.isNotBlank() }?.let { Locales.normalizeLocale(it) }
        val currency = m.currency?.takeIf { it.isNotBlank() }?.let { raw ->
            val up = raw.uppercase()
            if (up.matches(CURRENCY_RE)) up else onBadFormat("currency", raw, "invalid format")
        }
        val country = m.country?.takeIf { it.isNotBlank() }?.let { raw ->
            val up = raw.uppercase()
            if (up.matches(COUNTRY_RE)) up else onBadFormat("country", raw, "invalid format")
        }

        // ===== clientPlatform（meta 通道）=====
        // ClientPlatform.fromHeader 对非法值抛 IAE（实测行为；空值返回 null），转软校验：
        // strict 抛 INVALID_REQUEST；否则 WARN + null（当作未提供）。
        val platformRaw = m.clientPlatform?.takeIf { it.isNotBlank() }
        val clientPlatform = platformRaw?.let { raw ->
            try {
                ClientPlatform.fromHeader(raw)
            } catch (_: IllegalArgumentException) {
                if (strict) throw ApiError(ErrorCode.INVALID_REQUEST, "invalid client platform: $raw")
                log.warn("bad meta clientPlatform ignored. value={}", raw)
                null
            }
        }

        // ===== 边缘注入信号（永不走 meta，取自真实 request）=====
        val clientIp = ClientIpResolver.resolve(request)
        val botScore = request.getHeader(RequestHeaders.CF_BOT_SCORE)?.trim()?.toIntOrNull()?.takeIf { it in 1..99 }

        // ===== requestId（meta.reqId 优先，缺省回落 x-req-id header 通道）=====
        val requestId = m.reqId?.takeIf { it.isNotBlank() } ?: LogContext.requestId(request)
        // 回写 attribute：GlobalExceptionHandler 错误路径构造 Envelope.reqId（factory 抛出/后续异常均可读到）。
        request.setAttribute(RequestHeaders.PARSED_REQ_ID_ATTR, requestId)

        // ===== meta 独有字段 / 版本字段（仅记录用途，无格式校验）=====
        val userTz = m.userTz?.trim()?.takeIf { it.isNotEmpty() }
        val deviceModel = m.deviceModel?.trim()?.takeIf { it.isNotEmpty() }
        val osVersion = m.osVersion?.trim()?.takeIf { it.isNotEmpty() }
        val appVersion = m.appVersion?.trim()?.takeIf { it.isNotEmpty() }
        val otaVersion = m.otaVersion?.trim()?.takeIf { it.isNotEmpty() }

        val ctx = ActionContext(
            projectId = projectId,
            actorId = actor?.actorId,
            actorType = actor?.actorType,
            anonymous = actor?.anonymous ?: false,
            sessionId = actor?.sessionId,
            locale = locale,
            currency = currency,
            country = country,
            clientPlatform = clientPlatform,
            clientIp = clientIp,
            installId = null,            // RPC 协议没有不可信 x-install-id 信源
            tokenInstallId = tokenInstallId,
            tokenType = tokenType,
            requestId = requestId,
            botScore = botScore,
            appVersion = appVersion,
            otaVersion = otaVersion,
            userTz = userTz,
            deviceModel = deviceModel,
            osVersion = osVersion,
            meta = m,
            actionName = reqName,
            isMutation = effectiveMutation,
            preferReader = !effectiveMutation,
        )
        ActionContextHolder.set(ctx)
        LogContext.bind(ctx, request)
        return ctx
    }

    /**
     * 格式软校验失败的统一处理：strict 抛错；否则打 WARN 并返回 null（当作未提供）。
     * value 只可能是 locale/currency/country/clientPlatform 这类非敏感字段（token 绝不进日志）。
     */
    private fun onBadFormat(field: String, value: String, reason: String): Nothing? {
        if (strict) throw ApiError(ErrorCode.INVALID_REQUEST, "invalid meta.$field: $reason")
        log.warn("bad meta field ignored. field={} value={} reason={}", field, value, reason)
        return null
    }

    companion object {
        /** project slug 主键：小写字母开头，小写字母/数字/连字符，3-30 字符。创建后不可变。 */
        private val PROJECT_ID_RE = Regex("^[a-z][a-z0-9-]{2,29}$")
        /** ISO 4217（大写后校验）。 */
        private val CURRENCY_RE = Regex("^[A-Z]{3}$")
        /** ISO 3166-1 alpha-2（大写后校验）。 */
        private val COUNTRY_RE = Regex("^[A-Z]{2}$")

        /** 非 UUID 格式 → null（不抛）。 */
        private fun tryUuid(s: String): UUID? = try { UUID.fromString(s) } catch (_: Exception) { null }
    }
}
