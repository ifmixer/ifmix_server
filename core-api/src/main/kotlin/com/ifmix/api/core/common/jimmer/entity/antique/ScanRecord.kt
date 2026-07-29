package com.ifmix.api.core.common.jimmer.entity.antique

import org.babyfish.jimmer.sql.*
import org.babyfish.jimmer.sql.UUIDIdGenerator
import com.ifmix.api.core.common.jimmer.entity.AppScopedProps
import java.time.Instant
import java.util.UUID

/**
 * 古物扫描记录。
 */
@Entity
@Table(name = "scan_record")
interface ScanRecord : AppScopedProps {
    @Id
    @GeneratedValue(generatorType = UUIDIdGenerator::class)
    val id: UUID

    override val appId: UUID

    val scanId: String?
    val imageUrl: String?
    val resultJson: String?
    val status: String?
    val tier: String?
    val clientIp: String?
    val relatedId: String?

    @LogicalDeleted("now")
    val deletedAt: Instant?

    val createdAt: Instant
    val updatedAt: Instant}
