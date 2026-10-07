-- V1: 初始 schema（2026-10-07 收敛）。
-- 历史 V1–V20 已按当时最终状态固化为本文件（表名规则：域前缀 core_<域>_ 保留层级，域内单词直连，关系表用 2）。
-- 后续变更从 V2 开始递增。

--
-- PostgreSQL database dump
--


-- Dumped from database version 18.1 (Homebrew)
-- Dumped by pg_dump version 18.1 (Postgres.app)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: core_ai_apikey; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.core_ai_apikey (
    id uuid CONSTRAINT agnes_key_id_not_null NOT NULL,
    key character varying(512) CONSTRAINT agnes_key_key_not_null NOT NULL,
    email character varying(255),
    rate_limit bigint DEFAULT '-1'::integer CONSTRAINT agnes_key_rate_limit_not_null NOT NULL,
    window_sec bigint DEFAULT 86400 CONSTRAINT agnes_key_window_sec_not_null NOT NULL,
    models text,
    unavailable_until timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() CONSTRAINT agnes_key_created_at_not_null NOT NULL,
    updated_at timestamp with time zone DEFAULT now() CONSTRAINT agnes_key_updated_at_not_null NOT NULL,
    deleted_at timestamp with time zone,
    type smallint CONSTRAINT core_agnes_key_type_not_null NOT NULL,
    enabled boolean DEFAULT true CONSTRAINT ai_agnes_key_enabled_not_null NOT NULL,
    provider smallint DEFAULT 10 CONSTRAINT core_ai_api_key_provider_not_null NOT NULL
);


--
-- Name: core_ai_deepresearch; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.core_ai_deepresearch (
    id uuid CONSTRAINT ai_scan_deep_research_id_not_null NOT NULL,
    project_id character varying(30) CONSTRAINT ai_scan_deep_research_project_id_not_null NOT NULL,
    scan_record_id uuid CONSTRAINT ai_scan_deep_research_scan_record_id_not_null NOT NULL,
    premium_result jsonb,
    created_at timestamp with time zone DEFAULT now() CONSTRAINT ai_scan_deep_research_created_at_not_null NOT NULL,
    updated_at timestamp with time zone DEFAULT now() CONSTRAINT ai_scan_deep_research_updated_at_not_null NOT NULL,
    prompt_version character varying(32) CONSTRAINT ai_scan_deep_research_prompt_version_not_null NOT NULL,
    status smallint CONSTRAINT core_ai_scan_deep_research_status_not_null NOT NULL,
    error_code character varying(64),
    error_details jsonb,
    basic_result jsonb
);


--
-- Name: core_ai_scancollection; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.core_ai_scancollection (
    id uuid CONSTRAINT collection_id_not_null NOT NULL,
    project_id character varying(30) CONSTRAINT collection_project_id_not_null NOT NULL,
    customer_id uuid,
    is_default boolean DEFAULT false CONSTRAINT collection_is_default_not_null NOT NULL,
    created_at timestamp with time zone DEFAULT now() CONSTRAINT collection_created_at_not_null NOT NULL,
    updated_at timestamp with time zone DEFAULT now() CONSTRAINT collection_updated_at_not_null NOT NULL,
    deleted_at timestamp with time zone,
    install_id uuid
);


--
-- Name: core_ai_scancollectionitem; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.core_ai_scancollectionitem (
    id uuid CONSTRAINT collection_item_id_not_null NOT NULL,
    project_id character varying(30) CONSTRAINT collection_item_project_id_not_null NOT NULL,
    collection_id uuid CONSTRAINT collection_item_collection_id_not_null NOT NULL,
    scan_record_id uuid CONSTRAINT collection_item_scan_record_id_not_null NOT NULL,
    created_at timestamp with time zone DEFAULT now() CONSTRAINT collection_item_created_at_not_null NOT NULL,
    updated_at timestamp with time zone DEFAULT now() CONSTRAINT collection_item_updated_at_not_null NOT NULL,
    deleted_at timestamp with time zone
);


