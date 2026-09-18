-- CRM Mobile Bridge 0.7.0
-- Rozszerzenie osi aktywności o nazwę pracownika dla mobilnej karty klienta.
-- Dopasuj nazwę/kolumny tabeli employees do finalnego schematu CRM.

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
    c.duration_seconds
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
    NULL AS duration_seconds
FROM mobile_sms_events s
LEFT JOIN employees e ON e.id = s.employee_id
WHERE s.client_id IS NOT NULL;

-- Przykładowe zapytanie dla /api/mobile/client/overview:
-- SELECT *
-- FROM v_mobile_client_activity
-- WHERE client_id = :client_id
-- ORDER BY occurred_at DESC
-- LIMIT :limit;
