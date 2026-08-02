package com.ifmix.api.core.infra.http

import java.util.UUID

/** Return appId as non-null UUID or throw INVALID_REQUEST. */
fun OperationContext.appIdAsUUID(): UUID =
    appId ?: throw ApiError(ErrorCode.INVALID_REQUEST, "Invalid app ID")