--
-- Name: core_ai_scanmetrics; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.core_ai_scanmetrics (
    id uuid CONSTRAINT core_ai_customer_scan_metrics_id_not_null NOT NULL,
    project_id text CONSTRAINT core_ai_customer_scan_metrics_project_id_not_null NOT NULL,
    customer_id uuid CONSTRAINT core_ai_customer_scan_metrics_customer_id_not_null NOT NULL,
    scan_count integer DEFAULT 0 CONSTRAINT core_ai_customer_scan_metrics_scan_count_not_null NOT NULL,
    deep_research_count integer DEFAULT 0 CONSTRAINT core_ai_customer_scan_metrics_deep_research_count_not_null NOT NULL,
    created_at timestamp with time zone CONSTRAINT core_ai_customer_scan_metrics_created_at_not_null NOT NULL,
    updated_at timestamp with time zone CONSTRAINT core_ai_customer_scan_metrics_updated_at_not_null NOT NULL,
    pending_scan_count integer CONSTRAINT core_ai_customer_scan_metrics_pending_scan_count_not_null NOT NULL
);


--
-- Name: core_ai_scanrecord; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.core_ai_scanrecord (
    id uuid CONSTRAINT scan_record_id_not_null NOT NULL,
    project_id character varying(30) CONSTRAINT scan_record_project_id_not_null NOT NULL,
    status smallint DEFAULT 10 CONSTRAINT core_scan_record_status_not_null NOT NULL,
    client_ip character varying(45),
    created_at timestamp with time zone DEFAULT now() CONSTRAINT scan_record_created_at_not_null NOT NULL,
    updated_at timestamp with time zone DEFAULT now() CONSTRAINT scan_record_updated_at_not_null NOT NULL,
    deleted_at timestamp with time zone,
    image_keys jsonb CONSTRAINT core_scan_record_image_keys_not_null NOT NULL,
    user_display_name character varying(255),
    user_notes text,
    collected boolean DEFAULT false CONSTRAINT core_scan_record_collected_not_null NOT NULL,
    locale character varying(16),
    country character varying(8),
    currency character varying(8),
    basic_result jsonb,
    customer_id uuid,
    has_deep_search boolean DEFAULT false CONSTRAINT ai_scan_record_has_deep_search_not_null NOT NULL,
    prompt_version character varying(32) CONSTRAINT ai_scan_record_prompt_version_not_null NOT NULL,
    is_public boolean DEFAULT false CONSTRAINT ai_scan_record_is_public_not_null NOT NULL,
    install_id uuid,
    latest_deep_research_id uuid,
    error_code character varying(64),
    error_details jsonb
);


--
-- Name: core_auth_customer; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.core_auth_customer (
    id uuid CONSTRAINT app_user_id_not_null NOT NULL,
    project_id character varying(30) CONSTRAINT app_user_app_id_not_null NOT NULL,
    created_at timestamp with time zone DEFAULT now() CONSTRAINT app_user_created_at_not_null NOT NULL,
    updated_at timestamp with time zone DEFAULT now() CONSTRAINT app_user_updated_at_not_null NOT NULL,
    anonymous boolean DEFAULT true CONSTRAINT customer_anonymous_not_null NOT NULL,
    merged_to uuid,
    merged_to_at timestamp with time zone,
    auth_identity_id uuid,
    scan_count integer DEFAULT 0 CONSTRAINT core_customer_scan_count_not_null NOT NULL,
    deep_research_count integer DEFAULT 0 CONSTRAINT core_customer_deep_research_count_not_null NOT NULL,
    deleted_at timestamp with time zone,
    delete_reason_category integer,
    delete_reason text
);


--
-- Name: core_auth_identity; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.core_auth_identity (
    id uuid CONSTRAINT auth_identity_id_not_null NOT NULL,
    project_id character varying(30) CONSTRAINT auth_identity_project_id_not_null NOT NULL,
    password character varying(255),
    first_name character varying(255),
    last_name character varying(255),
    email character varying(255),
    email_verified boolean DEFAULT false CONSTRAINT auth_identity_email_verified_not_null NOT NULL,
    phone_calling_code character varying(8),
    phone_country_code character varying(2),
    phone_national_number character varying(32),
    phone_verified boolean DEFAULT false CONSTRAINT auth_identity_phone_verified_not_null NOT NULL,
    last_login_at timestamp with time zone,
    last_login_method smallint,
    last_login_idp_identity_id uuid,
    last_login_ip character varying(45),
    metadata jsonb,
    created_at timestamp with time zone DEFAULT now() CONSTRAINT auth_identity_created_at_not_null NOT NULL,
    updated_at timestamp with time zone DEFAULT now() CONSTRAINT auth_identity_updated_at_not_null NOT NULL
);


