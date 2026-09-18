# Budowanie APK przez GitHub Actions — CRM Mobile Bridge 0.8.1

Projekt zawiera workflow `.github/workflows/android-apk.yml`.

## Najprostszy pierwszy build (debug)

1. Utwórz prywatne repozytorium GitHub, np. `crm-mobile-bridge`.
2. Wgraj do niego **zawartość katalogu projektu**, tak aby w głównym katalogu repo były m.in. `app/`, `build.gradle.kts`, `settings.gradle.kts` i `.github/`.
3. Ustaw główną gałąź jako `main` i wykonaj push.
4. GitHub -> **Actions** -> **Android APK** -> wybierz zakończony przebieg.
5. Na dole przebiegu, w **Artifacts**, pobierz `CRM-Mobile-Bridge-0.8.1-debug`.
6. W ZIP artefaktu znajduje się `CRM-Mobile-Bridge-0.8.1-debug.apk` oraz jego suma SHA-256.

Workflow sam:
- ustawia JDK 17,
- instaluje Android SDK API 37,
- ustawia Gradle 9.6.0,
- generuje kompletny Gradle Wrapper,
- buduje `:app:assembleDebug`,
- publikuje APK jako artefakt GitHub Actions.

## Ręczne uruchomienie

GitHub -> Actions -> Android APK -> **Run workflow**.

Opcja `Zbuduj również release APK` może zostać włączona ręcznie. Bez sekretów podpisu powstanie `release-unsigned.apk`.

## Adres CRM

Domyślnie build korzysta z:

`https://crm.usundlug.pl`

Można go nadpisać w Gradle przez:

`-PCRM_BASE_URL=https://adres-crm.example`

## Instalacja debug APK

Najprościej skopiować APK na telefon i zezwolić Androidowi na instalację aplikacji z danego źródła.

Przez ADB:

```bash
adb install -r CRM-Mobile-Bridge-0.8.1-debug.apk
```

Debug ma `applicationId = pl.usundlug.crmbridge.debug`, więc może być zainstalowany równolegle z wersją release `pl.usundlug.crmbridge`.
