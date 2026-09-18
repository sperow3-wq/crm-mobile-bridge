-- CRM Mobile Bridge 0.6.0
-- Rozszerzenie o identyfikację służbowej karty SIM i pełną historię SMS.

ALTER TABLE mobile_devices
    ADD COLUMN service_subscription_id INT NULL AFTER service_phone,
    ADD COLUMN service_sim_slot_index INT NULL AFTER service_subscription_id,
    ADD COLUMN service_carrier_name VARCHAR(120) NULL AFTER service_sim_slot_index;

ALTER TABLE mobile_sms_events
    ADD COLUMN service_subscription_id INT NULL AFTER remote_phone,
    ADD COLUMN provider_message_id BIGINT NULL AFTER service_subscription_id,
    ADD COLUMN status VARCHAR(40) NULL AFTER direction;

CREATE INDEX ix_mobile_sms_subscription_time
    ON mobile_sms_events(service_subscription_id, occurred_at);

-- event_uuid pozostaje głównym mechanizmem idempotencji. provider_message_id służy audytowi
-- i diagnostyce; numer provider ID jest lokalny dla konkretnego urządzenia.
CREATE INDEX ix_mobile_sms_provider_message
    ON mobile_sms_events(employee_id, provider_message_id);

-- Opcjonalny widok do wpięcia w oś aktywności klienta CRM.
CREATE OR REPLACE VIEW v_mobile_client_activity AS
SELECT
    CONCAT('call:', c.event_uuid) AS activity_key,
    c.client_id,
    c.employee_id,
    'call' AS activity_type,
    c.direction,
    c.status,
    c.remote_phone,
    NULL AS message_body,
    c.started_at AS occurred_at,
    c.duration_seconds
FROM mobile_call_events c
WHERE c.client_id IS NOT NULL
UNION ALL
SELECT
    CONCAT('sms:', s.event_uuid) AS activity_key,
    s.client_id,
    s.employee_id,
    'sms' AS activity_type,
    s.direction,
    s.status,
    s.remote_phone,
    s.body AS message_body,
    s.occurred_at,
    NULL AS duration_seconds
FROM mobile_sms_events s
WHERE s.client_id IS NOT NULL;