--
-- Name: core_auth_identity2idp; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.core_auth_identity2idp (
    id uuid CONSTRAINT auth_identity_to_idpidentity_relation_id_not_null NOT NULL,
    project_id character varying(30) CONSTRAINT auth_identity_to_idpidentity_relation_project_id_not_null NOT NULL,
    auth_identity_id uuid CONSTRAINT auth_identity_to_idpidentity_relation_auth_identity_id_not_null NOT NULL,
    idp_identity_id uuid CONSTRAINT auth_identity_to_idpidentity_relation_idp_identity_id_not_null NOT NULL,
    created_at timestamp with time zone DEFAULT now() CONSTRAINT auth_identity_to_idpidentity_relation_created_at_not_null NOT NULL,
    updated_at timestamp with time zone DEFAULT now() CONSTRAINT auth_identity_to_idpidentity_relation_updated_at_not_null NOT NULL,
    deleted_at timestamp with time zone
);


--
-- Name: core_auth_idp; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.core_auth_idp (
    id uuid CONSTRAINT auth_idp_id_not_null NOT NULL,
    name character varying(255) CONSTRAINT auth_idp_name_not_null NOT NULL,
    idp_type smallint CONSTRAINT auth_idp_provider_type_not_null NOT NULL,
    third_id character varying(500) CONSTRAINT auth_idp_third_id_not_null NOT NULL,
    config jsonb CONSTRAINT auth_idp_config_not_null NOT NULL,
    "desc" text,
    created_at timestamp with time zone CONSTRAINT auth_idp_created_at_not_null NOT NULL
);


--
-- Name: core_auth_idpidentity; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.core_auth_idpidentity (
    id uuid CONSTRAINT auth_idpidentity_id_not_null NOT NULL,
    idp_id uuid,
    provider_subject_id character varying(500) CONSTRAINT auth_idpidentity_idp_identity_id_not_null NOT NULL,
    email character varying(255),
    email_verified boolean DEFAULT false CONSTRAINT auth_idpidentity_email_verified_not_null NOT NULL,
    profile jsonb,
    login_ip character varying(45),
    created_at timestamp with time zone CONSTRAINT auth_idpidentity_created_at_not_null NOT NULL,
    updated_at timestamp with time zone CONSTRAINT auth_idpidentity_updated_at_not_null NOT NULL,
    idp_type smallint CONSTRAINT auth_idpidentity_provider_type_not_null NOT NULL,
    phone_calling_code character varying(8),
    phone_country_code character varying(2),
    phone_national_number character varying(32),
    phone_verified boolean DEFAULT false CONSTRAINT auth_idpidentity_phone_verified_not_null NOT NULL
);


--
-- Name: core_auth_install; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.core_auth_install (
    id uuid CONSTRAINT core_install_id_not_null NOT NULL,
    project_id text CONSTRAINT core_install_project_id_not_null NOT NULL,
    platform integer,
    device_info jsonb,
    app_version text,
    ota_version text,
    locale text,
    country text,
    currency text,
    reg_ip text,
    firebase_install_id text,
    fcm_token text,
    created_at timestamp with time zone CONSTRAINT core_install_created_at_not_null NOT NULL,
    updated_at timestamp with time zone CONSTRAINT core_install_updated_at_not_null NOT NULL,
    scan_result_noti_enabled boolean DEFAULT true CONSTRAINT core_install_scan_result_noti_enabled_not_null NOT NULL,
    fcm_token_valid boolean DEFAULT true CONSTRAINT core_install_fcm_token_valid_not_null NOT NULL,
    deep_research_noti_enabled boolean DEFAULT true CONSTRAINT core_install_deep_research_noti_enabled_not_null NOT NULL,
    store_type integer
);


--
-- Name: core_auth_install2customer; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.core_auth_install2customer (
    id uuid CONSTRAINT core_install_customer_relation_id_not_null NOT NULL,
    project_id text CONSTRAINT core_install_customer_relation_project_id_not_null NOT NULL,
    install_id uuid CONSTRAINT core_install_customer_relation_install_id_not_null NOT NULL,
    customer_id uuid CONSTRAINT core_install_customer_relation_customer_id_not_null NOT NULL,
    created_at timestamp with time zone CONSTRAINT core_install_customer_relation_created_at_not_null NOT NULL,
    updated_at timestamp with time zone CONSTRAINT core_install_customer_relation_updated_at_not_null NOT NULL,
    deleted_at timestamp with time zone
);


