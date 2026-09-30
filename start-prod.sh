#!/usr/bin/env bash
# One command to build and run everything: ./start-prod.sh   (add "down" to stop, "logs" to tail)
set -euo pipefail
cd "$(dirname "$0")"

command -v docker >/dev/null || { echo "Docker is not installed: curl -fsSL https://get.docker.com | sudo sh"; exit 1; }
# Works with the v2 plugin ("docker compose") or the older standalone v1 ("docker-compose").
if docker compose version >/dev/null 2>&1; then DC="docker compose"
elif command -v docker-compose >/dev/null 2>&1; then DC="docker-compose"
else echo "No Compose found. Install one: sudo apt-get install -y docker-compose"; exit 1; fi
SUDO=""; docker info >/dev/null 2>&1 || SUDO="sudo"; DC="$SUDO $DC"

case "${1:-up}" in
  down) $DC --profile tls down; exit 0 ;;
  logs) $DC logs -f --tail=100 app; exit 0 ;;
  up) ;;
  *) echo "usage: $0 [up|down|logs]"; exit 1 ;;
esac

rand() { openssl rand -base64 "$1" 2>/dev/null | tr -d '\n=+/' || head -c "$1" /dev/urandom | base64 | tr -d '\n=+/'; }

if [ ! -f .env ]; then
  cp .env.example .env
  sed -i "s|^DB_PASSWORD=.*|DB_PASSWORD=$(rand 24)|; s|^VM_JWT_SECRET=.*|VM_JWT_SECRET=$(rand 64)|" .env
  chmod 600 .env
  echo ">> Created .env with generated secrets."
fi
set -a; . ./.env; set +a

if [ -z "${VM_ADMIN_USERNAME:-}" ] || [ -z "${VM_ADMIN_PASSWORD:-}" ] || [ -z "${VM_ADMIN_MOBILE:-}" ]; then
  echo "!! VM_ADMIN_USERNAME / _PASSWORD / _MOBILE are not all set in .env -- nobody will be able to sign in."
  echo "   Edit .env and re-run, or continue and add them later."
  read -r -p "Continue anyway? [y/N] " a; [ "${a:-n}" = y ] || exit 1
fi

PROFILE=()
[ -n "${DOMAIN:-}" ] && PROFILE=(--profile tls)

$DC "${PROFILE[@]}" up -d --build

echo ">> Waiting for the app to become healthy..."
for _ in $(seq 1 60); do
  cid="$($DC ps -q app 2>/dev/null || true)"
  s="$([ -n "$cid" ] && $SUDO docker inspect -f '{{.State.Health.Status}}' "$cid" 2>/dev/null || true)"
  [ "$s" = healthy ] && break
  sleep 5
done
$DC ps
if [ "${s:-}" = healthy ]; then
  echo ">> Up: ${DOMAIN:+https://$DOMAIN}${DOMAIN:-http://<this-vm>:${APP_PORT:-8080}}  (Swagger: /swagger-ui.html)"
else
  echo "!! App not healthy yet. Check: ./start-prod.sh logs"; exit 1
fi
