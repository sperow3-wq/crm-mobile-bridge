# CRM Mobile Bridge 0.8.1

Wewnętrzna aplikacja Android łącząca służbowy telefon pracownika z CRM Kancelarii.

## Nowości 0.8.1 — automatyczny build APK

Ta wersja jest przygotowana do kompilacji w GitHub Actions bez lokalnego Android Studio.

- workflow `.github/workflows/android-apk.yml` buduje debug APK po pushu do `main`,
- ręczny workflow lub tag `v*` może zbudować release APK,
- release jest automatycznie podpisywany po dodaniu sekretów keystore,
- debug ma osobny `applicationId` (`pl.usundlug.crmbridge.debug`), więc może być zainstalowany obok wersji produkcyjnej,
- build używa JDK 17, Gradle 9.6.0, Android API 37 i AGP 9.4.0,
- projekt został przeniesiony na wbudowany Kotlin AGP 9 i nie stosuje już niekompatybilnej wtyczki `org.jetbrains.kotlin.android`,
- `CRM_BASE_URL` można nadpisać właściwością Gradle `-PCRM_BASE_URL=https://...`; domyślnie jest `https://crm.usundlug.pl`.

Instrukcje:

- `docs/BUILD_APK_GITHUB.md` — pierwszy build debug APK,
- `docs/RELEASE_SIGNING_GITHUB.md` — podpisywanie wersji release.

> Repozytorium nie musi zawierać binarnego `gradle-wrapper.jar`. Workflow ma Gradle 9.6.0 i w razie braku pliku generuje wrapper przed kompilacją.

## Funkcje 0.8.0 — podsumowanie rozmowy


Po zakończeniu **odebranej rozmowy** aplikacja:

1. ustala rzeczywisty status i czas rozmowy z systemowego Call Log,
2. aktualizuje istniejące zdarzenie CRM po tym samym `event_uuid`,
3. zapisuje szyfrowany lokalny kontekst rozmowy,
4. pokazuje powiadomienie **Uzupełnij rozmowę**,
5. otwiera ekran podsumowania po dotknięciu powiadomienia.

Ekran podsumowania pozwala:

- wybrać wynik rozmowy,
- wpisać notatkę do 2000 znaków,
- ustawić dokładną datę i godzinę ponownego kontaktu,
- przejść do historii klienta,
- zapisać podsumowanie także bez Internetu — przez istniejącą szyfrowaną kolejkę offline.

W historii klienta rozmowa pokazuje później wynik, notatkę i termin ponownego kontaktu.

### Dlaczego powiadomienie zamiast automatycznego wyskakującego ekranu?

Nowe Androidy ograniczają uruchamianie aktywności z tła. Bridge używa bezpośredniego `PendingIntent` z normalnego powiadomienia. Nie używa inwazyjnego full-screen intent.

Jeżeli pracownik odmówi uprawnienia do powiadomień, rozmowa nadal nie ginie — na ekranie głównym aplikacji pojawia się sekcja **Rozmowy do uzupełnienia**.

## Funkcje odziedziczone z 0.7.0

- identyfikacja pracownika po numerze służbowym i konkretnej karcie SIM,
- Caller ID z klientem, usługą, etapem, opiekunem i statusem zaległych faktur,
- offline Caller ID z szyfrowanym cache,
- rejestracja połączeń z trwałym `event_uuid`,
- SMS przychodzące i wychodzące tylko ze służbowej SIM,
- privacy-first dual-SIM,
- szyfrowana kolejka offline i retry,
- mobilna karta klienta z rozmowami i SMS-ami.

## Instalacja bazy CRM

- nowa instalacja modułu: `server/001_mobile_bridge.sql`,
- aktualizacja 0.7.x → 0.8.0: `server/005_upgrade_0_7_to_0_8.sql`,
- starsze instalacje aktualizuj kolejno odpowiednimi plikami `002`, `003`, `004`, `005`.

Endpointy opisuje `docs/API_CONTRACT.md`. Logikę podsumowania rozmowy opisuje `docs/POST_CALL_WRAP_UP.md`.

## Budowanie i instalacja

Najprostsza ścieżka testowa to GitHub Actions — patrz `docs/BUILD_APK_GITHUB.md`.

Lokalnie można również otworzyć projekt w Android Studio z JDK 17 i Android SDK API 37. Jeśli `gradle-wrapper.jar` nie jest obecny, uruchom najpierw `scripts/bootstrap-wrapper.sh` w środowisku z Gradle 9.6.0.

Po instalacji nadaj wymagane uprawnienia systemowe i sparuj służbową kartę SIM z pracownikiem w CRM. Projekt jest przeznaczony do wewnętrznego/managed deployment; uprawnienia Call Log i SMS są restrykcyjne i wymagają odpowiedniej polityki wdrożenia.