--
-- Name: core_auth_installattestation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.core_auth_installattestation (
    id uuid CONSTRAINT core_install_attestation_id_not_null NOT NULL,
    project_id text CONSTRAINT core_install_attestation_project_id_not_null NOT NULL,
    install_id uuid CONSTRAINT core_install_attestation_install_id_not_null NOT NULL,
    provider integer CONSTRAINT core_install_attestation_provider_not_null NOT NULL,
    subject text,
    public_key bytea,
    attestation_object bytea,
    sign_count bigint DEFAULT 0 CONSTRAINT core_install_attestation_sign_count_not_null NOT NULL,
    receipt bytea,
    receipt_expires_at timestamp with time zone,
    next_refresh_at timestamp with time zone,
    refresh_failure_count integer DEFAULT 0 CONSTRAINT core_install_attestation_refresh_failure_count_not_null NOT NULL,
    fraud_metric integer,
    signals jsonb CONSTRAINT core_install_attestation_signals_not_null NOT NULL,
    evidence jsonb,
    status integer CONSTRAINT core_install_attestation_status_not_null NOT NULL,
    created_at timestamp with time zone CONSTRAINT core_install_attestation_created_at_not_null NOT NULL,
    updated_at timestamp with time zone CONSTRAINT core_install_attestation_updated_at_not_null NOT NULL,
    last_used_at timestamp with time zone,
    verify_status integer,
    challenge text
);


--
-- Name: core_auth_project2idp; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.core_auth_project2idp (
    id uuid CONSTRAINT auth_project_to_idp_relation_id_not_null NOT NULL,
    project_id character varying(30) CONSTRAINT auth_project_to_idp_relation_project_id_not_null NOT NULL,
    idp_id uuid CONSTRAINT auth_project_to_idp_relation_idp_id_not_null NOT NULL,
    created_at timestamp with time zone CONSTRAINT auth_project_to_idp_relation_created_at_not_null NOT NULL,
    updated_at timestamp with time zone CONSTRAINT auth_project_to_idp_relation_updated_at_not_null NOT NULL,
    deleted_at timestamp with time zone
);


--
-- Name: core_auth_refreshtoken; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.core_auth_refreshtoken (
    id uuid CONSTRAINT auth_appuser_refreshtoken_id_not_null NOT NULL,
    project_id character varying(30) CONSTRAINT auth_appuser_refreshtoken_app_id_not_null NOT NULL,
    actor_id uuid CONSTRAINT auth_appuser_refreshtoken_app_user_id_not_null NOT NULL,
    token_hash character varying(128) CONSTRAINT auth_appuser_refreshtoken_token_hash_not_null NOT NULL,
    login_install_id uuid,
    expires_at timestamp with time zone,
    revoked_at timestamp with time zone,
    replaced_by uuid,
    created_at timestamp with time zone CONSTRAINT auth_appuser_refreshtoken_created_at_not_null NOT NULL,
    updated_at timestamp with time zone CONSTRAINT auth_appuser_refreshtoken_updated_at_not_null NOT NULL,
    actor_type smallint DEFAULT 10 CONSTRAINT auth_appuser_refreshtoken_actor_type_not_null NOT NULL
);


--
-- Name: core_cs_feedback; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.core_cs_feedback (
    id uuid CONSTRAINT feedback_id_not_null NOT NULL,
    project_id character varying(30) CONSTRAINT feedback_project_id_not_null NOT NULL,
    customer_id uuid,
    scan_record_id uuid,
    comment character varying(1000),
    created_at timestamp with time zone CONSTRAINT feedback_created_at_not_null NOT NULL,
    locale character varying(16),
    country character varying(8),
    currency character varying(8),
    spm character varying(255),
    reasons smallint[] CONSTRAINT cms_feedback_reasons_not_null NOT NULL,
    topic smallint CONSTRAINT cms_feedback_topic_not_null NOT NULL,
    email character varying(320),
    phone character varying(32),
    install_id uuid,
    app_version character varying(64),
    ota_version character varying(64)
);


--
-- Name: core_cs_supportrequest; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.core_cs_supportrequest (
    id uuid CONSTRAINT cs_support_request_id_not_null NOT NULL,
    project_id character varying(30) CONSTRAINT cs_support_request_project_id_not_null NOT NULL,
    install_id uuid,
    customer_id uuid,
    locale character varying(35),
    country character varying(2),
    currency character varying(3),
    title text CONSTRAINT cs_support_request_title_not_null NOT NULL,
    message text CONSTRAINT cs_support_request_message_not_null NOT NULL,
    email character varying(320),
    phone character varying(32),
    category smallint DEFAULT 0 CONSTRAINT cs_support_request_category_not_null NOT NULL,
    status smallint DEFAULT 10 CONSTRAINT cs_support_request_status_not_null NOT NULL,
    attachments jsonb,
    created_at timestamp with time zone DEFAULT now() CONSTRAINT cs_support_request_created_at_not_null NOT NULL,
    updated_at timestamp with time zone DEFAULT now() CONSTRAINT cs_support_request_updated_at_not_null NOT NULL,
    first_replied_at timestamp with time zone,
    last_agent_replied_at timestamp with time zone,
    last_customer_replied_at timestamp with time zone,
    resolved_at timestamp with time zone,
    closed_at timestamp with time zone,
    app_version character varying(64),
    ota_version character varying(64)
);


