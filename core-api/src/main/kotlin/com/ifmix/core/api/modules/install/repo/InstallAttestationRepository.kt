package com.ifmix.core.api.modules.install.repo

import com.ifmix.core.api.entity.install.AttestationStatuses
import com.ifmix.core.api.entity.install.InstallAttestation
import com.ifmix.core.api.entity.install.createdAt
import com.ifmix.core.api.entity.install.id
import com.ifmix.core.api.entity.install.installId
import com.ifmix.core.api.entity.install.lastUsedAt
import com.ifmix.core.api.entity.install.projectId
import com.ifmix.core.api.entity.install.provider
import com.ifmix.core.api.entity.install.signCount
import com.ifmix.core.api.entity.install.status
import com.ifmix.core.api.entity.install.subject
import com.ifmix.core.api.entity.install.updatedAt
import com.ifmix.core.api.infra.db.ModuleCtx
import com.ifmix.core.api.infra.repo.ProjectCrudRepoTemplate
import org.babyfish.jimmer.sql.kt.ast.expression.asc
import org.babyfish.jimmer.sql.kt.ast.expression.count
import org.babyfish.jimmer.sql.kt.ast.expression.eq
import org.babyfish.jimmer.sql.kt.ast.expression.lt
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

@Repository
class InstallAttestationRepository(dataSource: DataSource) {
    companion object { private val tpl = ProjectCrudRepoTemplate(InstallAttestation::class, UUID::class) }

    /**
     * FOR UPDATE 锁语句专用（Jimmer 0.11.5 不暴露 forUpdate）：同一 routing DataSource 的 JdbcClient，
     * 经 DataSourceUtils 加入当前事务——锁的是事务连接上的同一行（参照 ScanRecordRepository）。
     */
    private val jdbc: JdbcClient = JdbcClient.create(dataSource)

    /** 插入一条 attestation（id 由应用生成 UuidV7，created/updated 由调用方赋值）。 */
    fun insert(mc: ModuleCtx, entity: InstallAttestation): Boolean = tpl.save(mc, entity)

    /** 按 (projectId, provider, subject) 查绑定（含 status，供调用方区分 ACTIVE/BLOCKED/RETIRED）。 */
    fun findBySubject(mc: ModuleCtx, projectId: String, provider: Int, subject: String): InstallAttestation? =
        mc.sql.createQuery(InstallAttestation::class) {
            where(table.projectId eq projectId)
            where(table.provider eq provider)
            where(table.subject eq subject)
            select(table)
        }.limit(1).execute().firstOrNull()

    /** 该 install 的全部 attestation 行（created_at ASC, id ASC，与轮换口径一致）。 */
    fun listByInstall(mc: ModuleCtx, projectId: String, installId: UUID): List<InstallAttestation> =
        mc.sql.createQuery(InstallAttestation::class) {
            where(table.projectId eq projectId)
            where(table.installId eq installId)
            orderBy(table.createdAt.asc())
            orderBy(table.id.asc())
            select(table)
        }.execute()

    fun countActiveByInstall(mc: ModuleCtx, projectId: String, installId: UUID): Int =
        mc.sql.createQuery(InstallAttestation::class) {
            where(table.projectId eq projectId)
            where(table.installId eq installId)
            where(table.status eq AttestationStatuses.ACTIVE)
            select(count(table.id))
        }.execute().first().toInt()

    /**
     * 轮换：把 (created_at ASC, id ASC) 最先的一把 ACTIVE 置 status=RETIRED。
     * 先查后改，必须在 install 行锁（[lockInstallRow]）内调用，否则并发下可能退役多把。
     * 返回 false = 没有 ACTIVE 行可退役。
     */
    fun retireOldestActive(mc: ModuleCtx, projectId: String, installId: UUID): Boolean {
        val oldestId = mc.sql.createQuery(InstallAttestation::class) {
            where(table.projectId eq projectId)
            where(table.installId eq installId)
            where(table.status eq AttestationStatuses.ACTIVE)
            orderBy(table.createdAt.asc())
            orderBy(table.id.asc())
            select(table.id)
        }.limit(1).execute().firstOrNull() ?: return false
        val affected = mc.sql.createUpdate(InstallAttestation::class) {
            where(table.id eq oldestId)
            where(table.status eq AttestationStatuses.ACTIVE)
            set(table.status, AttestationStatuses.RETIRED)
            set(table.updatedAt, Instant.now())
        }.execute()
        return affected > 0
    }

    /**
     * 条件更新 assertion counter（recover 防重放的根本保证，规格 §3.3-e）：
     * 仅 status=ACTIVE 且 sign_count < newCount 时推进并写 last_used_at。
     * 0 行命中 = 并发重放或期间被封禁/退役，调用方按 403001 处理。
     */
    fun updateSignCount(mc: ModuleCtx, id: UUID, newCount: Long): Boolean =
        mc.sql.createUpdate(InstallAttestation::class) {
            where(table.id eq id)
            where(table.status eq AttestationStatuses.ACTIVE)
            where(table.signCount lt newCount)
            set(table.signCount, newCount)
            set(table.lastUsedAt, Instant.now())
        }.execute() > 0

    /**
     * FOR UPDATE 锁 install 行（attestExisting 事务内第一步）：行不存在（跨 project / 已删）→ false，
     * 调用方映射 404001。必须参与当前事务（GlobalTxRunner 的 Spring 事务）。
     */
    fun lockInstallRow(mc: ModuleCtx, projectId: String, installId: UUID): Boolean =
        jdbc.sql("SELECT id FROM core_install WHERE project_id = :projectId AND id = :id FOR UPDATE")
            .param("projectId", projectId)
            .param("id", installId)
            .query { rs, _ -> rs.getString("id") }
            .list()
            .isNotEmpty()
}
