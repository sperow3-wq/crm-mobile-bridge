# Changelog

## 0.8.1

- dodano GitHub Actions do automatycznej kompilacji debug APK po pushu do `main`,
- dodano ręczny/tagowy build release APK,
- dodano obsługę podpisu release przez GitHub Secrets,
- debug otrzymał `applicationIdSuffix = ".debug"` i może działać obok release,
- `CRM_BASE_URL` jest konfigurowalny przez właściwość Gradle,
- podniesiono `versionCode` do 9 i `versionName` do 0.8.1,
- workflow używa JDK 17, Android API 37, Gradle 9.6.0 oraz `android-actions/setup-android@v4`,
- dodano skrypty bootstrap/keystore i dokumentację build/signing,
- poprawiono zgodność z AGP 9.4: usunięto `org.jetbrains.kotlin.android`, włączono built-in Kotlin AGP 9 i usunięto stary `android.kotlinOptions`,
- KGP/Compose Compiler jest przypięty do 2.3.21.

## 0.8.0

- dodano `CallWrapUpActivity` otwierany po zakończeniu odebranej rozmowy,
- po rozmowie aplikacja publikuje zwykłe powiadomienie `Uzupełnij rozmowę` z bezpośrednim `PendingIntent`,
- dodano wybór wyniku rozmowy, notatkę do 2000 znaków i dokładny termin ponownego kontaktu,
- podsumowanie jest powiązane z istniejącą rozmową przez `event_uuid`,
- zapis podsumowania korzysta z szyfrowanej kolejki offline (`call_wrap_up:<event_uuid>`),
- dodano szyfrowany `CallWrapUpStore` z retencją 7 dni,
- brak uprawnienia do powiadomień nie powoduje utraty podsumowania; ekran główny pokazuje oczekujące rozmowy,
- mobilna historia klienta pokazuje `result_label`, `wrap_up_note` i `follow_up_at`,
- dodano endpoint `POST /api/mobile/call/wrap-up`,
- rozszerzono `mobile_call_events` o pola podsumowania rozmowy i indeks terminu follow-up,
- dodano migrację `005_upgrade_0_7_to_0_8.sql`,
- odłączenie urządzenia czyści również lokalne oczekujące podsumowania.


## 0.7.0

- dodano mobilną kartę klienta `ClientHistoryActivity`,
- nagłówek karty pokazuje klienta, telefon, usługę, etap i opiekuna,
- karta pokazuje aktualny status faktur po terminie, liczbę zaległych faktur i kwotę,
- dodano listę ostatnich rozmów i SMS-ów z kierunkiem, statusem, czasem rozmowy/treścią i pracownikiem,
- historia jest pobierana jednym endpointem `POST /api/mobile/client/overview`,
- historia SMS nie jest zapisywana w lokalnym cache telefonu,
- przy braku Internetu karta korzysta wyłącznie z zaszyfrowanego nagłówka Caller ID i wyraźnie oznacza tryb offline,
- Caller ID otrzymał przycisk `Historia klienta`,
- wejście do pełnej historii z zablokowanego ekranu wymaga odblokowania urządzenia,
- ekran główny pokazuje ostatnio rozpoznanego klienta i pozwala wrócić do jego historii,
- dodano bezpieczny przycisk `Otwórz pełną kartę w CRM`; aplikacja akceptuje tylko URL z hosta skonfigurowanego jako `CRM_BASE_URL`,
- widok SQL `v_mobile_client_activity` rozszerzono o `employee_name`,
- dodano migrację `004_upgrade_0_6_to_0_7.sql`.

## 0.6.0

- dodano trwałe powiązanie pracownika z konkretną służbową kartą SIM (`subscriptionId`, slot, operator),
- automatyczne parowanie sprawdza wszystkie aktywne numery SIM i wybiera ten, który występuje na karcie pracownika w CRM,
- synchronizacja SMS jest filtrowana po systemowym `sub_id`; wiadomości z prywatnej karty są ignorowane,
- dodano `ServiceSimValidator` — przy zmianie/wyjęciu SIM synchronizacja SMS jest blokowana,
- dodano synchronizację SMS przychodzących z `Telephony.Sms.Inbox`,
- dodano synchronizację SMS wychodzących z `Telephony.Sms.Sent`, także gdy pracownik używa zwykłej aplikacji Wiadomości,
- dodano stabilny `event_uuid` generowany z `device_uuid + kierunek + provider_message_id`, co zapobiega duplikatom,
- zapis SMS trafia do istniejącej szyfrowanej kolejki offline przed próbą wysłania do CRM,
- po pierwszym sparowaniu ustawiany jest punkt startowy na bieżącym ostatnim SMS-ie — brak automatycznego importu starej historii,
- dodano obserwator systemowej bazy SMS oraz fallback okresowy co 15 minut,
- dodano ręczne „Synchronizuj SMS teraz”,
- API SMS rozszerzono o `service_subscription_id`, `provider_message_id`, `status` i `source`,
- dodano migrację SQL `003_upgrade_0_5_to_0_6.sql`,
- dodano widok `v_mobile_client_activity` do prezentacji rozmów i SMS na osi klienta.

## 0.5.0

- lokalny szyfrowany cache Caller ID,
- identyfikacja klienta offline,
- automatyczna synchronizacja cache co 6 godzin,
- status „Dane offline” na ekranie połączenia.