--
-- Name: core_demo_todo; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.core_demo_todo (
    id uuid CONSTRAINT todo_id_not_null NOT NULL,
    title character varying(255) CONSTRAINT todo_title_not_null NOT NULL,
    done boolean DEFAULT false CONSTRAINT todo_done_not_null NOT NULL,
    project_id character varying(30) CONSTRAINT todo_project_id_not_null NOT NULL,
    created_at timestamp with time zone CONSTRAINT todo_created_at_not_null NOT NULL,
    updated_at timestamp with time zone CONSTRAINT todo_updated_at_not_null NOT NULL,
    deleted_at timestamp with time zone,
    customer_id uuid,
    meta jsonb,
    note text,
    recommend jsonb
);


--
-- Name: core_demo_todoitem; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.core_demo_todoitem (
    id uuid CONSTRAINT todo_item_id_not_null NOT NULL,
    todo_id uuid CONSTRAINT todo_item_todo_id_not_null NOT NULL,
    project_id character varying(30) CONSTRAINT todo_item_project_id_not_null NOT NULL,
    content character varying(1000) CONSTRAINT todo_item_content_not_null NOT NULL,
    done boolean DEFAULT false CONSTRAINT todo_item_done_not_null NOT NULL,
    created_at timestamp with time zone CONSTRAINT todo_item_created_at_not_null NOT NULL,
    updated_at timestamp with time zone CONSTRAINT todo_item_updated_at_not_null NOT NULL,
    deleted_at timestamp with time zone,
    note text
);


--
-- Name: core_media_filerecord; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.core_media_filerecord (
    id uuid CONSTRAINT core_upload_record_id_not_null NOT NULL,
    project_id character varying(30) CONSTRAINT core_upload_record_project_id_not_null NOT NULL,
    object_key text CONSTRAINT core_upload_record_object_key_not_null NOT NULL,
    content_type character varying(64) CONSTRAINT core_upload_record_content_type_not_null NOT NULL,
    client_ip character varying(64),
    created_at timestamp with time zone DEFAULT now() CONSTRAINT core_upload_record_created_at_not_null NOT NULL,
    actor_id uuid CONSTRAINT media_upload_record_actor_id_not_null NOT NULL,
    actor_type smallint CONSTRAINT media_upload_record_actor_type_not_null NOT NULL
);


--
-- Name: core_pay_storenotification; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.core_pay_storenotification (
    id uuid CONSTRAINT store_notification_id_not_null NOT NULL,
    project_id character varying(30) CONSTRAINT store_notification_project_id_not_null NOT NULL,
    platform character varying(32),
    subscription_pxid character varying(255),
    purchase_token text,
    notification_type character varying(64),
    raw_payload jsonb,
    processed boolean DEFAULT false CONSTRAINT store_notification_processed_not_null NOT NULL,
    processed_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() CONSTRAINT store_notification_created_at_not_null NOT NULL,
    updated_at timestamp with time zone DEFAULT now() CONSTRAINT store_notification_updated_at_not_null NOT NULL,
    deleted_at timestamp with time zone
);


--
-- Name: core_pay_subscription; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.core_pay_subscription (
    id uuid CONSTRAINT subscription_id_not_null NOT NULL,
    project_id character varying(30) CONSTRAINT subscription_project_id_not_null NOT NULL,
    subscription_pxid character varying(255),
    original_transaction_id text,
    product_id character varying(255),
    platform smallint CONSTRAINT core_subscription_platform_not_null NOT NULL,
    active boolean DEFAULT false CONSTRAINT subscription_active_not_null NOT NULL,
    sub_status character varying(32),
    expiry_date timestamp with time zone,
    purchase_token text,
    raw_response jsonb,
    created_at timestamp with time zone DEFAULT now() CONSTRAINT subscription_created_at_not_null NOT NULL,
    updated_at timestamp with time zone DEFAULT now() CONSTRAINT subscription_updated_at_not_null NOT NULL,
    deleted_at timestamp with time zone,
    customer_id uuid
);


