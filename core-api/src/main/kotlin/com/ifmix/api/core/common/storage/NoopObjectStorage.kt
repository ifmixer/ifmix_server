package com.ifmix.api.core.common.storage

import java.time.Duration

/**
 * 未配置对象存储（app.storage.type != s3）时的占位实现：返回可辨识的假 URL，不做真实签名。
 * 仅供本地/开发启动使用；生产请配置 app.storage.type=s3 + 凭证以启用 [S3ObjectStorage]。
 */
class NoopObjectStorage : ObjectStorage {

    override fun presignUpload(objectKey: String, contentType: String, duration: Duration): String =
        "http://noop-storage.local/upload/$objectKey"

    override fun presignDownload(objectKey: String, duration: Duration): String =
        "http://noop-storage.local/download/$objectKey"
}
