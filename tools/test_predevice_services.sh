#!/usr/bin/env bash
set -euo pipefail
repo_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo_dir"
fixture_name="hellfire-predevice-$$"
cleanup() { docker rm -f "$fixture_name" >/dev/null 2>&1 || true; }
trap cleanup EXIT
# Own tmpfs database, random loopback port, no host or personal volumes.
docker run --detach --rm --name "$fixture_name" -p 127.0.0.1::5432 \
    -e POSTGRES_USER=hellfire_fixture -e POSTGRES_PASSWORD=synthetic-test-only \
    -e POSTGRES_DB=hellfire_predevice -e PGDATA=/tmp/hellfire-pg \
    --tmpfs /tmp/hellfire-pg:rw,size=256m postgres:18 >/dev/null
fixture_port=$(docker port "$fixture_name" 5432/tcp)
export TEST_DATABASE_URL="postgresql://hellfire_fixture:synthetic-test-only@${fixture_port}/hellfire_predevice"
for attempt in {1..60}; do
    if docker exec "$fixture_name" pg_isready -U hellfire_fixture -d hellfire_predevice >/dev/null 2>&1; then break; fi
    if [[ $attempt == 60 ]]; then echo 'Fixture database did not become ready' >&2; exit 1; fi
    sleep 1
done
python3 tools/generate_predevice_fixtures.py --check
(cd server && .venv/bin/python -m pytest -q)
(cd server/whatsapp-web && npm test)
(cd web/api && npm test)
(cd web/frontend && npm run typecheck && API_URL=http://127.0.0.1:43911 npm run build)
node tools/test_web_browser.mjs
