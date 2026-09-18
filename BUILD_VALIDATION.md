# Build validation — CRM Mobile Bridge 0.8.1

Walidacja statyczna projektu została wykonana przed spakowaniem.

```text
PASS versionName 0.8.1 
PASS versionCode 9 
PASS compileSdk 37 
PASS targetSdk 37 
PASS minSdk 29 
PASS AGP 9.4.0 
PASS Gradle 9.6.0 
PASS Java target 17 
PASS AGP 9 built-in Kotlin 
PASS KGP 2.3.21 pinned 
PASS Compose compiler 2.3.21 
PASS legacy android.kotlinOptions removed 
PASS debug applicationId suffix 
PASS release signing env 
PASS GitHub Actions workflow 
PASS CI setup-android v4 
PASS CI setup-gradle v6 
PASS CI builds debug 
PASS CI builds release 
PASS CI provisions Gradle 9.6 
PASS CI provisions Android API 37 
PASS GitHub build docs 
PASS release signing docs 
PASS server API contract present 
PASS Android XML parse 

25/25 checks passed
```

Dodatkowo:

- GitHub Actions YAML: poprawnie parsowany,
- skrypty Bash: poprawna składnia,
- pliki XML Androida: poprawnie parsowane,
- brak klucza prywatnego / keystore w paczce,
- release signing odbywa się wyłącznie przez zmienne środowiskowe/GitHub Secrets.

## Ograniczenie lokalnego środowiska

W lokalnym środowisku roboczym nie ma kompletnego Android SDK ani Gradle 9.6, dlatego fizyczny `assembleDebug` nie został tutaj uruchomiony. Workflow GitHub Actions instaluje wymagane SDK/JDK/Gradle i wykonuje rzeczywisty build APK na runnerze GitHub.
