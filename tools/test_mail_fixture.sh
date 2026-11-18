#!/usr/bin/env bash
set -euo pipefail
repo_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
fixture_dir=$(mktemp -d)
fixture_name="hellfire-mail-fixture-$$"
cleanup() { docker rm -f "$fixture_name" >/dev/null 2>&1 || true; rm -rf -- "$fixture_dir"; }
trap cleanup EXIT
keytool -genkeypair -alias fixture -keyalg RSA -keysize 2048 -validity 2 \
    -dname CN=localhost -ext SAN=dns:localhost -storetype PKCS12 \
    -keystore "$fixture_dir/server.p12" -storepass synthetic-fixture-only -keypass synthetic-fixture-only >/dev/null 2>&1
keytool -exportcert -rfc -alias fixture -keystore "$fixture_dir/server.p12" \
    -storepass synthetic-fixture-only -file "$fixture_dir/server.pem" >/dev/null 2>&1
chmod 644 "$fixture_dir/server.p12"
docker run --detach --rm --name "$fixture_name" --memory 256m \
    -p 127.0.0.1:993:3993 -p 127.0.0.1:465:3465 \
    --mount "type=bind,src=$fixture_dir/server.p12,dst=/home/greenmail/greenmail.p12,readonly" \
    -e JAVA_OPTS=-Xmx128m \
    -e 'GREENMAIL_OPTS=-Dgreenmail.setup.test.imaps -Dgreenmail.setup.test.smtps -Dgreenmail.hostname=0.0.0.0 -Dgreenmail.users=fixture:synthetic-pass@localhost -Dgreenmail.users.login=email -Dgreenmail.tls.keystore.file=/home/greenmail/greenmail.p12 -Dgreenmail.tls.keystore.password=synthetic-fixture-only' \
    greenmail/standalone:2.1.3 >/dev/null
python3 - "$fixture_dir/server.pem" <<'PY'
import socket,ssl,sys,time
context=ssl.create_default_context(cafile=sys.argv[1])
for attempt in range(60):
    try:
        with socket.create_connection(('localhost',993),timeout=2) as tcp:
            with context.wrap_socket(tcp,server_hostname='localhost') as tls:
                assert b'OK' in tls.recv(4096)
                break
    except (OSError,AssertionError):
        if attempt==59:raise
        time.sleep(.5)
PY
export HELLFIRE_MAIL_FIXTURE_TRUSTSTORE="$fixture_dir/server.p12"
export ANDROID_HOME="${ANDROID_HOME:-/home/dev/android-sdk}"
cd "$repo_dir/android"
./gradlew :app:testDebugUnitTest --rerun \
    --offline --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8'
