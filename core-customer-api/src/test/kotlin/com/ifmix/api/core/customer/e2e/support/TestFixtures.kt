package com.ifmix.api.core.customer.e2e.support

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * E2E 测试种子数据工厂。
 *
 * 使用 JdbcTemplate 直接执行 SQL 创建测试所需的基础数据。
 */
@Component
class TestFixtures(private val jdbcTemplate: JdbcTemplate) {

    companion object {
        val APP_ID: UUID = UUID.fromString(E2eTestBase.TEST_APP_ID)
        val TENANT_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000010")
    }

    /**
     * 初始化最小测试数据集。
     * 幂等：重复调用不会报错。
     */
    fun seedMinimal() {
        // 1. AuthTenant
        jdbcTemplate.update(
            """INSERT INTO core_auth_tenant (id, jwt_issuer, created_at, updated_at)
               VALUES (?::uuid, 'ifmix-test', now(), now())
               ON CONFLICT (id) DO NOTHING""",
            TENANT_ID.toString()
        )

        // 2. AppInfo
        jdbcTemplate.update(
            """INSERT INTO core_app_info (id, name, slug, created_at, updated_at)
               VALUES (?::uuid, 'Test App', 'test-app', now(), now())
               ON CONFLICT (id) DO NOTHING""",
            APP_ID.toString()
        )

        // 3. AppConfigRevision (new schema: content JSONB aggregates all config)
        jdbcTemplate.update(
            """INSERT INTO core_app_config_revision (id, app_id, auth_tenant_id, apple_bundle_id, android_package_name,
                                                    content, revision_number, enabled, slug, note, created_at)
               VALUES (?::uuid, ?::uuid, ?::uuid, 'com.ifmix.test', 'com.ifmix.test',
                       '{"apple":{"appAppleId":"123","issuerId":"iss","keyId":"kid","privateKey":"pk","servicesId":"sid"},"google":{"serviceAccount":"sa","clientIds":{"ios":"ios-id","android":"android-id","web":"web-id"}},"iap":{"productTierMap":{"pro_monthly":"PRO"},"env":"sandbox"},"wechat":{"appId":"wx_test_id","appSecret":"wx_test_secret"}}'::jsonb,
                       1, true, 'test', 'test seed', now())
               ON CONFLICT DO NOTHING""",
            UUID.randomUUID().toString(), APP_ID.toString(), TENANT_ID.toString()
        )
    }
}
