package com.ifmix.api.core.entity

import java.util.UUID

interface AppScopedProps {
    override val appId: UUID
}
