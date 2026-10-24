package com.ifmix.core.api.modules.project.handler

import com.ifmix.core.api.entity.project.ConfigPlatform
import com.ifmix.core.api.entity.project.ConfigPlatforms
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.http.ClientPlatform
import com.ifmix.core.api.infra.redis.CacheAside
import com.ifmix.core.api.modules.project.repo.CloudConfigItemRepository
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.time.Duration

/**
 * 云配置装配：DB 行 → 客户端生效键值。
 * 缓存粒度 (project, platform, otaVersion)：ota 门槛过滤在 loader 里完成后才进缓存；
 * keys 不参与缓存，只在返回装配时过滤——同一份缓存服务所有 keys 组合。
 * 设计见 docs/design/config/cloud-config.md。
 */
@Component
class CloudConfigItemAggHandler(
    private val repo: CloudConfigItemRepository,
    private val cache: CacheAside,
    private val objectMapper: ObjectMapper,
) {

    /** 缓存快照行（纯 data class；Jimmer entity 动态代理不适合走 Jackson 缓存） */
    data class RowSnapshot(
        val key: String,
        /** JSON 原文（entity JsonNode 序列化文本），装配时再解析 */
        val valueText: String,
        val platform: ConfigPlatform,
        val minOtaVersion: String?,
        val maxOtaVersion: String?,
        val updatedAtEpochMillis: Long,
    )

    data class Payload(val configVersion: String, val values: Map<String, Any?>)

    fun getCloudConfig(mc: ModuleCtx, platform: ClientPlatform?, otaVersion: String?, keys: List<String>): Payload {
        val projectId = mc.projectId!!
        val platformCode = platform?.let { toPlatformCode(it) }
        val load = {
            repo.findEnabledRows(mc, projectId, platformCode)
                .map {
                    RowSnapshot(
                        key = it.key,
                        valueText = it.value.toString(),
                        platform = it.platform,
                        minOtaVersion = it.minOtaVersion,
                        maxOtaVersion = it.maxOtaVersion,
                        updatedAtEpochMillis = it.updatedAt.toEpochMilli(),
                    )
                }
                .filter { matchesOtaGate(it, otaVersion) }
                .toTypedArray()
        }
        // mutation 上下文不读缓存（约定同 CrudServiceOps），防写后脏读
        val rows = if (mc.action.readCache) {
            cache.getOrLoad(cacheKey(projectId, platformCode, otaVersion), Array<RowSnapshot>::class.java, CACHE_TTL, load)
        } else {
            load()
        }
        return assemble(rows.toList(), keys)
    }

    /**
     * 平台覆盖 + keys 过滤 + configVersion。纯函数：入参 rows 已按 ota 门槛过滤（缓存粒度保证），
     * 同 key 单平台行覆盖 0=全部行，只返回 keys 要求的项。
     */
    internal fun assemble(rows: List<RowSnapshot>, keys: Collection<String>): Payload {
        val merged = LinkedHashMap<String, RowSnapshot>()
        rows.filter { it.platform == ConfigPlatforms.ALL }.forEach { merged[it.key] = it }
        rows.filter { it.platform != ConfigPlatforms.ALL }.forEach { merged[it.key] = it }
        val wanted = keys.toSet()
        var maxMillis = 0L
        val values = LinkedHashMap<String, Any?>()
        merged.forEach { (key, row) ->
            if (key !in wanted) return@forEach
            values[key] = objectMapper.readValue(row.valueText, Any::class.java)
            if (row.updatedAtEpochMillis > maxMillis) maxMillis = row.updatedAtEpochMillis
        }
        return Payload(configVersion = maxMillis.toString(), values = values)
    }

    /** ota 门槛：无门槛恒真；otaVersion 缺失（meta 未带）→ 只放行无门槛行（门槛行代表新行为，无版本证据不下发） */
    internal fun matchesOtaGate(row: RowSnapshot, otaVersion: String?): Boolean {
        if (row.minOtaVersion == null && row.maxOtaVersion == null) return true
        if (otaVersion == null) return false
        row.minOtaVersion?.let { if (compareOtaVersion(otaVersion, it) < 0) return false }
        row.maxOtaVersion?.let { if (compareOtaVersion(otaVersion, it) > 0) return false }
        return true
    }

    private fun toPlatformCode(platform: ClientPlatform): ConfigPlatform = when (platform) {
        ClientPlatform.IOS -> ConfigPlatforms.IOS
        ClientPlatform.ANDROID -> ConfigPlatforms.ANDROID
        ClientPlatform.WEB -> ConfigPlatforms.WEB
    }

    companion object {
        private val CACHE_TTL = Duration.ofMinutes(5)

        private fun cacheKey(projectId: String, platformCode: ConfigPlatform?, otaVersion: String?) =
            "cloudconfig:$projectId:${platformCode ?: 0}:${otaVersion ?: "_"}"

        /** 版本逐段数值比较（`.` 与 `-` 都作分隔，兼容 otaVersion 形如 `1-23-3`）：缺失段补 0，段内取前导数字、非数字段按 0。负数 = a < b。 */
        internal fun compareOtaVersion(a: String, b: String): Int {
            val as_ = a.split('.', '-').map { leadingNumber(it) }
            val bs = b.split('.', '-').map { leadingNumber(it) }
            for (i in 0 until maxOf(as_.size, bs.size)) {
                val av = as_.getOrElse(i) { 0L }
                val bv = bs.getOrElse(i) { 0L }
                if (av != bv) return av.compareTo(bv)
            }
            return 0
        }

        private fun leadingNumber(segment: String): Long =
            segment.takeWhile { it.isDigit() }.ifEmpty { "0" }.toLongOrNull() ?: 0L
    }
}
