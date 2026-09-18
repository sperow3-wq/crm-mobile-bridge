# CRM Mobile Bridge — kontrakt API v0.8

Base URL w projekcie: `https://crm.usundlug.pl` — przed instalacją ustaw właściwy adres CRM w `app/build.gradle.kts`.

## 1. Rejestracja / powiązanie urządzenia

`POST /api/mobile/device/register`

Request:
```json
{
  "phone": "+48573481685",
  "device_uuid": "uuid",
  "device_manufacturer": "Samsung",
  "device_model": "SM-S931B",
  "android_version": "17",
  "android_sdk": 37,
  "service_subscription_id": 4,
  "service_sim_slot_index": 0,
  "service_carrier_name": "Orange PL"
}
```

Response:
```json
{
  "matched": true,
  "employee_id": 17,
  "employee_name": "Martyna Olszewska",
  "phone": "+48573481685",
  "device_token": "random-long-lived-device-token"
}
```

Po poprawnym sparowaniu aplikacja uruchamia natychmiastową synchronizację lokalnego cache Caller ID oraz synchronizację okresową co 6 godzin.

## 2. Identyfikacja klienta + stan finansowy

`POST /api/mobile/client/identify`

Header: `Authorization: Bearer <device_token>`

Request:
```json
{
  "phone": "+48501122333",
  "device_uuid": "uuid",
  "employee_id": 17
}
```

Response:
```json
{
  "matched": true,
  "client_id": 18271,
  "client_name": "Jan Kowalski",
  "phone": "+48501122333",
  "product": "Upadłość konsumencka",
  "stage": "Etap 3",
  "guardian_name": "Martyna Olszewska",
  "updated_at_epoch_ms": 1789730100000,
  "financial": {
    "overdue_invoices_count": 2,
    "overdue_amount": 950.00,
    "currency": "PLN"
  }
}
```

Aplikacja zapisuje udane dopasowanie do zaszyfrowanego cache. Jeżeli zapytanie sieciowe nie powiedzie się, aplikacja szuka numeru lokalnie. Jeżeli serwer odpowie poprawnie `matched=false`, ewentualne stare dopasowanie tego numeru jest usuwane z cache.

## 3. Synchronizacja lokalnego cache Caller ID

`POST /api/mobile/client/cache/sync`

Header: `Authorization: Bearer <device_token>`

Request pierwszej strony:
```json
{
  "device_uuid": "uuid",
  "employee_id": 17,
  "since_epoch_ms": 1789700000000,
  "limit": 500,
  "scope": "caller_id"
}
```

Kolejna strona dodatkowo zawiera:
```json
{
  "cursor": "opaque-server-cursor"
}
```

Response:
```json
{
  "server_time_epoch_ms": 1789730400000,
  "clients": [
    {
      "matched": true,
      "client_id": 18271,
      "client_name": "Jan Kowalski",
      "phone": "+48501122333",
      "product": "Upadłość konsumencka",
      "stage": "Etap 3",
      "guardian_name": "Martyna Olszewska",
      "updated_at_epoch_ms": 1789730100000,
      "financial": {
        "overdue_invoices_count": 2,
        "overdue_amount": 950.00,
        "currency": "PLN"
      }
    }
  ],
  "deleted_client_ids": [19211],
  "next_cursor": null
}
```

Wymagania endpointu cache:
- zwraca tylko minimum niezbędne do ekranu Caller ID,
- nie zwraca dokumentów, notatek, PESEL, adresów ani innych zbędnych danych,
- `since_epoch_ms` obsługuje synchronizację przyrostową,
- `deleted_client_ids` usuwa z telefonu klientów usuniętych lub niedostępnych,
- `next_cursor` jest nieprzezroczystym kursorem stronicowania,
- pełny watermark synchronizacji jest zapisywany dopiero po pobraniu ostatniej strony,
- limit 500 rekordów/stronę.

## 4. Zdarzenie połączenia — UPSERT po `event_uuid`

`POST /api/mobile/call/event`

Początek:
```json
{
  "event_uuid": "cc6992dc-4a1b-47ec-8a63-756e87939a04",
  "phone": "+48501122333",
  "device_uuid": "uuid",
  "employee_id": 17,
  "client_id": 18271,
  "direction": "incoming",
  "started_at_epoch_ms": 1789729200000,
  "status": "started"
}
```

Koniec — ten sam `event_uuid`:
```json
{
  "event_uuid": "cc6992dc-4a1b-47ec-8a63-756e87939a04",
  "phone": "+48501122333",
  "device_uuid": "uuid",
  "employee_id": 17,
  "client_id": 18271,
  "direction": "incoming",
  "started_at_epoch_ms": 1789729200000,
  "ended_at_epoch_ms": 1789729487000,
  "duration_seconds": 287,
  "status": "answered"
}
```

Serwer wykonuje UPSERT po `event_uuid`. Późniejsze zdarzenie końcowe uzupełnia istniejący wpis, a retry nie tworzy duplikatu.

## 5. Zdarzenie SMS

`POST /api/mobile/sms/event`

```json
{
  "event_uuid": "68d64640-13a8-44c6-b06b-c3e77bde8ab7",
  "phone": "+48501122333",
  "device_uuid": "uuid",
  "employee_id": 17,
  "client_id": 18271,
  "direction": "incoming",
  "body": "Dzień dobry...",
  "occurred_at_epoch_ms": 1789729200000,
  "service_subscription_id": 4,
  "provider_message_id": 8124,
  "status": "received",
  "source": "android_sms_provider"
}
```

