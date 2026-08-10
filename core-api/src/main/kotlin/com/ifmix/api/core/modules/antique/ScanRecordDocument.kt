package com.ifmix.api.core.modules.antique

import com.ifmix.api.core.common.db.BaseAppDocument
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document

/**
 * 古物扫描记录文档。
 *
 * 存储每次扫描的输入（图片 URL）、输出（结果 JSON）、状态等信息。
 * 按 appId 分片，支持软删。userId/installId 用于登录后归并匿名记录。
 */
@Document(collection = "scan_records")
class ScanRecordDocument : BaseAppDocument() {

    @Indexed
    var scanId: String? = null

    var imageUrl: String? = null

    /** 扫描结果 JSON（字符串形式），由 ScanRunner 写入。 */
    var resultJson: String? = null

    var status: String? = null

    var tier: String? = null

    var clientIp: String? = null

    /** 可选：关联的 todo / task ID。 */
    var relatedId: String? = null

    /** 归属用户 ID，登录后由 MergeOnLoginListener 回填。 */
    var userId: String? = null

    /** 归属安装 ID，匿名阶段即记录。 */
    var installId: String? = null

    /** 是否已被收藏（在默认夹中），由 CollectionService.addItem() 更新。 */
    var collected: Boolean = false
}
