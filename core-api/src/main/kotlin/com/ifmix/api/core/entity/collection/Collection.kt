package com.ifmix.api.core.entity.collection

import org.babyfish.jimmer.sql.*
import com.ifmix.api.core.entity.AppScopedProps
import com.ifmix.api.core.entity.SoftDeletableProps
import java.util.UUID

@Entity
@Table(name = "core_collection")
interface Collection : AppScopedProps, SoftDeletableProps {

    @Id
    val id: UUID

    override val appId: UUID

    val installId: UUID?
    val userId: String?
    val isDefault: Boolean

    @OneToMany(mappedBy = "collection")
    val items: List<CollectionItem>
}
