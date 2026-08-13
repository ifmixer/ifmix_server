package com.ifmix.api.core.infra.http

import java.util.UUID

/** Return appId as non-null UUID or throw INVALID_REQUEST. */
fun OperationContext.mustGetAppId(): UUID =
    appId ?: throw ApiError(ErrorCode.INVALID_REQUEST, "x-app-id is required")

/** Return installId as non-null UUID or throw INVALID_REQUEST. */
fun OperationContext.mustGetInstallId(): UUID =
    installId ?: throw ApiError(ErrorCode.INVALID_REQUEST, "x-install-id is required")

/** Return userId as non-null UUID or throw UNAUTHORIZED. */
fun OperationContext.mustGetUserId(): UUID =
    userId ?: throw ApiError(ErrorCode.UNAUTHORIZED, "authentication required")

