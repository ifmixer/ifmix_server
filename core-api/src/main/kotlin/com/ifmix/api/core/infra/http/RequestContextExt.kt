package com.ifmix.api.core.infra.http

import java.util.UUID

/** Parse appId as UUID, throw INVALID_REQUEST if malformed. */
fun RequestContext.appIdAsUUID(): UUID = try {
    UUID.fromString(appId)
} catch (e: Exception) {
    throw ApiError(ErrorCode.INVALID_REQUEST, "Invalid app ID")
}
