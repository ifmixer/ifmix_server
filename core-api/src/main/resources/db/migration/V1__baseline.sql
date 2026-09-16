-- V1: baseline schema (squashed from historical V1..V10 on 2026-09-12).
-- Source: pg_dump --schema-only of core_api_local at schema version v10.
-- flyway_schema_history is managed by Flyway and intentionally excluded.

--
-- PostgreSQL database dump
--

-- Dumped from database version 18.1 (Homebrew)
-- Dumped by pg_dump version 18.1 (Postgres.app)

--
-- Name: ai_agnes_key; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ai_agnes_key (
    id uuid CONSTRAINT agnes_key_id_not_null NOT NULL,
    project_id character varying(30) CONSTRAINT agnes_key_project_id_not_null NOT NULL,
    key character varying(512) CONSTRAINT agnes_key_key_not_null NOT NULL,
    email character varying(255),
    rate_limit bigint DEFAULT '-1'::integer CONSTRAINT agnes_key_rate_limit_not_null NOT NULL,
    window_sec bigint DEFAULT 86400 CONSTRAINT agnes_key_window_sec_not_null NOT NULL,
    models text,
    unavailable_until timestamp with time zone,
    created_at timestamp with time zone DEFAULT now() CONSTRAINT agnes_key_created_at_not_null NOT NULL,
    updated_at timestamp with time zone DEFAULT now() CONSTRAINT agnes_key_updated_at_not_null NOT NULL,
    deleted_at timestamp with time zone,
    type smallint DEFAULT 100 CONSTRAINT core_agnes_key_type_not_null NOT NULL
);

--
-- Name: ai_scan_collection; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ai_scan_collection (
    id uuid CONSTRAINT collection_id_not_null NOT NULL,
    project_id character varying(30) CONSTRAINT collection_project_id_not_null NOT NULL,
    customer_id uuid,
    is_default boolean DEFAULT false CONSTRAINT collection_is_default_not_null NOT NULL,
    created_at timestamp with time zone DEFAULT now() CONSTRAINT collection_created_at_not_null NOT NULL,
    updated_at timestamp with time zone DEFAULT now() CONSTRAINT collection_updated_at_not_null NOT NULL,
    deleted_at timestamp with time zone,
    install_id character varying(128)
);

--
-- Name: ai_scan_collection_item; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ai_scan_collection_item (
    id uuid CONSTRAINT collection_item_id_not_null NOT NULL,
    project_id character varying(30) CONSTRAINT collection_item_project_id_not_null NOT NULL,
    collection_id uuid CONSTRAINT collection_item_collection_id_not_null NOT NULL,
    scan_record_id uuid CONSTRAINT collection_item_scan_record_id_not_null NOT NULL,
    created_at timestamp with time zone DEFAULT now() CONSTRAINT collection_item_created_at_not_null NOT NULL,
    updated_at timestamp with time zone DEFAULT now() CONSTRAINT collection_item_updated_at_not_null NOT NULL,
    deleted_at timestamp with time zone
);

--
-- Name: ai_scan_deep_research; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ai_scan_deep_research (
    id uuid NOT NULL,
    project_id character varying(30) NOT NULL,
    scan_record_id uuid NOT NULL,
    premium_result jsonb,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    prompt_version character varying(32) DEFAULT 'v10'::character varying NOT NULL
);

--
-- Name: ai_scan_record; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ai_scan_record (
    id uuid CONSTRAINT scan_record_id_not_null NOT NULL,
    project_id character varying(30) CONSTRAINT scan_record_project_id_not_null NOT NULL,
    status smallint DEFAULT 100 CONSTRAINT core_scan_record_status_not_null NOT NULL,
    client_ip character varying(45),
    created_at timestamp with time zone DEFAULT now() CONSTRAINT scan_record_created_at_not_null NOT NULL,
    updated_at timestamp with time zone DEFAULT now() CONSTRAINT scan_record_updated_at_not_null NOT NULL,
    deleted_at timestamp with time zone,
    image_keys jsonb DEFAULT '[]'::jsonb CONSTRAINT core_scan_record_image_keys_not_null NOT NULL,
    user_display_name character varying(255),
    user_notes text,
    collected boolean DEFAULT false CONSTRAINT core_scan_record_collected_not_null NOT NULL,
    locale character varying(16),
    country character varying(8),
    currency character varying(8),
    basic_result jsonb,
    customer_id uuid,
    has_deep_search boolean DEFAULT false NOT NULL,
    prompt_version character varying(32) DEFAULT 'v10'::character varying NOT NULL,
    is_public boolean DEFAULT true NOT NULL,
    install_id character varying(128)
);

