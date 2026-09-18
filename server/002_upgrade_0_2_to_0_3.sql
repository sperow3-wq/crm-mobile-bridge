-- Upgrade istniejącej bazy testowej CRM Mobile Bridge 0.2.x -> 0.3.0
-- Uruchamiaj tylko wtedy, gdy tabele mobile_call_events/mobile_sms_events już istnieją.

ALTER TABLE mobile_call_events
    ADD COLUMN event_uuid CHAR(36) NULL AFTER id,
    ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER created_at;

UPDATE mobile_call_events
SET event_uuid = UUID()
WHERE event_uuid IS NULL OR event_uuid = '';

ALTER TABLE mobile_call_events
    MODIFY event_uuid CHAR(36) NOT NULL,
    ADD UNIQUE KEY ux_mobile_call_event_uuid (event_uuid),
    ADD KEY ix_mobile_call_phone_started (remote_phone, started_at);

ALTER TABLE mobile_sms_events
    ADD COLUMN event_uuid CHAR(36) NULL AFTER id;

UPDATE mobile_sms_events
SET event_uuid = UUID()
WHERE event_uuid IS NULL OR event_uuid = '';

ALTER TABLE mobile_sms_events
    MODIFY event_uuid CHAR(36) NOT NULL,
    ADD UNIQUE KEY ux_mobile_sms_event_uuid (event_uuid),
    ADD KEY ix_mobile_sms_phone_time (remote_phone, occurred_at);
