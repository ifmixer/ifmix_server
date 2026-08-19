package com.ifmix.api.core.modules.appconfig.repo

import com.ifmix.api.core.common.db.BaseAppEntity
import com.ifmix.api.core.common.db.BaseEntity
import com.ifmix.api.core.common.http.RequestContext
import com.ifmix.api.core.common.tx.TxRunner
import org.bson.types.ObjectId
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.data.mongodb.core.query.isEqualTo
import org.springframework.data.mongodb.core.query.ne
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import com.ifmix.api.core.modules.appconfig.entity.AppConfigEntity
import com.ifmix.api.core.modules.appconfig.AppConfigView
import com.ifmix.api.core.modules.appconfig.AppConfigMapper
import com.ifmix.api.core.modules.appconfig.AppConfigPatch

/**
 * app_config 读取：按 appId / appleBundleId / androidPackageName 查“当前版本”（deletedAt=null），
 * 映射成扁平 [AppConfigView]，内存缓存 TTL 60s。写用 [newVersion]（版本化，事务内）。
 *
 * app_config 是 app 注册表，不经 CRUDAppRepository 租户过滤——直接按查询键查。
 */
@Component
class AppConfigRepo(
    private val mongo: MongoTemplate,
    private val txRunner: TxRunner,
) {
    private val cacheTtlMs = 60_000L

    private data class Cached(val at: Long, val cfg: AppConfigView?)

    private val cache = ConcurrentHashMap<String, Cached>()

    fun getByAppId(appId: String): AppConfigView? = getBy("appId", appId)

    fun getByAppleBundleId(bundleId: String): AppConfigView? = getBy("appleBundleId", bundleId)

    fun getByAndroidPackage(pkg: String): AppConfigView? = getBy("androidPackageName", pkg)

    private fun getBy(field: String, value: String): AppConfigView? {
        val key = "$field:$value"
        cache[key]?.let { if (System.currentTimeMillis() - it.at < cacheTtlMs) return it.cfg }
        val query = Query(Criteria().andOperator(AppConfigEntity::id isEqualTo ObjectId(field), AppConfigEntity::deletedAt isEqualTo null))
        val doc = mongo.findOne(query, AppConfigEntity::class.java)
        val cfg = doc?.let { AppConfigMapper.toView(it) }
        cache[key] = Cached(System.currentTimeMillis(), cfg)
        return cfg
    }

    /** 追加新版本：事务内软删当前版本 + 插入 revision+1 的新当前版本；清缓存。 */
    fun newVersion(ctx: RequestContext, appId: String, patch: AppConfigPatch) {
        txRunner.withTx(ctx) {
            val current = mongo.findOne(
                Query(Criteria().andOperator(
                    AppConfigEntity::appId isEqualTo ObjectId(appId),
                    AppConfigEntity::deletedAt isEqualTo null,
                )),
                AppConfigEntity::class.java,
            )
            val now = Instant.now()
            if (current != null) {
                mongo.updateFirst(
                    Query(Criteria().andOperator(AppConfigEntity::id isEqualTo current.id)),
                    Update().set(BaseAppEntity::deletedAt, now).set(BaseEntity::updatedAt, now),
                    AppConfigEntity::class.java,
                )
            }
            val next = AppConfigEntity().apply {
                this.appId = ObjectId(appId)
                authTenantId = patch.authTenantId ?: current?.authTenantId
                appleBundleId = patch.appleBundleId ?: current?.appleBundleId
                androidPackageName = patch.androidPackageName ?: current?.androidPackageName
                apple = patch.apple ?: current?.apple ?: AppleConfig()
                google = patch.google ?: current?.google ?: GoogleConfig()
                iap = patch.iap ?: current?.iap ?: IapConfig()
                revision = (current?.revision ?: 0) + 1
                createdAt = now
                updatedAt = now
                deletedAt = null
            }
            mongo.insert(next)
        }
        cache.clear()
    }

    /** 获取当前生效的 AppConfigEntity（按 appId，deletedAt=null）。 */
    fun getCurrentDoc(appId: String): AppConfigEntity? {
        return mongo.findOne(
            Query(Criteria().andOperator(
                AppConfigEntity::appId isEqualTo ObjectId(appId),
                AppConfigEntity::deletedAt isEqualTo null,
            )),
            AppConfigEntity::class.java,
        )
    }

    /**
     * 切换指定 revision 的启用状态。
     * enabled=true：软删同 appId 其他版本，恢复目标版本。
     * enabled=false：软删目标版本。
     * 返回切换后当前生效的 AppConfigView（可能为 null）。
     */
    fun toggleRevision(ctx: RequestContext, id: String, enabled: Boolean): AppConfigView? {
        val appId = ctx.appId
        val now = Instant.now()
        txRunner.withTx(ctx) {
            if (enabled) {
                // 软删同 appId 其他版本
                mongo.updateMulti(
                    Query(Criteria().andOperator(
                        AppConfigEntity::appId isEqualTo ObjectId(appId),
                        AppConfigEntity::deletedAt isEqualTo null,
                        AppConfigEntity::id ne ObjectId(id),
                    )),
                    Update().set(BaseAppEntity::deletedAt, now).set(BaseEntity::updatedAt, now),
                    AppConfigEntity::class.java,
                )
                // 恢复目标版本
                mongo.updateFirst(
                    Query(Criteria().andOperator(
                        AppConfigEntity::id isEqualTo ObjectId(id),
                        AppConfigEntity::appId isEqualTo ObjectId(appId),
                    )),
                    Update().set(BaseAppEntity::deletedAt, null).set(BaseEntity::updatedAt, now),
                    AppConfigEntity::class.java,
                )
            } else {
                // 软删目标版本
                mongo.updateFirst(
                    Query(Criteria().andOperator(
                        AppConfigEntity::id isEqualTo ObjectId(id),
                        AppConfigEntity::appId isEqualTo ObjectId(appId),
                    )),
                    Update().set(BaseAppEntity::deletedAt, now).set(BaseEntity::updatedAt, now),
                    AppConfigEntity::class.java,
                )
            }
        }
        cache.clear()
        val doc = getCurrentDoc(appId)
        return doc?.let { AppConfigMapper.toView(it) }
    }
}
