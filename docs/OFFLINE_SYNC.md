# Offline sync — CRM Mobile Bridge 0.4.0

## Cel

Żadne zdarzenie biznesowe (połączenie / SMS) nie może zginąć, gdy telefon chwilowo nie ma Internetu, CRM odpowiada błędem 5xx albo proces aplikacji zostanie ubity.

## Kolejność zapisu

1. Android wykrywa zdarzenie.
2. Aplikacja najpierw zapisuje payload do lokalnej kolejki SQLite.
3. Payload jest szyfrowany AES-256-GCM kluczem przechowywanym w Android Keystore.
4. Aplikacja wykonuje szybką próbę wysłania do CRM.
5. Po sukcesie usuwa wyłącznie dokładnie tę wersję wpisu, którą wysłała.
6. Po błędzie sieci/408/429/5xx WorkManager uruchamia retry po odzyskaniu łączności.
7. Błędy trwałe 4xx są oznaczane jako `blocked` i pozostają w telefonie do ręcznej interwencji / ponownego sparowania.

## Deduplikacja

Klucz lokalny:

- połączenie: `call:<event_uuid>`
- SMS: `sms:<event_uuid>`

Dla połączenia `started` oraz status końcowy używają tego samego klucza. Jeśli oba stany czekają offline, końcowy payload zastępuje początkowy.

Serwer nadal MUSI wykonywać UPSERT po `event_uuid`; lokalna deduplikacja nie zastępuje idempotencji API.

## Ochrona przed race condition

Każdy rekord ma `updated_at_epoch_ms` traktowany jako wersja payloadu. Po odpowiedzi HTTP aplikacja usuwa/oznacza rekord tylko wtedy, gdy wersja w bazie nadal jest taka sama. Jeśli podczas requestu stan połączenia zmienił się np. z `started` na `answered`, nowszy payload pozostaje w kolejce.

## Retry

WorkManager ma constraint `NetworkType.CONNECTED` i exponential backoff. Kolejka uruchamia synchronizację również po starcie procesu aplikacji, jeśli istnieją wpisy `pending`.

## Status w aplikacji

Ekran główny pokazuje:

- `Oczekujące zdarzenia` — kolejka `pending`,
- `Wymagające interwencji` — kolejka `blocked`,
- przycisk `Synchronizuj teraz` — ponawia także zdarzenia wcześniej zablokowane.
