package com.ifmix.api.core.modules.appconfig

import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.common.tx.TxRunner
import org.bson.types.ObjectId
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * app_config 读取：按 appId / appleBundleId / androidPackageName 查“当前版本”（deletedAt=null），
 * 映射成扁平 [AppConfig]，内存缓存 TTL 60s。写用 [newVersion]（版本化，事务内）。
 *
 * app_config 是 app 注册表，不经 CRUDAppRepository 租户过滤——直接按查询键查。
 */
@Component
class AppConfigRepo(
    private val mongo: MongoTemplate,
    private val txRunner: TxRunner,
) {
    private val cacheTtlMs = 60_000L

    private data class Cached(val at: Long, val cfg: AppConfig?)

    private val cache = ConcurrentHashMap<String, Cached>()

    fun getByAppId(appId: String): AppConfig? = getBy("appId", appId)

    fun getByAppleBundleId(bundleId: String): AppConfig? = getBy("appleBundleId", bundleId)

    fun getByAndroidPackage(pkg: String): AppConfig? = getBy("androidPackageName", pkg)

    private fun getBy(field: String, value: String): AppConfig? {
        val key = "$field:$value"
        cache[key]?.let { if (System.currentTimeMillis() - it.at < cacheTtlMs) return it.cfg }
        val query = Query(Criteria.where(field).`is`(value).and("deletedAt").`is`(null))
        val doc = mongo.findOne(query, AppConfigDocument::class.java)
        val cfg = doc?.let { AppConfigMapper.toFlat(it) }
        cache[key] = Cached(System.currentTimeMillis(), cfg)
        return cfg
    }

    /** 追加新版本：事务内软删当前版本 + 插入 configVersion+1 的新当前版本；清缓存。 */
    fun newVersion(ctx: RequestContext, appId: String, patch: AppConfigPatch) {
        txRunner.withTx(ctx) {
            val current = mongo.findOne(
                Query(Criteria.where("appId").`is`(appId).and("deletedAt").`is`(null)),
                AppConfigDocument::class.java,
            )
            val now = Instant.now()
            if (current?.id != null) {
                mongo.updateFirst(
                    Query(Criteria.where("_id").`is`(ObjectId(current.id))),
                    Update().set("deletedAt", now).set("updatedAt", now),
                    AppConfigDocument::class.java,
                )
            }
            val next = AppConfigDocument().apply {
                this.appId = appId
                authTenantId = patch.authTenantId ?: current?.authTenantId
                appleBundleId = patch.appleBundleId ?: current?.appleBundleId
                androidPackageName = patch.androidPackageName ?: current?.androidPackageName
                apple = patch.apple ?: current?.apple ?: AppleConfig()
                google = patch.google ?: current?.google ?: GoogleConfig()
                iap = patch.iap ?: current?.iap ?: IapConfig()
                configVersion = (current?.configVersion ?: 0) + 1
                createdAt = now
                updatedAt = now
                deletedAt = null
            }
            mongo.insert(next)
        }
        cache.clear()
    }
}