--
-- Name: core_project_configrevision; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.core_project_configrevision (
    id uuid CONSTRAINT project_config_id_not_null NOT NULL,
    project_id character varying(30) CONSTRAINT project_config_project_id_not_null NOT NULL,
    apple_bundle_id character varying(255),
    android_package_name character varying(255),
    revision_number integer DEFAULT 1 CONSTRAINT project_config_revision_not_null NOT NULL,
    created_at timestamp with time zone DEFAULT now() CONSTRAINT project_config_created_at_not_null NOT NULL,
    enabled boolean DEFAULT false CONSTRAINT core_project_config_version_enabled_not_null NOT NULL,
    slug character varying(64) DEFAULT ''::character varying CONSTRAINT core_project_config_version_slug_not_null NOT NULL,
    content jsonb CONSTRAINT core_project_config_version_content_not_null NOT NULL,
    note text DEFAULT ''::text CONSTRAINT core_project_config_revision_note_not_null NOT NULL
);


--
-- Name: core_project_info; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.core_project_info (
    id character varying(30) CONSTRAINT project_info_id_not_null NOT NULL,
    name character varying(255),
    description text,
    created_at timestamp with time zone DEFAULT now() CONSTRAINT project_info_created_at_not_null NOT NULL,
    updated_at timestamp with time zone DEFAULT now() CONSTRAINT project_info_updated_at_not_null NOT NULL
);


--
-- Name: core_project_serverconfig; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.core_project_serverconfig (
    id uuid CONSTRAINT core_project_server_config_id_not_null NOT NULL,
    project_id character varying(64) CONSTRAINT core_project_server_config_project_id_not_null NOT NULL,
    fcm_config jsonb,
    created_at timestamp with time zone DEFAULT now() CONSTRAINT core_project_server_config_created_at_not_null NOT NULL,
    updated_at timestamp with time zone DEFAULT now() CONSTRAINT core_project_server_config_updated_at_not_null NOT NULL,
    app_attest_config jsonb
);


--
-- Name: core_ai_apikey agnes_key_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.core_ai_apikey
    ADD CONSTRAINT agnes_key_pkey PRIMARY KEY (id);


--
-- Name: core_ai_deepresearch ai_scan_deep_research_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.core_ai_deepresearch
    ADD CONSTRAINT ai_scan_deep_research_pkey PRIMARY KEY (id);


--
-- Name: core_auth_customer app_user_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.core_auth_customer
    ADD CONSTRAINT app_user_pkey PRIMARY KEY (id);


--
-- Name: core_auth_identity auth_identity_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.core_auth_identity
    ADD CONSTRAINT auth_identity_pkey PRIMARY KEY (id);


--
-- Name: core_auth_identity2idp auth_identity_to_idpidentity_relation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.core_auth_identity2idp
    ADD CONSTRAINT auth_identity_to_idpidentity_relation_pkey PRIMARY KEY (id);


--
-- Name: core_auth_idp auth_idp_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.core_auth_idp
    ADD CONSTRAINT auth_idp_pkey PRIMARY KEY (id);


--
-- Name: core_auth_idpidentity auth_idpidentity_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.core_auth_idpidentity
    ADD CONSTRAINT auth_idpidentity_pkey PRIMARY KEY (id);


--
-- Name: core_auth_project2idp auth_project_to_idp_relation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.core_auth_project2idp
    ADD CONSTRAINT auth_project_to_idp_relation_pkey PRIMARY KEY (id);


--
-- Name: core_auth_refreshtoken auth_refreshtoken_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.core_auth_refreshtoken
    ADD CONSTRAINT auth_refreshtoken_pkey PRIMARY KEY (id);


--
-- Name: core_ai_scancollectionitem collection_item_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.core_ai_scancollectionitem
    ADD CONSTRAINT collection_item_pkey PRIMARY KEY (id);


--
-- Name: core_ai_scancollection collection_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.core_ai_scancollection
    ADD CONSTRAINT collection_pkey PRIMARY KEY (id);


--
-- Name: core_ai_scanmetrics core_ai_customer_scan_metrics_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.core_ai_scanmetrics
    ADD CONSTRAINT core_ai_customer_scan_metrics_pkey PRIMARY KEY (id);


--
-- Name: core_auth_installattestation core_install_attestation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.core_auth_installattestation
    ADD CONSTRAINT core_install_attestation_pkey PRIMARY KEY (id);


--
-- Name: core_auth_install2customer core_install_customer_relation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.core_auth_install2customer
    ADD CONSTRAINT core_install_customer_relation_pkey PRIMARY KEY (id);


--
-- Name: core_auth_install core_install_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.core_auth_install
    ADD CONSTRAINT core_install_pkey PRIMARY KEY (id);