--
-- Name: project_config_revision; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.project_config_revision (
    id uuid CONSTRAINT project_config_id_not_null NOT NULL,
    project_id character varying(30) CONSTRAINT project_config_project_id_not_null NOT NULL,
    apple_bundle_id character varying(255),
    android_package_name character varying(255),
    revision_number integer DEFAULT 1 CONSTRAINT project_config_revision_not_null NOT NULL,
    created_at timestamp with time zone DEFAULT now() CONSTRAINT project_config_created_at_not_null NOT NULL,
    enabled boolean DEFAULT false CONSTRAINT core_project_config_version_enabled_not_null NOT NULL,
    slug character varying(64) DEFAULT ''::character varying CONSTRAINT core_project_config_version_slug_not_null NOT NULL,
    content jsonb DEFAULT '{}'::jsonb CONSTRAINT core_project_config_version_content_not_null NOT NULL,
    note text DEFAULT ''::text CONSTRAINT core_project_config_revision_note_not_null NOT NULL
);

--
-- Name: project_info; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.project_info (
    id character varying(30) NOT NULL,
    name character varying(255),
    description text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

--
-- Name: auth_project_to_idp_relation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.auth_project_to_idp_relation (
    id uuid NOT NULL,
    project_id character varying(30) NOT NULL,
    idp_id uuid NOT NULL,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    deleted_at timestamp with time zone
);

--
-- Name: auth_identity; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.auth_identity (
    id uuid NOT NULL,
    project_id character varying(30) NOT NULL,
    password character varying(255),
    first_name character varying(255),
    last_name character varying(255),
    email character varying(255),
    email_verified boolean DEFAULT false NOT NULL,
    phone_calling_code character varying(8),
    phone_country_code character varying(2),
    phone_national_number character varying(32),
    phone_verified boolean DEFAULT false NOT NULL,
    last_login_at timestamp with time zone,
    last_login_method smallint,
    last_login_idp_identity_id uuid,
    last_login_ip character varying(45),
    metadata jsonb,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

--
-- Name: auth_identity_to_idpidentity_relation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.auth_identity_to_idpidentity_relation (
    id uuid NOT NULL,
    project_id character varying(30) NOT NULL,
    auth_identity_id uuid NOT NULL,
    idp_identity_id uuid NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone
);

--
-- Name: auth_idp; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.auth_idp (
    id uuid NOT NULL,
    name character varying(255) NOT NULL,
    idp_type smallint CONSTRAINT auth_idp_provider_type_not_null NOT NULL,
    third_id character varying(500) NOT NULL,
    config jsonb DEFAULT '{}'::jsonb NOT NULL,
    "desc" text,
    created_at timestamp with time zone NOT NULL
);

--
-- Name: auth_idpidentity; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.auth_idpidentity (
    id uuid NOT NULL,
    idp_id uuid,
    provider_subject_id character varying(500) CONSTRAINT auth_idpidentity_idp_identity_id_not_null NOT NULL,
    email character varying(255),
    email_verified boolean DEFAULT false NOT NULL,
    profile jsonb,
    login_ip character varying(45),
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL,
    idp_type smallint CONSTRAINT auth_idpidentity_provider_type_not_null NOT NULL,
    phone_calling_code character varying(8),
    phone_country_code character varying(2),
    phone_national_number character varying(32),
    phone_verified boolean DEFAULT false NOT NULL
);

--
-- Name: auth_refreshtoken; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.auth_refreshtoken (
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
-- Name: cs_feedback; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.cs_feedback (
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
    reasons smallint[] DEFAULT '{}'::smallint[] CONSTRAINT cms_feedback_reasons_not_null NOT NULL,
    topic smallint CONSTRAINT cms_feedback_topic_not_null NOT NULL,
    email character varying(320),
    phone character varying(32),
    install_id character varying(128)
);

--
-- Name: cs_support_request; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.cs_support_request (
    id uuid NOT NULL,
    project_id character varying(30) NOT NULL,
    install_id character varying(128),
    customer_id uuid,
    locale character varying(35),
    country character varying(2),
    currency character varying(3),
    title text NOT NULL,
    message text NOT NULL,
    email character varying(320),
    phone character varying(32),
    category smallint DEFAULT 0 NOT NULL,
    status smallint DEFAULT 10 NOT NULL,
    attachments jsonb,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    first_replied_at timestamp with time zone,
    last_agent_replied_at timestamp with time zone,
    last_customer_replied_at timestamp with time zone,
    resolved_at timestamp with time zone,
    closed_at timestamp with time zone
);

--
-- Name: customer; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.customer (
    id uuid CONSTRAINT app_user_id_not_null NOT NULL,
    project_id character varying(30) CONSTRAINT app_user_app_id_not_null NOT NULL,
    created_at timestamp with time zone DEFAULT now() CONSTRAINT app_user_created_at_not_null NOT NULL,
    updated_at timestamp with time zone DEFAULT now() CONSTRAINT app_user_updated_at_not_null NOT NULL,
    anonymous boolean DEFAULT true NOT NULL,
    merged_to uuid,
    merged_to_at timestamp with time zone,
    auth_identity_id uuid
);

--
-- Name: demo_todo; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.demo_todo (
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
-- Name: demo_todo_item; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.demo_todo_item (
    id uuid CONSTRAINT todo_item_id_not_null NOT NULL,
    todo_id uuid CONSTRAINT todo_item_todo_id_not_null NOT NULL,
    project_id character varying(30) CONSTRAINT todo_item_project_id_not_null NOT NULL,
    content character varying(1000) CONSTRAINT todo_item_content_not_null NOT NULL,
    done boolean DEFAULT false CONSTRAINT todo_item_done_not_null NOT NULL,
    created_at timestamp with time zone CONSTRAINT todo_item_created_at_not_null NOT NULL,
    updated_at timestamp with time zone CONSTRAINT todo_item_updated_at_not_null NOT NULL,
    deleted_at timestamp with time zone
);

--
-- Name: media_upload_record; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.media_upload_record (
    id uuid CONSTRAINT core_upload_record_id_not_null NOT NULL,
    project_id character varying(30) CONSTRAINT core_upload_record_project_id_not_null NOT NULL,
    object_key text CONSTRAINT core_upload_record_object_key_not_null NOT NULL,
    content_type character varying(64) CONSTRAINT core_upload_record_content_type_not_null NOT NULL,
    client_ip character varying(64),
    created_at timestamp with time zone DEFAULT now() CONSTRAINT core_upload_record_created_at_not_null NOT NULL,
    actor_id uuid NOT NULL,
    actor_type smallint NOT NULL
);

--
-- Name: pay_store_notification; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.pay_store_notification (
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
-- Name: pay_subscription; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.pay_subscription (
    id uuid CONSTRAINT subscription_id_not_null NOT NULL,
    project_id character varying(30) CONSTRAINT subscription_project_id_not_null NOT NULL,
    subscription_pxid character varying(255),
    original_transaction_id text,
    product_id character varying(255),
    platform smallint DEFAULT 100 CONSTRAINT core_subscription_platform_not_null NOT NULL,
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
-- Name: ai_agnes_key agnes_key_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ai_agnes_key
    ADD CONSTRAINT agnes_key_pkey PRIMARY KEY (id);

--
-- Name: ai_scan_deep_research ai_scan_deep_research_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ai_scan_deep_research
    ADD CONSTRAINT ai_scan_deep_research_pkey PRIMARY KEY (id);

--
-- Name: project_config_revision project_config_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_config_revision
    ADD CONSTRAINT project_config_pkey PRIMARY KEY (id);

--
-- Name: project_info project_info_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.project_info
    ADD CONSTRAINT project_info_pkey PRIMARY KEY (id);

--
-- Name: customer app_user_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.customer
    ADD CONSTRAINT app_user_pkey PRIMARY KEY (id);

--
-- Name: auth_project_to_idp_relation auth_project_to_idp_relation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.auth_project_to_idp_relation
    ADD CONSTRAINT auth_project_to_idp_relation_pkey PRIMARY KEY (id);

--
-- Name: auth_identity auth_identity_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.auth_identity
    ADD CONSTRAINT auth_identity_pkey PRIMARY KEY (id);

--
-- Name: auth_identity_to_idpidentity_relation auth_identity_to_idpidentity_relation_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.auth_identity_to_idpidentity_relation
    ADD CONSTRAINT auth_identity_to_idpidentity_relation_pkey PRIMARY KEY (id);

--
-- Name: auth_idp auth_idp_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.auth_idp
    ADD CONSTRAINT auth_idp_pkey PRIMARY KEY (id);

--
-- Name: auth_idpidentity auth_idpidentity_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.auth_idpidentity
    ADD CONSTRAINT auth_idpidentity_pkey PRIMARY KEY (id);

--
-- Name: auth_refreshtoken auth_refreshtoken_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.auth_refreshtoken
    ADD CONSTRAINT auth_refreshtoken_pkey PRIMARY KEY (id);

--
-- Name: ai_scan_collection_item collection_item_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ai_scan_collection_item
    ADD CONSTRAINT collection_item_pkey PRIMARY KEY (id);

--
-- Name: ai_scan_collection collection_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ai_scan_collection
    ADD CONSTRAINT collection_pkey PRIMARY KEY (id);

--
-- Name: media_upload_record core_upload_record_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.media_upload_record
    ADD CONSTRAINT core_upload_record_pkey PRIMARY KEY (id);

--
-- Name: cs_support_request cs_support_request_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cs_support_request
    ADD CONSTRAINT cs_support_request_pkey PRIMARY KEY (id);

--
-- Name: cs_feedback feedback_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cs_feedback
    ADD CONSTRAINT feedback_pkey PRIMARY KEY (id);

--
-- Name: ai_scan_record scan_record_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ai_scan_record
    ADD CONSTRAINT scan_record_pkey PRIMARY KEY (id);

--
-- Name: pay_store_notification store_notification_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pay_store_notification
    ADD CONSTRAINT store_notification_pkey PRIMARY KEY (id);

--
-- Name: pay_subscription subscription_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.pay_subscription
    ADD CONSTRAINT subscription_pkey PRIMARY KEY (id);

--
-- Name: demo_todo_item todo_item_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.demo_todo_item
    ADD CONSTRAINT todo_item_pkey PRIMARY KEY (id);

--
-- Name: demo_todo todo_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.demo_todo
    ADD CONSTRAINT todo_pkey PRIMARY KEY (id);

--
-- Name: agnes_key_project_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX agnes_key_project_idx ON public.ai_agnes_key USING btree (project_id) WHERE (deleted_at IS NULL);

--
-- Name: agnes_key_unavailable_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX agnes_key_unavailable_idx ON public.ai_agnes_key USING btree (project_id, unavailable_until) WHERE ((unavailable_until IS NOT NULL) AND (deleted_at IS NULL));

--
-- Name: agnes_key_uq; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX agnes_key_uq ON public.ai_agnes_key USING btree (key);

--
-- Name: ai_scan_deep_research_project_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ai_scan_deep_research_project_id_idx ON public.ai_scan_deep_research USING btree (project_id, id DESC);

--
-- Name: ai_scan_deep_research_scan_record_id_uidx; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX ai_scan_deep_research_scan_record_id_uidx ON public.ai_scan_deep_research USING btree (scan_record_id);

--
-- Name: project_config_bundle_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX app_config_bundle_idx ON public.project_config_revision USING btree (apple_bundle_id) WHERE (apple_bundle_id IS NOT NULL);

--
-- Name: project_config_package_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX project_config_package_idx ON public.project_config_revision USING btree (android_package_name) WHERE (android_package_name IS NOT NULL);

--
-- Name: auth_project_to_idp_relation_project_idp_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX auth_project_to_idp_relation_project_idp_idx ON public.auth_project_to_idp_relation USING btree (project_id, idp_id);

--
-- Name: auth_identity_project_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX auth_identity_project_idx ON public.auth_identity USING btree (project_id);

--
-- Name: auth_identity_rel_app_authidentity_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX auth_identity_rel_app_authidentity_idx ON public.auth_identity_to_idpidentity_relation USING btree (project_id, auth_identity_id);

--
-- Name: auth_identity_rel_project_idpidentity_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX auth_identity_rel_project_idpidentity_idx ON public.auth_identity_to_idpidentity_relation USING btree (project_id, idp_identity_id) WHERE (deleted_at IS NULL);

--
-- Name: auth_idpidentity_subject_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX auth_idpidentity_subject_idx ON public.auth_idpidentity USING btree (idp_id, provider_subject_id);

--
-- Name: auth_refreshtoken_app_hash_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX auth_refreshtoken_app_hash_idx ON public.auth_refreshtoken USING btree (project_id, token_hash);

--
-- Name: collection_project_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX collection_project_id_idx ON public.ai_scan_collection USING btree (project_id, id DESC);

--
-- Name: collection_item_app_coll_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX collection_item_app_coll_idx ON public.ai_scan_collection_item USING btree (project_id, collection_id, id DESC);

--
-- Name: collection_item_scan_record_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX collection_item_scan_record_idx ON public.ai_scan_collection_item USING btree (project_id, scan_record_id);

--
-- Name: collection_item_scan_uq; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX collection_item_scan_uq ON public.ai_scan_collection_item USING btree (collection_id, scan_record_id) WHERE (deleted_at IS NULL);

--
-- Name: customer_auth_identity_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX customer_auth_identity_idx ON public.customer USING btree (project_id, auth_identity_id);

--
-- Name: feedback_app_created_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX feedback_app_created_idx ON public.cs_feedback USING btree (project_id, created_at);

--
-- Name: idx_demo_todo_app_customer; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_demo_todo_app_customer ON public.demo_todo USING btree (project_id, customer_id) WHERE (customer_id IS NOT NULL);

--
-- Name: idx_upload_record_actor; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_upload_record_actor ON public.media_upload_record USING btree (actor_id, actor_type);

--
-- Name: idx_upload_record_project_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_upload_record_project_id ON public.media_upload_record USING btree (project_id);

--
-- Name: idx_upload_record_created_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_upload_record_created_at ON public.media_upload_record USING btree (created_at);

--
-- Name: ix_cs_support_request_owner; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX ix_cs_support_request_owner ON public.cs_support_request USING btree (project_id, customer_id, id DESC);

--
-- Name: pay_subscription_app_customer_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX pay_subscription_app_customer_idx ON public.pay_subscription USING btree (project_id, customer_id);

--
-- Name: scan_record_project_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX scan_record_project_id_idx ON public.ai_scan_record USING btree (project_id, id DESC);

--
-- Name: store_notif_platform_sub_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX store_notif_platform_sub_idx ON public.pay_store_notification USING btree (platform, subscription_pxid, processed_at);

--
-- Name: store_notif_platform_token_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX store_notif_platform_token_idx ON public.pay_store_notification USING btree (platform, purchase_token);

--
-- Name: subscription_project_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX subscription_project_id_idx ON public.pay_subscription USING btree (project_id);

--
-- Name: subscription_original_txn_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX subscription_original_txn_idx ON public.pay_subscription USING btree (original_transaction_id);

--
-- Name: subscription_pxid_active_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX subscription_pxid_active_idx ON public.pay_subscription USING btree (subscription_pxid, active) WHERE (active = true);

--
-- Name: todo_project_id_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX todo_project_id_id_idx ON public.demo_todo USING btree (project_id, id);

--
-- Name: todo_item_project_id_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX todo_item_project_id_id_idx ON public.demo_todo_item USING btree (project_id, id);

--
-- Name: todo_item_demo_id_idx; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX todo_item_demo_id_idx ON public.demo_todo_item USING btree (todo_id);

--
-- Name: ai_scan_collection_item collection_item_collection_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ai_scan_collection_item
    ADD CONSTRAINT collection_item_collection_id_fkey FOREIGN KEY (collection_id) REFERENCES public.ai_scan_collection(id);

--
-- Name: ai_scan_collection_item collection_item_scan_record_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ai_scan_collection_item
    ADD CONSTRAINT collection_item_scan_record_id_fkey FOREIGN KEY (scan_record_id) REFERENCES public.ai_scan_record(id);

--
-- PostgreSQL database dump complete
--


