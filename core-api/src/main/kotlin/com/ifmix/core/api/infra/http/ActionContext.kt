package com.ifmix.core.api.infra.http

import com.ifmix.core.api.entity.common.ActorType
import com.ifmix.core.api.infra.auth.AuthJwtService
import org.babyfish.jimmer.sql.kt.KSqlClient
import java.util.UUID

/**
 * 操作上下文 — per-action。
 *
 * 两种构造来源：
 *  - RPC 请求：由 [ActionContextFactory.fromRpc] 消费 `{"meta": …, "input": …}` 信封与 HTTP header
 *    解析/校验（失败即抛 ApiError）后构造，持有已解析的值。
 *  - webhook / 内部调用：直接构造并塞入 projectId/actorId（无 HTTP 请求）。
 *
 * 只持有「已解析成功的值」，不含错误状态——token 过期/无效等在 fromRpc 解析+校验时即时抛 ApiError。
 */
data class ActionContext(
    val projectId: String? = null,
    /** 主体 id（token sub）。null = 未认证。 */
    val actorId: UUID? = null,
    /** 主体类型：10=customer / 20=manager。未认证时 null。 */
    val actorType: ActorType? = null,
    /** 是否匿名主体（token ano claim）。 */
    val anonymous: Boolean = true,
    /** sessionId（token sid claim）= 签发该 access token 的 refresh token id。为将来 Redis session 预留；未认证/旧 token 时 null。 */
    val sessionId: String? = null,
    val locale: String? = null,
    val currency: String? = null,
    val country: String? = null,
    val clientPlatform: ClientPlatform? = null,
    val clientIp: String? = null,
    /** 安装标识（x-install-id header）。客户端生成，仅记录用于分析，不用于鉴权。 */
    val installId: String? = null,
    /** token 的 iid claim（可信 installId）。install token 或 customer token 携带；无则 null。用于关系维护/updateInstall。 */
    val tokenInstallId: UUID? = null,
    /** token 的 type claim：5=install / 10=customer / 20=manager。无 token 时 null。 */
    val tokenType: Int? = null,
    /** 请求 id（客户端 x-req-id 原值，或服务端生成的 UUID；客户端 x-req-id 或服务端生成，响应头 x-req-id 返回）。 */
    val requestId: String? = null,
    /** Cloudflare bot score（cf-bot-score header，1-99，越低越像 bot）。仅记录用途。 */
    val botScore: Int? = null,
    /** 客户端版本（x-app-version / x-ota-version）。仅记录用途，格式软校验。 */
    val appVersion: String? = null,
    /** x-ota-version：热更新版本号，形如 `1-23-3`（runtimeVersion-buildNumber-otaSeq）。原样透传。 */
    val otaVersion: String? = null,
    /** IANA 时区名（RPC meta 独有，可选，仅记录用途，无格式校验）。 */
    val userTz: String? = null,
    /** 设备型号（RPC meta 独有，可选，仅记录用途）。 */
    val deviceModel: String? = null,
    /** 操作系统版本（RPC meta 独有，可选，仅记录用途）。 */
    val osVersion: String? = null,
    // ===== 操作元信息 =====
    val actionName: String? = null,
    val isMutation: Boolean = false,
    /** true = 优先走 reader；mutation 时默认为 false，query 时默认为 true */
    val preferReader: Boolean = !isMutation,
    /**
     * [ActionContextFactory.fromRpc] 解析用的 raw [RequestMeta] 实例（body.meta，缺失时 [RequestMeta] 空对象）。
     *
     * 分界（2026-10-06）：
     * - 透传型 meta 字段（遥测类，无校验/归一逻辑）走 `ctx.meta.xxx` 直读；以后新增此类字段
     *   只改 [RequestMeta] 一处。
     * - 有校验/归一逻辑的字段（locale/currency/country/clientPlatform/projectId）仍由 factory
     *   出派生字段（`ctx.locale` 等），**禁止业务层绕过 `ctx.locale` 直读 `ctx.meta.locale`**。
     *
     * 非 factory 构造点（webhook/内部调用/测试）保持默认空 [RequestMeta]。
     */
    val meta: RequestMeta = RequestMeta(),
    // ===== 全局事务支持 =====
    /**
     * 全局事务 KSqlClient（由 GlobalTxRunner 在 DataFetcher 层设置）。
     * ModuleCtxFactory 构建 ModuleCtx 时：若已有全局事务则复用，否则走 router 选择。
     */
    val globalTxSql: KSqlClient? = null,
    val inGlobalTx: Boolean = false,
) {
    /** true = 允许读缓存。mutation 时为 false，避免脏读。 */
    val readCache get() = !isMutation

    /**
     * 每条日志附带的请求上下文字段（经 [LogContext] 逐项写入 MDC，JSON 日志里各成一个顶层字段）。
     * 以后要加打印字段只在这里加一项；null 不输出。key 用短名，避免与 logstash 内置字段（message/level…）冲突。
     */
    fun logFields(): Map<String, Any?> = linkedMapOf(
        "rid" to requestId,
        "pid" to projectId,
        "iid" to tokenInstallId,
        "cid" to actorId,
        "ip" to clientIp,
        "bot" to botScore,
        "plat" to clientPlatform,
        "av" to appVersion,
        "ov" to otaVersion,
        "loc" to locale,
        "cur" to currency,
        "cty" to country,
        "tz" to userTz,
        "dm" to deviceModel,
        "os" to osVersion,
    )

    fun mustGetProjectId() = projectId ?: throw ApiError(ErrorCode.INVALID_REQUEST, "projectId is required")
    fun mustGetActorId() = actorId ?: throw ApiError(ErrorCode.UNAUTHORIZED, "authentication required")

    /**
     * 可信 installId（token iid）。Customer 业务写入统一走此。
     * createAnonymous / refresh 也走此：只要求携带有效可信 iid（token 类型不限——install token 或
     * 含 iid 的 customer token 皆可），无有效 iid → UNAUTHORIZED（缺少可信 install 上下文，拒绝写入）。
     */
    fun mustGetTokenInstallId() = tokenInstallId ?: throw ApiError(ErrorCode.UNAUTHORIZED, "trusted install id required")

    /**
     * login 入口：必须携带 iid，允许两类上下文——
     *  - 当前 customer token（type=10、有 actor）：保留 promote/merge 上下文；
     *  - install token（type=5、无 actor）：无现有 session，创建/绑定最终 owner。
     * manager token / 无 token / 无 iid 一律 UNAUTHORIZED。返回可信 iid。
     */
    fun mustGetLoginInstallId(): UUID {
        val ok = when (tokenType) {
            AuthJwtService.TOKEN_TYPE_CUSTOMER -> actorId != null
            AuthJwtService.TOKEN_TYPE_INSTALL -> actorId == null
            else -> false
        }
        if (!ok) throw ApiError(ErrorCode.UNAUTHORIZED, "login requires customer or install token")
        return tokenInstallId ?: throw ApiError(ErrorCode.UNAUTHORIZED, "login requires trusted install id")
    }
}