Endpoint SMS powinien wykonywać UPSERT po `event_uuid`. Po zapisaniu zdarzenia CRM powinien udostępnić je na osi aktywności klienta. `provider_message_id` jest identyfikatorem lokalnym telefonu i służy do audytu; idempotencję między retry zapewnia `event_uuid`.

Statusy SMS używane w 0.6.0:
- `received`,
- `sent`,
- `sent_pending_delivery`,
- `delivered`,
- `delivery_failed`.

## Statusy połączeń

- `started`
- `answered`
- `missed`
- `rejected`
- `not_connected`
- `blocked`
- `answered_externally`
- `completed`

## Bezpieczeństwo i cache lokalny

- cache zawiera wyłącznie dane Caller ID,
- numer telefonu nie jest przechowywany jako jawny indeks; lokalny lookup używa HMAC-SHA256 numeru E.164,
- pełny rekord cache jest szyfrowany AES-256-GCM kluczem z Android Keystore,
- rekordy starsze niż 14 dni są usuwane,
- po odłączeniu urządzenia cały cache lokalny jest kasowany,
- dane offline są wyraźnie oznaczone na ekranie wraz z datą ostatniej aktualizacji,
- serwer musi zawsze autoryzować urządzenie przez `device_token` i przypisanie do pracownika.

## 6. Mobilna karta klienta + historia

`POST /api/mobile/client/overview`

Header: `Authorization: Bearer <device_token>`

Request:
```json
{
  "client_id": 18271,
  "employee_id": 17,
  "device_uuid": "uuid",
  "limit": 50
}
```

Response:
```json
{
  "server_time_epoch_ms": 1789731200000,
  "client": {
    "client_id": 18271,
    "client_name": "Jan Kowalski",
    "phone": "+48501122333",
    "product": "Upadłość konsumencka",
    "stage": "Etap 3",
    "guardian_name": "Martyna Olszewska",
    "financial": {
      "overdue_invoices_count": 2,
      "overdue_amount": 950.00,
      "currency": "PLN"
    }
  },
  "crm_url": "https://crm.usundlug.pl/clients/18271",
  "activities": [
    {
      "activity_key": "call:cc6992dc-4a1b-47ec-8a63-756e87939a04",
      "type": "call",
      "direction": "incoming",
      "status": "answered",
      "phone": "+48501122333",
      "message_body": null,
      "occurred_at_epoch_ms": 1789729200000,
      "duration_seconds": 287,
      "employee_name": "Martyna Olszewska",
      "result_label": "Kontakt skuteczny",
      "wrap_up_note": "Klient prześle dokumenty jutro.",
      "follow_up_at_epoch_ms": 1789998600000
    },
    {
      "activity_key": "sms:68d64640-13a8-44c6-b06b-c3e77bde8ab7",
      "type": "sms",
      "direction": "incoming",
      "status": "received",
      "phone": "+48501122333",
      "message_body": "Dzień dobry...",
      "occurred_at_epoch_ms": 1789728800000,
      "duration_seconds": null,
      "employee_name": "Martyna Olszewska"
    }
  ],
  "next_cursor": null
}
```

Wymagania endpointu:
- urządzenie musi należeć do aktywnego pracownika i posiadać ważny `device_token`,
- serwer musi zweryfikować, czy pracownik ma prawo zobaczyć wskazanego klienta,
- domyślny limit to 50, maksymalny 100 wpisów,
- aktywności sortowane malejąco po dacie,
- `crm_url` powinien wskazywać kartę klienta w tym samym zaufanym CRM,
- treść SMS jest zwracana wyłącznie do widoku po odblokowaniu telefonu i nie jest zapisywana w lokalnym cache historii,
- w trybie offline aplikacja pokazuje tylko zaszyfrowany nagłówek klienta (usługa, etap, opiekun, status faktur); historia wymaga połączenia z CRM.


## 7. Podsumowanie rozmowy

`POST /api/mobile/call/wrap-up`

Header: `Authorization: Bearer <device_token>`

Request:
```json
{
  "event_uuid": "cc6992dc-4a1b-47ec-8a63-756e87939a04",
  "device_uuid": "uuid",
  "employee_id": 17,
  "client_id": 18271,
  "result_code": "contact_successful",
  "result_label": "Kontakt skuteczny",
  "note": "Klient prześle dokumenty jutro.",
  "follow_up_at_epoch_ms": 1789998600000,
  "updated_at_epoch_ms": 1789731600000
}
```

Serwer aktualizuje rekord `mobile_call_events` po `event_uuid`. Endpoint powinien:
- autoryzować urządzenie po `device_token`,
- zweryfikować, że rozmowa należy do pracownika z tokenu lub pracownik ma prawo ją uzupełnić,
- znormalizować `result_code` do dozwolonej listy,
- ograniczyć długość notatki do 2000 znaków,
- zamienić `follow_up_at_epoch_ms` na `DATETIME(3)` w strefie serwera/UTC zgodnie z konwencją CRM,
- ustawić `wrap_up_updated_at`,
- być idempotentny: kolejne wywołanie dla tego samego `event_uuid` nadpisuje poprzednie podsumowanie, nie tworzy nowej rozmowy.

Kody używane przez aplikację 0.8.0:
- `contact_successful` — Kontakt skuteczny,
- `call_back` — Oddzwonić,
- `client_requests_later` — Klient prosi o kontakt później,
- `no_new_arrangements` — Brak nowych ustaleń,
- `no_further_contact` — Nie wymaga dalszego kontaktu.

Podsumowanie korzysta z tej samej szyfrowanej kolejki offline co połączenia i SMS-y. Klucz deduplikacji ma postać `call_wrap_up:<event_uuid>`, więc edycja przed synchronizacją zastępuje starszą wersję payloadu.
