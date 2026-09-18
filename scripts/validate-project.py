from pathlib import Path
import sys
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
errors = []
checks = []

def check(name, ok, detail=""):
    checks.append((name, bool(ok), detail))
    if not ok:
        errors.append(name)

build = (root / "app/build.gradle.kts").read_text(encoding="utf-8")
root_build = (root / "build.gradle.kts").read_text(encoding="utf-8")
wrapper = (root / "gradle/wrapper/gradle-wrapper.properties").read_text(encoding="utf-8")
workflow = (root / ".github/workflows/android-apk.yml").read_text(encoding="utf-8")

check("versionName 0.8.1", 'versionName = "0.8.1"' in build)
check("versionCode 9", "versionCode = 9" in build)
check("compileSdk 37", "compileSdk = 37" in build)
check("targetSdk 37", "targetSdk = 37" in build)
check("minSdk 29", "minSdk = 29" in build)
check("AGP 9.4.0", 'version "9.4.0"' in root_build)
check("Gradle 9.6.0", "gradle-9.6.0-bin.zip" in wrapper)
check("Java target 17", "JavaVersion.VERSION_17" in build)
check("AGP 9 built-in Kotlin", 'org.jetbrains.kotlin.android' not in root_build and 'org.jetbrains.kotlin.android' not in build)
check("KGP 2.3.21 pinned", 'kotlin-gradle-plugin:2.3.21' in root_build)
check("Compose compiler 2.3.21", 'org.jetbrains.kotlin.plugin.compose' in root_build and '2.3.21' in root_build)
check("legacy android.kotlinOptions removed", "kotlinOptions" not in build)
check("debug applicationId suffix", 'applicationIdSuffix = ".debug"' in build)
check("release signing env", "ANDROID_KEYSTORE_PATH" in build and "ANDROID_KEY_ALIAS" in build)
check("GitHub Actions workflow", (root / ".github/workflows/android-apk.yml").is_file())
check("CI setup-android v4", "android-actions/setup-android@v4" in workflow)
check("CI setup-gradle v6", "gradle/actions/setup-gradle@v6" in workflow)
check("CI builds debug", ":app:assembleDebug" in workflow)
check("CI builds release", ":app:assembleRelease" in workflow)
check("CI provisions Gradle 9.6", 'GRADLE_VERSION: "9.6.0"' in workflow)
check("CI provisions Android API 37", 'ANDROID_API: "37"' in workflow)
check("GitHub build docs", (root / "docs/BUILD_APK_GITHUB.md").is_file())
check("release signing docs", (root / "docs/RELEASE_SIGNING_GITHUB.md").is_file())
check("server API contract present", (root / "docs/API_CONTRACT.md").is_file())

try:
    for p in (root / "app/src/main").rglob("*.xml"):
        ET.parse(p)
    check("Android XML parse", True)
except Exception as exc:
    check("Android XML parse", False, str(exc))

for name, ok, detail in checks:
    print(("PASS" if ok else "FAIL"), name, detail)

print(f"\n{sum(ok for _, ok, _ in checks)}/{len(checks)} checks passed")
sys.exit(1 if errors else 0)
