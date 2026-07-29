-- V6__iap.sql
-- subscriptions: 订阅表
CREATE TABLE IF NOT EXISTS subscription (
    id UUID NOT NULL PRIMARY KEY,
    app_id UUID NOT NULL,
    subscription_pxid VARCHAR(255) UNIQUE,
    original_transaction_id TEXT,
    product_id VARCHAR(255),
    platform VARCHAR(32),
    active BOOLEAN DEFAULT FALSE,
    sub_status VARCHAR(32),
    expiry_date TIMESTAMPTZ,
    purchase_token VARCHAR(255),
    raw_response JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS_subscription_app_id_idx ON subscription (app_id);
CREATE INDEX IF NOT EXISTS_subscription_pxid_active_idx ON subscription (subscription_pxid, active) WHERE active = TRUE;
CREATE INDEX IF NOT EXISTS_subscription_original_txn_idx ON subscription (original_transaction_id);

-- store_notifications: 商店通知表（用于 webhook 幂等处理）
CREATE TABLE IF NOT EXISTS store_notification (
    id UUID NOT NULL PRIMARY KEY,
    app_id UUID NOT NULL,
    platform VARCHAR(32),
    subscription_pxid VARCHAR(255),
    purchase_token VARCHAR(255),
    notification_type VARCHAR(64),
    raw_payload JSONB,
    processed BOOLEAN DEFAULT FALSE,
    processed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS_store_notif_platform_sub_idx ON store_notification (platform, subscription_xid, processed_at);
CREATE INDEX IF NOT EXISTS_store_notif_platform_pxid_idx ON store_notification (platform, purchase_token);