--
-- Name: core_project_serverconfig core_project_server_config_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.core_project_serverconfig
    ADD CONSTRAINT core_project_server_config_pkey PRIMARY KEY (id);


--
-- Name: core_media_filerecord core_upload_record_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.core_media_filerecord
    ADD CONSTRAINT core_upload_record_pkey PRIMARY KEY (id);


--
-- Name: core_cs_supportrequest cs_support_request_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.core_cs_supportrequest
    ADD CONSTRAINT cs_support_request_pkey PRIMARY KEY (id);


--
-- Name: core_cs_feedback feedback_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.core_cs_feedback
    ADD CONSTRAINT feedback_pkey PRIMARY KEY (id);


--
-- Name: core_project_configrevision project_config_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.core_project_configrevision
    ADD CONSTRAINT project_config_pkey PRIMARY KEY (id);


--
-- Name: core_project_info project_info_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.core_project_info
    ADD CONSTRAINT project_info_pkey PRIMARY KEY (id);


--
-- Name: core_ai_scanrecord scan_record_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.core_ai_scanrecord
    ADD CONSTRAINT scan_record_pkey PRIMARY KEY (id);


--
-- Name: core_pay_storenotification store_notification_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.core_pay_storenotification
    ADD CONSTRAINT store_notification_pkey PRIMARY KEY (id);


--
-- Name: core_pay_subscription subscription_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.core_pay_subscription
    ADD CONSTRAINT subscription_pkey PRIMARY KEY (id);


--
-- Name: core_demo_todoitem todo_item_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.core_demo_todoitem
    ADD CONSTRAINT todo_item_pkey PRIMARY KEY (id);


--
-- Name: core_demo_todo todo_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.core_demo_todo
    ADD CONSTRAINT todo_pkey PRIMARY KEY (id);


--
-- Name: ai_scan_deep_research_project_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ai_scan_deep_research_project_id_idx ON public.core_ai_deepresearch USING btree (project_id, id DESC);


--
-- Name: ai_scan_deep_research_scan_record_created_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ai_scan_deep_research_scan_record_created_idx ON public.core_ai_deepresearch USING btree (scan_record_id, created_at);


--
-- Name: api_key_uq; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX api_key_uq ON public.core_ai_apikey USING btree (key);


--
-- Name: app_config_bundle_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX app_config_bundle_idx ON public.core_project_configrevision USING btree (apple_bundle_id) WHERE (apple_bundle_id IS NOT NULL);


--
-- Name: auth_identity_project_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX auth_identity_project_idx ON public.core_auth_identity USING btree (project_id);


--
-- Name: auth_identity_rel_app_authidentity_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX auth_identity_rel_app_authidentity_idx ON public.core_auth_identity2idp USING btree (project_id, auth_identity_id);


--
-- Name: auth_identity_rel_project_idpidentity_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX auth_identity_rel_project_idpidentity_idx ON public.core_auth_identity2idp USING btree (project_id, idp_identity_id) WHERE (deleted_at IS NULL);


--
-- Name: auth_idpidentity_subject_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX auth_idpidentity_subject_idx ON public.core_auth_idpidentity USING btree (idp_id, provider_subject_id);


--
-- Name: auth_project_to_idp_relation_project_idp_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX auth_project_to_idp_relation_project_idp_idx ON public.core_auth_project2idp USING btree (project_id, idp_id);


--
-- Name: auth_refreshtoken_app_hash_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX auth_refreshtoken_app_hash_idx ON public.core_auth_refreshtoken USING btree (project_id, token_hash);


--
-- Name: collection_item_app_coll_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX collection_item_app_coll_idx ON public.core_ai_scancollectionitem USING btree (project_id, collection_id, id DESC);


--
-- Name: collection_item_scan_record_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX collection_item_scan_record_idx ON public.core_ai_scancollectionitem USING btree (project_id, scan_record_id);


--
-- Name: collection_item_scan_uq; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX collection_item_scan_uq ON public.core_ai_scancollectionitem USING btree (collection_id, scan_record_id) WHERE (deleted_at IS NULL);


--
-- Name: collection_project_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX collection_project_id_idx ON public.core_ai_scancollection USING btree (project_id, id DESC);


--
-- Name: core_ai_customer_scan_metrics_customer_uq; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX core_ai_customer_scan_metrics_customer_uq ON public.core_ai_scanmetrics USING btree (customer_id);


--
-- Name: core_install_customer_rel_install_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX core_install_customer_rel_install_idx ON public.core_auth_install2customer USING btree (project_id, install_id);


