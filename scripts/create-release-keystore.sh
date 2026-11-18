#!/usr/bin/env bash
# Crée la clé de signature release FOODSAFE+ et le fichier keystore.properties (tous deux NON versionnés).
# À exécuter UNE fois, sur un poste de confiance. Sauvegarder la clé hors ligne :
# perdre cette clé = impossible de publier une mise à jour installable par-dessus l'APK existante.
set -euo pipefail
cd "$(dirname "$0")/.."
STORE=${1:-foodsafe-release.jks}
ALIAS=${ALIAS:-foodsafe}
if [ -f "$STORE" ]; then echo "$STORE existe déjà, abandon." >&2; exit 1; fi
read -r -s -p "Mot de passe du keystore (min. 12 caractères) : " PASS; echo
[ ${#PASS} -ge 12 ] || { echo "Mot de passe trop court" >&2; exit 1; }
keytool -genkeypair -v -keystore "$STORE" -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass "$PASS" -keypass "$PASS" -dname "${DNAME:-CN=FOODSAFE+, O=FOODSAFE+, C=FR}"
cat > keystore.properties <<PROPS
storeFile=$STORE
storePassword=$PASS
keyAlias=$ALIAS
keyPassword=$PASS
PROPS
chmod 600 keystore.properties "$STORE"
echo "OK : $STORE et keystore.properties créés (ignorés par git)."
