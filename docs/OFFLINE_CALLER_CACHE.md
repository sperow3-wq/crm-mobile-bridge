# Offline Caller ID cache — v0.5.0

## Cel

Telefon ma rozpoznać wcześniej zsynchronizowanego klienta także wtedy, gdy w chwili połączenia nie ma dostępu do Internetu lub CRM chwilowo nie odpowiada.

## Przepływ

1. Połączenie przychodzi na telefon.
2. Aplikacja próbuje `POST /api/mobile/client/identify`.
3. Jeżeli serwer odpowie — dane serwera są używane i odświeżają cache.
4. Jeżeli wystąpi błąd sieci/timeout — aplikacja wyszukuje numer w lokalnym cache.
5. Jeżeli znajdzie rekord — pokazuje klienta, produkt, etap, opiekuna i ostatni status faktur.
6. Ekran otrzymuje oznaczenie `Dane offline` oraz czas ostatniej aktualizacji.

## Ochrona danych

- indeks numeru: HMAC-SHA256 z numeru E.164,
- payload: AES-256-GCM / Android Keystore,
- brak dokumentów, PESEL, adresów, notatek i treści sprawy,
- retencja maks. 14 dni bez odświeżenia,
- usunięcie całego cache przy `Odłącz urządzenie`.

## Synchronizacja

- natychmiast po sparowaniu,
- cyklicznie co 6 godzin przez WorkManager,
- ręcznie z ekranu aplikacji,
- przyrostowo od `lastSuccessfulSyncEpochMs`,
- do 500 rekordów na stronę,
- dopiero ostatnia strona aktualizuje watermark całej synchronizacji.
