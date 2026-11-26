#!/usr/bin/env bash
# Creates Varde's release signing key and stores it as GitHub Actions secrets.
# Run once, on your own computer. Needs: keytool (any JDK), openssl, and the GitHub CLI (gh) signed in
# with access to the repository. See docs/RELEASING.md.
set -euo pipefail

REPO="${1:-vardirhq/varde}"
OUT="${2:-$HOME/varde-release-key}"
ALIAS="varde"

for tool in keytool openssl gh base64; do
  command -v "$tool" >/dev/null || { echo "Missing '$tool'. Install it and run this again."; exit 1; }
done
gh auth status >/dev/null 2>&1 || { echo "Sign in to the GitHub CLI first: gh auth login"; exit 1; }

if [ -e "$OUT/varde-release.keystore" ]; then
  echo "A key already exists at $OUT/varde-release.keystore. Not replacing it: a new key can't update installed releases."
  echo "To store the existing key's secrets again, set them by hand (see docs/RELEASING.md)."
  exit 1
fi

mkdir -p "$OUT"
chmod 700 "$OUT"
STORE_PASSWORD="$(openssl rand -base64 32 | tr -d '/+=' | cut -c1-32)"
# PKCS12 keystores use one password for the store and the key.
KEY_PASSWORD="$STORE_PASSWORD"

keytool -genkeypair -keystore "$OUT/varde-release.keystore" -storetype PKCS12 -alias "$ALIAS" \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Varde" \
  -storepass "$STORE_PASSWORD" -keypass "$KEY_PASSWORD" >/dev/null 2>&1
chmod 600 "$OUT/varde-release.keystore"

# Passwords are written next to the key so the backup is complete. Keep both somewhere safe, like a password manager.
printf 'alias=%s\nstore_password=%s\nkey_password=%s\n' "$ALIAS" "$STORE_PASSWORD" "$KEY_PASSWORD" > "$OUT/passwords.txt"
chmod 600 "$OUT/passwords.txt"

# gh encrypts each value with the repository's public key before sending it.
base64 < "$OUT/varde-release.keystore" | tr -d '\n' | gh secret set VARDE_RELEASE_KEYSTORE_B64 --repo "$REPO"
printf '%s' "$STORE_PASSWORD" | gh secret set VARDE_RELEASE_STORE_PASSWORD --repo "$REPO"
printf '%s' "$ALIAS" | gh secret set VARDE_RELEASE_KEY_ALIAS --repo "$REPO"
printf '%s' "$KEY_PASSWORD" | gh secret set VARDE_RELEASE_KEY_PASSWORD --repo "$REPO"

echo
echo "Done. Release signing secrets are set on $REPO."
echo "Key and passwords: $OUT"
echo "Back up that folder now (password manager or encrypted backup). If it's lost, no future release can update an installed Varde."
keytool -list -v -keystore "$OUT/varde-release.keystore" -storepass "$STORE_PASSWORD" 2>/dev/null | grep -E "SHA256:" | head -1 | sed 's/^\s*/Certificate fingerprint: /'
