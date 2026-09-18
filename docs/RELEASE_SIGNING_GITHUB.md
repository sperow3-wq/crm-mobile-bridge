# Podpisany release APK

Do normalnej dystrybucji wewnętrznej należy używać zawsze tego samego klucza podpisu Android.
Utrata klucza/hasła utrudni aktualizację już zainstalowanej aplikacji.

## 1. Utworzenie keystore

Na komputerze z JDK:

```bash
keytool -genkeypair -v \
  -keystore crm-mobile-bridge-release.jks \
  -alias crm-mobile-bridge \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000
```

Nie dodawaj pliku `.jks` do repozytorium.

## 2. Zakodowanie keystore do GitHub Secret

Linux/macOS:

```bash
base64 -w 0 crm-mobile-bridge-release.jks
```

Windows PowerShell:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("crm-mobile-bridge-release.jks")) | Set-Clipboard
```

## 3. GitHub Secrets

Repozytorium -> Settings -> Secrets and variables -> Actions -> New repository secret.

Dodaj:

- `ANDROID_KEYSTORE_BASE64` — wynik Base64 całego pliku `.jks`,
- `ANDROID_KEYSTORE_PASSWORD` — hasło keystore,
- `ANDROID_KEY_ALIAS` — np. `crm-mobile-bridge`,
- `ANDROID_KEY_PASSWORD` — hasło klucza.

## 4. Build podpisany

Możesz:
- utworzyć tag zaczynający się od `v`, np. `v0.8.1`, albo
- Actions -> Android APK -> Run workflow -> zaznaczyć `Zbuduj również release APK`.

Jeżeli wszystkie 4 sekrety są obecne, artefakt będzie nazywał się:

`CRM-Mobile-Bridge-0.8.1-release-signed.apk`

Workflow nie zapisuje pliku keystore w repozytorium; jest tworzony tylko na runnerze GitHub w katalogu tymczasowym.
