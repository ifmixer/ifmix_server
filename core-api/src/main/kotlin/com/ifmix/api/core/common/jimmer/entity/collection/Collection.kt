package com.ifmix.api.core.common.jimmer.entity.collection

import org.babyfish.jimmer.sql.*
import com.ifmix.api.core.common.jimmer.entity.AppScopedProps
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "collection")
interface Collection : AppScopedProps {

    @Id
    val id: UUID

    override val appId: UUID

    val installId: String?
    val userId: String?
    val isDefault: Boolean

    @LogicalDeleted("now")
    val deletedAt: Instant?

    @OneToMany(mappedBy = "collection")
    val items: List<CollectionItem>

    val createdAt: Instant
    val updatedAt: Instant
}
