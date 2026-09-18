#!/usr/bin/env bash
set -euo pipefail
OUT="${1:-crm-mobile-bridge-release.jks}"
ALIAS="${2:-crm-mobile-bridge}"

if ! command -v keytool >/dev/null 2>&1; then
  echo "Brak keytool. Zainstaluj JDK 17 lub nowsze." >&2
  exit 1
fi

if [[ -e "$OUT" ]]; then
  echo "Plik już istnieje: $OUT" >&2
  exit 1
fi

keytool -genkeypair -v \
  -keystore "$OUT" \
  -alias "$ALIAS" \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000

echo "Utworzono: $OUT"
echo "Zachowaj plik i hasła w bezpiecznym miejscu. Nie commituj keystore do GitHub."
