-- CRM Mobile Bridge 0.8.0
-- Podsumowanie rozmowy: wynik, notatka i termin ponownego kontaktu.

ALTER TABLE mobile_call_events
    ADD COLUMN result_code VARCHAR(60) NULL AFTER duration_seconds,
    ADD COLUMN result_label VARCHAR(160) NULL AFTER result_code,
    ADD COLUMN wrap_up_note TEXT NULL AFTER result_label,
    ADD COLUMN follow_up_at DATETIME(3) NULL AFTER wrap_up_note,
    ADD COLUMN wrap_up_updated_at DATETIME(3) NULL AFTER follow_up_at;

CREATE INDEX ix_mobile_call_follow_up ON mobile_call_events(follow_up_at);

CREATE OR REPLACE VIEW v_mobile_client_activity AS
SELECT
    CONCAT('call:', c.event_uuid) AS activity_key,
    c.client_id,
    c.employee_id,
    e.name AS employee_name,
    'call' AS activity_type,
    c.direction,
    c.status,
    c.remote_phone,
    NULL AS message_body,
    c.started_at AS occurred_at,
    c.duration_seconds,
    c.result_label,
    c.wrap_up_note,
    c.follow_up_at
FROM mobile_call_events c
LEFT JOIN employees e ON e.id = c.employee_id
WHERE c.client_id IS NOT NULL

UNION ALL

SELECT
    CONCAT('sms:', s.event_uuid) AS activity_key,
    s.client_id,
    s.employee_id,
    e.name AS employee_name,
    'sms' AS activity_type,
    s.direction,
    s.status,
    s.remote_phone,
    s.body AS message_body,
    s.occurred_at,
    NULL AS duration_seconds,
    NULL AS result_label,
    NULL AS wrap_up_note,
    NULL AS follow_up_at
FROM mobile_sms_events s
LEFT JOIN employees e ON e.id = s.employee_id
WHERE s.client_id IS NOT NULL;

-- Endpoint POST /api/mobile/call/wrap-up powinien wykonywać UPDATE po event_uuid
-- i zweryfikować, że employee_id z tokenu ma prawo do tej rozmowy/klienta.
