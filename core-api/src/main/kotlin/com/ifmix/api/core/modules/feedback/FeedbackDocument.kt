package com.ifmix.api.core.modules.feedback

import com.ifmix.api.core.common.db.AppScoped
import com.ifmix.api.core.common.db.BaseDocument
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.mapping.Document

/** feedback 集合。追加式写入，不软删（未实现 SoftDeletable）。 */
@Document(collection = "feedback")
@CompoundIndex(name = "feedback_app_id_id_idx", def = "{'appId': 1, '_id': 1}")
class FeedbackDocument : BaseDocument(), AppScoped {
    override var appId: String? = null
    var installId: String? = null
    var userId: String? = null
    var scanRecordId: String? = null
    var category: FeedbackCategory? = null
    var note: String? = null
}
