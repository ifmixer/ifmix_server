package com.ifmix.api.core.modules.app.repo

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.infra.jooq.CrudRepoOpsFactory
import com.ifmix.api.core.jooq.tables.CoreAppConfigRevision.Companion.CORE_APP_CONFIG_REVISION
import com.ifmix.api.core.entity.app.AppConfigRevision
import org.jooq.TableField
import org.springframework.stereotype.Repository
import java.util.UUID
import com.ifmix.api.core.infra.http.ErrorCode
/**
 * App 配置相关 jOOQ 仓库。
 */
@Repository
class AppConfigRepository(factory: CrudRepoOpsFactory) {

    companion object {
        val FIELD_MAP: Map<String, TableField<*, *>> = mapOf(
            AppConfigRevision::id.name to CORE_APP_CONFIG_REVISION.ID,
            AppConfigRevision::appId.name to CORE_APP_CONFIG_REVISION.APP_ID,
            AppConfigRevision::authTenantId.name to CORE_APP_CONFIG_REVISION.AUTH_TENANT_ID,
            AppConfigRevision::appleBundleId.name to CORE_APP_CONFIG_REVISION.APPLE_BUNDLE_ID,
            AppConfigRevision::androidPackageName.name to CORE_APP_CONFIG_REVISION.ANDROID_PACKAGE_NAME,
            AppConfigRevision::revisionNumber.name to CORE_APP_CONFIG_REVISION.REVISION_NUMBER,
            AppConfigRevision::enabled.name to CORE_APP_CONFIG_REVISION.ENABLED,
            AppConfigRevision::slug.name to CORE_APP_CONFIG_REVISION.SLUG,
            AppConfigRevision::createdAt.name to CORE_APP_CONFIG_REVISION.CREATED_AT,
        )
    }

    private val crud = factory.create(
        table = CORE_APP_CONFIG_REVISION,
        idField = CORE_APP_CONFIG_REVISION.ID,
        appIdField = CORE_APP_CONFIG_REVISION.APP_ID,
        type = AppConfigRevision::class.java,
    )

    fun findById(ctx: SvcCtx, id: UUID): AppConfigRevision? =
        crud.findById(ctx, id)

    fun findById(ctx: SvcCtx, appId: UUID, id: UUID): AppConfigRevision? =
        crud.findById(ctx, appId, id)

    /** 查询指定 app 下当前生效（enabled=true）的配置版本，按创建时间倒序取最新一条 */
    fun findActiveByAppId(ctx: SvcCtx, appId: UUID): AppConfigRevision? =
        ctx.dsl.selectFrom(CORE_APP_CONFIG_REVISION)
            .where(CORE_APP_CONFIG_REVISION.APP_ID.eq(appId))
            .and(CORE_APP_CONFIG_REVISION.ENABLED.eq(true))
            .orderBy(CORE_APP_CONFIG_REVISION.CREATED_AT.desc())
            .limit(1)
            .fetchOneInto(AppConfigRevision::class.java)

    /** 按 Apple Bundle ID 查询生效的配置版本 */
    fun findByBundleId(ctx: SvcCtx, bundleId: String): AppConfigRevision? =
        ctx.dsl.selectFrom(CORE_APP_CONFIG_REVISION)
            .where(CORE_APP_CONFIG_REVISION.APPLE_BUNDLE_ID.eq(bundleId))
            .and(CORE_APP_CONFIG_REVISION.ENABLED.eq(true))
            .limit(1)
            .fetchOneInto(AppConfigRevision::class.java)

    /** 按 Android 包名查询生效的配置版本 */
    fun findByAndroidPackage(ctx: SvcCtx, pkg: String): AppConfigRevision? =
        ctx.dsl.selectFrom(CORE_APP_CONFIG_REVISION)
            .where(CORE_APP_CONFIG_REVISION.ANDROID_PACKAGE_NAME.eq(pkg))
            .and(CORE_APP_CONFIG_REVISION.ENABLED.eq(true))
            .limit(1)
            .fetchOneInto(AppConfigRevision::class.java)

    /** 按 app id 查询当前生效（enabled=true）的配置版本，不存在时抛异常 */
    fun mustFindCurrentRevision(ctx: SvcCtx, appId: UUID): AppConfigRevision =
        findActiveByAppId(ctx, appId)
            ?: throw com.ifmix.api.core.infra.http.ApiError(
                ErrorCode.APP_CONFIG_MISSING,
                "AppConfigRevision not found for appId=$appId"
            )

    fun insert(ctx: SvcCtx, revision: AppConfigRevision) = crud.insert(ctx, revision)

    /** 将指定 app 下所有生效版本的 enabled 置为 false，返回影响行数 */
    fun disableCurrentRevisions(ctx: SvcCtx, appId: UUID): Int =
        ctx.dsl.update(CORE_APP_CONFIG_REVISION)
            .set(CORE_APP_CONFIG_REVISION.ENABLED, false)
            .where(CORE_APP_CONFIG_REVISION.APP_ID.eq(appId))
            .and(CORE_APP_CONFIG_REVISION.ENABLED.eq(true))
            .execute()

    /** 更新指定 revision 的 enabled 状态，返回影响行数 */
    fun updateEnabled(ctx: SvcCtx, revisionId: UUID, enabled: Boolean): Int =
        ctx.dsl.update(CORE_APP_CONFIG_REVISION)
            .set(CORE_APP_CONFIG_REVISION.ENABLED, enabled)
            .where(CORE_APP_CONFIG_REVISION.ID.eq(revisionId))
            .execute()
}
