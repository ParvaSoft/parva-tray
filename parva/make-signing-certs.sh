#!/usr/bin/env bash
#
# Genera la CA Parvasoft e i certificati con cui il backend firma le richieste
# di stampa inviate a QZ Tray.
#
#   ./make-signing-certs.sh [ambiente]      # default: prod
#
# La CA viene creata solo la prima volta: rigenerarla invaliderebbe tutte le
# installazioni gia' in campo. Per aggiungere un ambiente rilancia lo script
# con un nome diverso ("pre", "collaudo"), la CA esistente viene riusata.
#
# La passphrase della CA puo' essere passata in QZ_CA_PASS per usi non
# interattivi (CI); altrimenti viene chiesta a video.

set -euo pipefail

ORG="Parvasoft"
ENV_NAME="${1:-prod}"
OUT="$(cd "$(dirname "$0")" && pwd)/certs"
CA_DAYS=7300      # 20 anni
CERT_DAYS=3650    # 10 anni

CN="$ORG"
[[ "$ENV_NAME" != "prod" ]] && CN="$ORG ($ENV_NAME)"

mkdir -p "$OUT"
chmod 700 "$OUT"

pass_args() { # $1 = passin|passout
    [[ -n "${QZ_CA_PASS:-}" ]] && printf -- "-%s pass:%s" "$1" "$QZ_CA_PASS"
}

# ---------------------------------------------------------------- CA (una volta)
if [[ -f "$OUT/ca.crt" ]]; then
    echo "CA gia' presente in $OUT/ca.crt, la riuso."
else
    echo "Creo la CA $ORG (validita' $CA_DAYS giorni)..."
    openssl req -x509 -newkey rsa:4096 -sha512 -days "$CA_DAYS" \
        -keyout "$OUT/ca-key.pem" -out "$OUT/ca.crt" \
        -subj "/O=$ORG/CN=$ORG QZ Root CA" \
        -addext "basicConstraints=critical,CA:TRUE,pathlen:0" \
        -addext "keyUsage=critical,keyCertSign,cRLSign" \
        $(pass_args passout)
    chmod 600 "$OUT/ca-key.pem"
fi

# ------------------------------------------------- certificato di firma (per ambiente)
echo "Creo il certificato di firma \"$CN\"..."

openssl req -newkey rsa:2048 -sha512 -nodes \
    -keyout "$OUT/$ENV_NAME-key.pem" -out "$OUT/$ENV_NAME.csr" \
    -subj "/O=$ORG/CN=$CN"

openssl x509 -req -in "$OUT/$ENV_NAME.csr" -sha512 -days "$CERT_DAYS" \
    -CA "$OUT/ca.crt" -CAkey "$OUT/ca-key.pem" -CAcreateserial \
    -extfile <(printf '%s\n' \
        "basicConstraints=critical,CA:FALSE" \
        "keyUsage=critical,digitalSignature" \
        "subjectKeyIdentifier=hash" \
        "authorityKeyIdentifier=keyid,issuer") \
    -out "$OUT/$ENV_NAME.crt" \
    $(pass_args passin)

rm -f "$OUT/$ENV_NAME.csr"
chmod 600 "$OUT/$ENV_NAME-key.pem"

# ---------------------------------------------------------------------- verifica
openssl verify -CAfile "$OUT/ca.crt" "$OUT/$ENV_NAME.crt" > /dev/null

# La stessa firma/verifica che fara' il backend e poi QZ Tray
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
echo -n '{"call":"print","params":{},"timestamp":0}' > "$TMP/data"
openssl dgst -sha512 -sign "$OUT/$ENV_NAME-key.pem" -out "$TMP/sig" "$TMP/data"
openssl x509 -in "$OUT/$ENV_NAME.crt" -pubkey -noout > "$TMP/pub"
openssl dgst -sha512 -verify "$TMP/pub" -signature "$TMP/sig" "$TMP/data" > /dev/null
echo "Verifica catena e firma: OK"

cat <<EOF

Fatto. In $OUT:

  ca.crt              -> nel pacchetto QZ come "override.crt", accanto al jar.
                         Da distribuire con l'installer, non e' segreto.
  $ENV_NAME.crt$(printf '%*s' $((14 - ${#ENV_NAME})) '')-> lo serve il backend su /qz/cert. Non e' segreto.
  $ENV_NAME-key.pem$(printf '%*s' $((10 - ${#ENV_NAME})) '')-> CHIAVE PRIVATA: solo sul backend di $ENV_NAME,
                         fuori dal war (env QZ_KEY_PATH o secret K8s).
  ca-key.pem          -> CHIAVE DELLA CA: non va su nessun server. Serve solo
                         per emettere altri certificati. Conservala offline.

Impronta del certificato (quella che QZ confronta):
  $(openssl x509 -in "$OUT/$ENV_NAME.crt" -noout -fingerprint -sha1 | cut -d= -f2)
EOF
