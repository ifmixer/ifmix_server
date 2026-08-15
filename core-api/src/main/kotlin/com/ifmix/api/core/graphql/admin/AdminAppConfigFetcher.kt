package com.ifmix.api.core.graphql.admin

import com.ifmix.api.core.graphql.common.context.GraphQLRequestContext
import com.ifmix.api.core.graphql.common.type.OperationResult
import com.ifmix.api.core.modules.appconfig.AppleConfig
import com.ifmix.api.core.modules.appconfig.AppConfigDocument
import com.ifmix.api.core.modules.appconfig.AppConfigMapper
import com.ifmix.api.core.modules.appconfig.AppConfigRepo
import com.ifmix.api.core.modules.appconfig.AppConfigView
import com.ifmix.api.core.modules.appconfig.GoogleConfig
import com.netflix.graphql.dgs.DgsComponent
import com.netflix.graphql.dgs.DgsMutation
import com.netflix.graphql.dgs.DgsQuery
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment
import com.netflix.graphql.dgs.InputArgument
import com.netflix.graphql.dgs.context.DgsContext
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import java.time.Instant

/**
 * GraphQL Admin AppConfig fetcher。
 * 提供查询当前版本、创建新版本、切换版本三个入口。
 */
@DgsComponent
class AdminAppConfigFetcher(
    private val appConfigRepo: AppConfigRepo,
    private val mongo: MongoTemplate,
) {

    @DgsQuery
    fun currentAppConfig(dfe: DgsDataFetchingEnvironment): AppConfigView? {
        val ctx = DgsContext.getCustomContext<GraphQLRequestContext>(dfe)
        val doc = mongo.findOne(
            Query(Criteria.where("appId").`is`(ctx.requestContext.appId).and("deletedAt").`is`(null)),
            AppConfigDocument::class.java,
        )
        return doc?.let { AppConfigMapper.toView(it) }
    }

    @DgsMutation
    fun createAppConfigRevision(
        @InputArgument input: Map<String, Any?>,
        dfe: DgsDataFetchingEnvironment,
    ): AppConfigView {
        val now = Instant.now()
        val appId = input["appId"] as? String
            ?: DgsContext.getCustomContext<GraphQLRequestContext>(dfe).requestContext.appId
        val authTenantId = input["authTenantId"] as? String
        val appleBundleId = input["appleBundleId"] as? String
        val androidPackageName = input["androidPackageName"] as? String
        @Suppress("UNCHECKED_CAST")
        val appleConfig = input["appleConfig"] as? Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val googleConfig = input["googleConfig"] as? Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val iapConfig = input["iapConfig"] as? Map<String, Any?>

        // 查找当前版本以确定下一个 revision 号
        val current = mongo.findOne(
            Query(Criteria.where("appId").`is`(appId).and("deletedAt").`is`(null)),
            AppConfigDocument::class.java,
        )
        val nextRevision = (current?.revision ?: 0) + 1

        val next = AppConfigDocument().apply {
            this.appId = appId
            this.authTenantId = authTenantId ?: current?.authTenantId
            this.appleBundleId = appleBundleId ?: current?.appleBundleId
            this.androidPackageName = androidPackageName ?: current?.androidPackageName
            apple = parseAppleConfig(appleConfig, current?.apple)
            google = parseGoogleConfig(googleConfig, current?.google)
            iap = parseIapConfig(iapConfig, current?.iap)
            revision = nextRevision
            createdAt = now
            updatedAt = now
            deletedAt = null
        }
        mongo.insert(next)
        return AppConfigMapper.toView(next)
    }

    @DgsMutation
    fun toggleAppConfigRevision(
        @InputArgument id: String,
        @InputArgument enabled: Boolean,
        dfe: DgsDataFetchingEnvironment,
    ): AppConfigView {
        val ctx = DgsContext.getCustomContext<GraphQLRequestContext>(dfe)
        val now = Instant.now()
        val appId = ctx.requestContext.appId

        if (enabled) {
            // 先把同 appId 的其他当前版本标记为已删除
            val otherCurrent = mongo.find(
                Query(Criteria.where("appId").`is`(appId).and("deletedAt").`is`(null).and("_id").ne(id)),
                AppConfigDocument::class.java,
            )
            otherCurrent.forEach { doc ->
                mongo.updateFirst(
                    Query(Criteria.where("_id").`is`(doc.id)),
                    Update().set("deletedAt", now).set("updatedAt", now),
                    AppConfigDocument::class.java,
                )
            }

            // 激活目标版本
            mongo.updateFirst(
                Query(Criteria.where("_id").`is`(id).and("appId").`is`(appId)),
                Update().set("deletedAt", null).set("updatedAt", now),
                AppConfigDocument::class.java,
            )
        } else {
            // 软删当前版本
            mongo.updateFirst(
                Query(Criteria.where("_id").`is`(id).and("appId").`is`(appId)),
                Update().set("deletedAt", now).set("updatedAt", now),
                AppConfigDocument::class.java,
            )
        }

        // 返回最新当前版本
        val updated = mongo.findOne(
            Query(Criteria.where("appId").`is`(appId).and("deletedAt").`is`(null)),
            AppConfigDocument::class.java,
        )
        return updated?.let { AppConfigMapper.toView(it) }
            ?: throw IllegalStateException("No active AppConfigRevision found after toggle")
    }

    // ---- helpers ----

    private fun parseAppleConfig(input: Map<String, Any?>?, fallback: AppleConfig?): AppleConfig {
        val fb = fallback ?: AppleConfig()
        return AppleConfig(
            appAppleId = input?.get("appAppleId") as? String ?: fb.appAppleId,
            issuerId = input?.get("issuerId") as? String ?: fb.issuerId,
            keyId = input?.get("keyId") as? String ?: fb.keyId,
            privateKey = input?.get("privateKey") as? String ?: fb.privateKey,
            servicesId = input?.get("servicesId") as? String ?: fb.servicesId,
        )
    }

    private fun parseGoogleConfig(input: Map<String, Any?>?, fallback: GoogleConfig?): GoogleConfig {
        val fb = fallback ?: GoogleConfig()
        @Suppress("UNCHECKED_CAST")
        val rawClientIds = input?.get("clientIds") as? Map<String, String?>
        return GoogleConfig(
            serviceAccount = input?.get("serviceAccount") as? String ?: fb.serviceAccount,
            clientIds = com.ifmix.api.core.modules.appconfig.GoogleClientIds(
                ios = rawClientIds?.get("ios") ?: fb.clientIds.ios,
                android = rawClientIds?.get("android") ?: fb.clientIds.android,
                web = rawClientIds?.get("web") ?: fb.clientIds.web,
            ),
        )
    }

    private fun parseIapConfig(input: Map<String, Any?>?, fallback: com.ifmix.api.core.modules.appconfig.IapConfig?): com.ifmix.api.core.modules.appconfig.IapConfig {
        val fb = fallback ?: com.ifmix.api.core.modules.appconfig.IapConfig()
        return com.ifmix.api.core.modules.appconfig.IapConfig(
            productTierMap = (input?.get("productTierMap") as? Map<String, Any>)?.mapValues { (_, v) -> v.toString() } ?: fb.productTierMap,
            env = input?.get("env") as? String ?: fb.env,
        )
    }
}
