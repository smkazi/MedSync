#!/usr/bin/env bash
#
# Generates development TLS material: a local CA, a server certificate for the gateway and the
# web UI, and the PKCS#12 keystore/truststore the JVM services need.
#
# These certificates are for development only. Nothing here is committed - the script is. In any
# real environment the certificates come from your CA or ACME, and the private keys from a secret
# manager.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CERT_DIR="${CERT_DIR:-$ROOT/certs}"
DAYS="${DAYS:-365}"
PASSWORD="${CERT_PASSWORD:-changeit}"

# Every name the certificate must be valid for: localhost for a laptop, the compose service names
# for container-to-container calls.
SANS="DNS:localhost,DNS:gateway,DNS:identity-service,DNS:patient-service,DNS:scheduling-service,DNS:laboratory-service,DNS:notification-service,DNS:admissions-service,DNS:pharmacy-service,DNS:billing-service,DNS:ai-service,DNS:web,IP:127.0.0.1"

mkdir -p "$CERT_DIR"
cd "$CERT_DIR"

if [[ -f ca.crt && "${FORCE:-}" != "1" ]]; then
  echo "Certificates already exist in $CERT_DIR (set FORCE=1 to regenerate)."
  exit 0
fi

echo "==> Local certificate authority"
openssl req -x509 -newkey rsa:4096 -sha256 -days $((DAYS * 3)) -nodes \
  -keyout ca.key -out ca.crt \
  -subj "/C=IN/O=MedSync Development/CN=MedSync Development CA" \
  -addext "basicConstraints=critical,CA:TRUE,pathlen:0" \
  -addext "keyUsage=critical,keyCertSign,cRLSign" 2>/dev/null

echo "==> Server key and certificate"
openssl req -newkey rsa:2048 -sha256 -nodes -keyout server.key -out server.csr \
  -subj "/C=IN/O=MedSync Development/CN=localhost" 2>/dev/null

cat > server.ext <<EXT
basicConstraints=CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth,clientAuth
subjectAltName=$SANS
EXT

openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
  -out server.crt -days "$DAYS" -sha256 -extfile server.ext 2>/dev/null

echo "==> PKCS#12 keystore and truststore for the JVM services"
openssl pkcs12 -export -in server.crt -inkey server.key -certfile ca.crt \
  -name medsync -out keystore.p12 -passout "pass:$PASSWORD"

# A truststore holding only the local CA: a service should trust this CA, not the whole world.
keytool -importcert -noprompt -alias medsync-ca -file ca.crt \
  -keystore truststore.p12 -storetype PKCS12 -storepass "$PASSWORD" >/dev/null 2>&1

rm -f server.csr server.ext ca.srl
chmod 600 ./*.key ./*.p12

cat <<SUMMARY

Wrote development certificates to $CERT_DIR:

  ca.crt          the local CA - trust this in your browser to silence warnings
  server.crt/key  the server certificate, valid for localhost and the compose service names
  keystore.p12    for the JVM services (server.ssl.key-store)
  truststore.p12  the local CA only, for service-to-service calls

Run a service with TLS:

  SPRING_PROFILES_ACTIVE=tls \\
  HMS_TLS_KEYSTORE=$CERT_DIR/keystore.p12 \\
  HMS_TLS_PASSWORD=$PASSWORD \\
  java -jar services/gateway/target/gateway-*.jar

These are development certificates. Do not deploy them.
SUMMARY
