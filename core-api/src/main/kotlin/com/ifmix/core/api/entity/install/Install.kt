package com.ifmix.core.api.entity.install

import com.ifmix.core.api.entity.common.BaseProjectEntity
import org.babyfish.jimmer.sql.*

/**
 * install 设备表：主键 id 即 installId（服务端生成的 UuidV7，= API installId = JWT iid）。
 * platform/app_version/... 来自请求 header；device_info 为自由结构 JSONB。
 * reg_ip 为注册时 IP（createInstall 写入，updateInstall 不改）。
 */
@Entity
@Table(name = "core_install")
interface Install : BaseProjectEntity {

    /** 平台 Int 码：10=ANDROID / 20=IOS / 30=WEB。来自 x-client-platform。 */
    val platform: Int?

    /** 设备信息（自由结构 JSONB）。 */
    @Serialized
    @Column(name = "device_info")
    val deviceInfo: Map<String, Any?>?

    @Column(name = "app_version")
    val appVersion: String?

    @Column(name = "ota_version")
    val otaVersion: String?

    val locale: String?
    val country: String?
    val currency: String?

    /** 注册时 IP（createInstall 写入，updateInstall 不改）。 */
    @Column(name = "reg_ip")
    val regIp: String?

    @Column(name = "firebase_install_id")
    val firebaseInstallId: String?

    @Column(name = "fcm_token")
    val fcmToken: String?

    /** 用户是否允许 scan 完成通知；默认开启。 */
    @Column(name = "scan_result_noti_enabled")
    val scanResultNotiEnabled: Boolean

    /** 最近一次 direct FCM token 是否仍有效；失效时保留 token 并回退 topic。 */
    @Column(name = "fcm_token_valid")
    val fcmTokenValid: Boolean

    /** 用户是否允许 DeepResearch 完成通知；默认开启。 */
    @Column(name = "deep_research_noti_enabled")
    val deepResearchNotiEnabled: Boolean
}