--
-- Name: core_install_customer_rel_uq; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX core_install_customer_rel_uq ON public.core_auth_install2customer USING btree (install_id, customer_id);


--
-- Name: core_project_server_config_project_id_uidx; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX core_project_server_config_project_id_uidx ON public.core_project_serverconfig USING btree (project_id);


--
-- Name: customer_auth_identity_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX customer_auth_identity_idx ON public.core_auth_customer USING btree (project_id, auth_identity_id);


--
-- Name: feedback_app_created_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX feedback_app_created_idx ON public.core_cs_feedback USING btree (project_id, created_at);


--
-- Name: idx_demo_todo_app_customer; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_demo_todo_app_customer ON public.core_demo_todo USING btree (project_id, customer_id) WHERE (customer_id IS NOT NULL);


--
-- Name: idx_install_attestation_backfill; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_install_attestation_backfill ON public.core_auth_installattestation USING btree (created_at) WHERE ((receipt IS NULL) AND (attestation_object IS NOT NULL));


--
-- Name: idx_install_attestation_evidence; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_install_attestation_evidence ON public.core_auth_installattestation USING btree (created_at) WHERE (evidence IS NOT NULL);


--
-- Name: idx_install_attestation_install; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_install_attestation_install ON public.core_auth_installattestation USING btree (project_id, install_id);


--
-- Name: idx_install_attestation_refresh; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_install_attestation_refresh ON public.core_auth_installattestation USING btree (next_refresh_at) WHERE (receipt IS NOT NULL);


--
-- Name: idx_upload_record_actor; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_upload_record_actor ON public.core_media_filerecord USING btree (actor_id, actor_type);


--
-- Name: idx_upload_record_created_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_upload_record_created_at ON public.core_media_filerecord USING btree (created_at);


--
-- Name: idx_upload_record_project_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_upload_record_project_id ON public.core_media_filerecord USING btree (project_id);


--
-- Name: ix_cs_support_request_owner; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_cs_support_request_owner ON public.core_cs_supportrequest USING btree (project_id, customer_id, id DESC);


--
-- Name: pay_subscription_app_customer_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX pay_subscription_app_customer_idx ON public.core_pay_subscription USING btree (project_id, customer_id);


--
-- Name: project_config_package_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX project_config_package_idx ON public.core_project_configrevision USING btree (android_package_name) WHERE (android_package_name IS NOT NULL);


--
-- Name: scan_record_project_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX scan_record_project_id_idx ON public.core_ai_scanrecord USING btree (project_id, id DESC);


--
-- Name: store_notif_platform_sub_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX store_notif_platform_sub_idx ON public.core_pay_storenotification USING btree (platform, subscription_pxid, processed_at);


--
-- Name: store_notif_platform_token_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX store_notif_platform_token_idx ON public.core_pay_storenotification USING btree (platform, purchase_token);


--
-- Name: subscription_original_txn_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX subscription_original_txn_idx ON public.core_pay_subscription USING btree (original_transaction_id);


--
-- Name: subscription_project_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX subscription_project_id_idx ON public.core_pay_subscription USING btree (project_id);


--
-- Name: subscription_pxid_active_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX subscription_pxid_active_idx ON public.core_pay_subscription USING btree (subscription_pxid, active) WHERE (active = true);


--
-- Name: todo_item_demo_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX todo_item_demo_id_idx ON public.core_demo_todoitem USING btree (todo_id);


--
-- Name: todo_item_project_id_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX todo_item_project_id_id_idx ON public.core_demo_todoitem USING btree (project_id, id);


--
-- Name: todo_project_id_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX todo_project_id_id_idx ON public.core_demo_todo USING btree (project_id, id);


--
-- Name: uk_install_attestation_subject; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_install_attestation_subject ON public.core_auth_installattestation USING btree (project_id, provider, subject) WHERE ((subject IS NOT NULL) AND (verify_status = 10));


--
-- Name: core_ai_scancollectionitem collection_item_collection_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.core_ai_scancollectionitem
    ADD CONSTRAINT collection_item_collection_id_fkey FOREIGN KEY (collection_id) REFERENCES public.core_ai_scancollection(id);


--
-- Name: core_ai_scancollectionitem collection_item_scan_record_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.core_ai_scancollectionitem
    ADD CONSTRAINT collection_item_scan_record_id_fkey FOREIGN KEY (scan_record_id) REFERENCES public.core_ai_scanrecord(id);


--
-- PostgreSQL database dump complete
--


