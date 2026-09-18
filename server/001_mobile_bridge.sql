-- CRM Mobile Bridge 0.8.0 — pełny schemat dla nowej instalacji
-- Nazwy tabel employees/clients należy dopasować do finalnego schematu własnego CRM.

ALTER TABLE employees
    ADD COLUMN service_phone VARCHAR(20) NULL,
    ADD COLUMN mobile_app_enabled TINYINT(1) NOT NULL DEFAULT 1;

CREATE UNIQUE INDEX ux_employees_service_phone ON employees(service_phone);

CREATE TABLE mobile_devices (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    employee_id BIGINT UNSIGNED NOT NULL,
    device_uuid VARCHAR(64) NOT NULL,
    service_phone VARCHAR(20) NOT NULL,
    service_subscription_id INT NULL,
    service_sim_slot_index INT NULL,
    service_carrier_name VARCHAR(120) NULL,
    device_manufacturer VARCHAR(80) NULL,
    device_model VARCHAR(120) NULL,
    android_version VARCHAR(30) NULL,
    token_hash CHAR(64) NOT NULL,
    paired_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at DATETIME NULL,
    revoked_at DATETIME NULL,
    UNIQUE KEY ux_mobile_device_uuid (device_uuid),
    KEY ix_mobile_devices_employee_id (employee_id),
    CONSTRAINT fk_mobile_devices_employee FOREIGN KEY (employee_id) REFERENCES employees(id)
);

CREATE TABLE mobile_call_events (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    event_uuid CHAR(36) NOT NULL,
    employee_id BIGINT UNSIGNED NOT NULL,
    client_id BIGINT UNSIGNED NULL,
    remote_phone VARCHAR(20) NOT NULL,
    direction ENUM('incoming','outgoing') NOT NULL,
    status VARCHAR(30) NOT NULL,
    started_at DATETIME(3) NOT NULL,
    ended_at DATETIME(3) NULL,
    duration_seconds INT UNSIGNED NULL,
    result_code VARCHAR(60) NULL,
    result_label VARCHAR(160) NULL,
    wrap_up_note TEXT NULL,
    follow_up_at DATETIME(3) NULL,
    wrap_up_updated_at DATETIME(3) NULL,
    source VARCHAR(30) NOT NULL DEFAULT 'android',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY ux_mobile_call_event_uuid (event_uuid),
    KEY ix_mobile_call_client (client_id),
    KEY ix_mobile_call_employee (employee_id),
    KEY ix_mobile_call_phone_started (remote_phone, started_at),
    KEY ix_mobile_call_follow_up (follow_up_at)
);

CREATE TABLE mobile_sms_events (
    id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    event_uuid CHAR(36) NOT NULL,
    employee_id BIGINT UNSIGNED NOT NULL,
    client_id BIGINT UNSIGNED NULL,
    remote_phone VARCHAR(20) NOT NULL,
    service_subscription_id INT NULL,
    provider_message_id BIGINT NULL,
    direction ENUM('incoming','outgoing') NOT NULL,
    status VARCHAR(40) NULL,
    body TEXT NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    source VARCHAR(30) NOT NULL DEFAULT 'android',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY ux_mobile_sms_event_uuid (event_uuid),
    KEY ix_mobile_sms_client (client_id),
    KEY ix_mobile_sms_employee (employee_id),
    KEY ix_mobile_sms_phone_time (remote_phone, occurred_at),
    KEY ix_mobile_sms_subscription_time (service_subscription_id, occurred_at),
    KEY ix_mobile_sms_provider_message (employee_id, provider_message_id)
);

CREATE OR REPLACE VIEW v_mobile_client_activity AS
SELECT
    CONCAT('call:', c.event_uuid) AS activity_key,
    c.client_id, c.employee_id, e.name AS employee_name,
    'call' AS activity_type, c.direction, c.status,
    c.remote_phone, NULL AS message_body, c.started_at AS occurred_at, c.duration_seconds,
    c.result_label, c.wrap_up_note, c.follow_up_at
FROM mobile_call_events c
LEFT JOIN employees e ON e.id = c.employee_id
WHERE c.client_id IS NOT NULL
UNION ALL
SELECT
    CONCAT('sms:', s.event_uuid) AS activity_key,
    s.client_id, s.employee_id, e.name AS employee_name,
    'sms' AS activity_type, s.direction, s.status,
    s.remote_phone, s.body AS message_body, s.occurred_at, NULL AS duration_seconds,
    NULL AS result_label, NULL AS wrap_up_note, NULL AS follow_up_at
FROM mobile_sms_events s
LEFT JOIN employees e ON e.id = s.employee_id
WHERE s.client_id IS NOT NULL;
