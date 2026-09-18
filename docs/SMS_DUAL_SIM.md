# SMS + Dual-SIM — projekt 0.6.0

## Zasada prywatności

Do CRM może trafić tylko SMS, dla którego systemowa kolumna `sub_id` odpowiada `DeviceStore.serviceSubscriptionId`.

Nie stosujemy heurystyki typu „pierwsza karta”, „domyślna karta SMS” ani „ostatnio używana karta”. Jeśli identyfikacja subskrypcji jest niepewna, wiadomość nie jest synchronizowana.

## Wychodzące

`Telephony.Sms.Sent` zawiera SMS wysłane przez systemowe aplikacje wiadomości. Scanner czyta nowe rekordy po `_id` i filtruje po `sub_id`.

`event_uuid = UUID.nameUUIDFromBytes(device_uuid + kierunek + provider_message_id)`

Daje to idempotencję przy wielokrotnym skanowaniu i retry.

## Przychodzące

Broadcast `SMS_RECEIVED` nie jest źródłem treści dla CRM. Jest wyłącznie sygnałem do uruchomienia skanera. Treść i subskrypcja są pobierane z `Telephony.Sms.Inbox`, dzięki czemu obowiązuje ten sam filtr `sub_id`.

## Checkpoint

Osobno przechowujemy:

- `last_inbox_sms_provider_id`,
- `last_sent_sms_provider_id`.

Po parowaniu oba checkpointy są ustawiane na bieżące maksimum, aby nie importować starej historii.
