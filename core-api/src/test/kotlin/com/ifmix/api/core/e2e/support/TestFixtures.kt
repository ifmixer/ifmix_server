package com.ifmix.api.core.e2e.support

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

        // 3. AppConfig
        jdbcTemplate.update(
            """INSERT INTO core_app_config (id, app_id, auth_tenant_id, apple_bundle_id, android_package_name,
                                           apple_config, google_config, iap_config, revision, created_at, updated_at)
               VALUES (?::uuid, ?::uuid, ?::uuid, 'com.ifmix.test', 'com.ifmix.test',
                       '{"appAppleId":"123","issuerId":"iss","keyId":"kid","privateKey":"pk","servicesId":"sid"}'::jsonb,
                       '{"serviceAccount":"sa","clientIds":{"ios":"ios-id","android":"android-id","web":"web-id"}}'::jsonb,
                       '{"productTierMap":{"pro_monthly":"PRO"},"env":"sandbox"}'::jsonb,
                       1, now(), now())
               ON CONFLICT DO NOTHING""",
            UUID.randomUUID().toString(), APP_ID.toString(), TENANT_ID.toString()
        )
    }
}
