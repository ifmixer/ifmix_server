package com.ifmix.api.core.modules.app.repo

import com.ifmix.api.core.infra.db.SvcCtx
import com.ifmix.api.core.jooq.tables.CoreAppConfigRevision.Companion.CORE_APP_CONFIG_REVISION
import com.ifmix.api.core.entity.app.AppConfigRevision
import org.jooq.JSONB
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * App 配置相关 jOOQ 仓库。
 */
@Repository
class AppConfigRepository {

    /** 查询指定 app 下当前生效（enabled=true）的配置版本，按创建时间倒序取最新一条 */
    fun findActiveByAppId(ctx: SvcCtx, appId: UUID): AppConfigRevision? =
        ctx.dsl.selectFrom(CORE_APP_CONFIG_REVISION)
            .where(CORE_APP_CONFIG_REVISION.APP_ID.eq(appId))
            .and(CORE_APP_CONFIG_REVISION.ENABLED.eq(true))
            .orderBy(CORE_APP_CONFIG_REVISION.CREATED_AT.desc())
            .limit(1)
            .fetchOne()?.let { mapToModel(it) }

    /** 按 Apple Bundle ID 查询生效的配置版本 */
    fun findByBundleId(ctx: SvcCtx, bundleId: String): AppConfigRevision? =
        ctx.dsl.selectFrom(CORE_APP_CONFIG_REVISION)
            .where(CORE_APP_CONFIG_REVISION.APPLE_BUNDLE_ID.eq(bundleId))
            .and(CORE_APP_CONFIG_REVISION.ENABLED.eq(true))
            .limit(1)
            .fetchOne()?.let { mapToModel(it) }

    /** 按 Android 包名查询生效的配置版本 */
    fun findByAndroidPackage(ctx: SvcCtx, pkg: String): AppConfigRevision? =
        ctx.dsl.selectFrom(CORE_APP_CONFIG_REVISION)
            .where(CORE_APP_CONFIG_REVISION.ANDROID_PACKAGE_NAME.eq(pkg))
            .and(CORE_APP_CONFIG_REVISION.ENABLED.eq(true))
            .limit(1)
            .fetchOne()?.let { mapToModel(it) }

    /** 将指定 app 下所有生效版本的 enabled 置为 false，返回影响行数 */
    fun disableCurrentRevisions(ctx: SvcCtx, appId: UUID): Int =
        ctx.dsl.update(CORE_APP_CONFIG_REVISION)
            .set(CORE_APP_CONFIG_REVISION.ENABLED, false)
            .where(CORE_APP_CONFIG_REVISION.APP_ID.eq(appId))
            .and(CORE_APP_CONFIG_REVISION.ENABLED.eq(true))
            .execute()

    /** 插入新配置版本，返回插入后的模型（含服务端生成的 createdAt） */
    fun insert(ctx: SvcCtx, revision: AppConfigRevision): AppConfigRevision {
        val record = ctx.dsl.newRecord(CORE_APP_CONFIG_REVISION, revision)
        record.content = JSONB.jsonb(revision.content)
        ctx.dsl.executeInsert(record)
        return findById(ctx, revision.id)
            ?: throw RuntimeException("failed to read newly inserted revision: ${revision.id}")
    }

    /** 按 id 查询单条记录（用于插入后回读或 toggle 后回读） */
    fun findById(ctx: SvcCtx, id: UUID): AppConfigRevision? =
        ctx.dsl.selectFrom(CORE_APP_CONFIG_REVISION)
            .where(CORE_APP_CONFIG_REVISION.ID.eq(id))
            .fetchOne()?.let { mapToModel(it) }

    /** 按 app id 查询当前生效（enabled=true）的配置版本，不存在时抛异常 */
    fun mustFindCurrentRevision(ctx: SvcCtx, appId: UUID): AppConfigRevision =
        findActiveByAppId(ctx, appId)
            ?: throw com.ifmix.api.core.infra.http.ApiError(
                com.ifmix.api.core.infra.http.ErrorCode.APP_CONFIG_MISSING,
                "AppConfigRevision not found for appId=$appId"
            )

    /** 更新指定 revision 的 enabled 状态，返回影响行数 */
    fun updateEnabled(ctx: SvcCtx, revisionId: UUID, enabled: Boolean): Int =
        ctx.dsl.update(CORE_APP_CONFIG_REVISION)
            .set(CORE_APP_CONFIG_REVISION.ENABLED, enabled)
            .where(CORE_APP_CONFIG_REVISION.ID.eq(revisionId))
            .execute()

    /** JSONB→String 映射需要手动处理 */
    private fun mapToModel(record: com.ifmix.api.core.jooq.tables.records.CoreAppConfigRevisionRecord): AppConfigRevision {
        val contentStr = record.content?.toString() ?: "{}"
        return AppConfigRevision(
            id = record.id!!,
            appId = record.appId!!,
            authTenantId = record.authTenantId,
            appleBundleId = record.appleBundleId,
            androidPackageName = record.androidPackageName,
            revisionNumber = record.revisionNumber ?: 0,
            createdAt = record.createdAt ?: java.time.Instant.now(),
            enabled = record.enabled ?: false,
            slug = record.slug ?: "",
            content = contentStr,
            note = record.note ?: "",
        )
    }
}
