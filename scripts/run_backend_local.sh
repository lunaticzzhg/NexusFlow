#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$ROOT_DIR/.env"
LOG_DIR="$ROOT_DIR/logs"
LOG_FILE="$LOG_DIR/backend-local.log"
BACKEND_PORT=8080
POSTGRES_WAIT_SECONDS=60
PROXY_MODE="auto"

usage() {
  cat <<'EOF'
Usage: scripts/run_backend_local.sh [--no-proxy] [--help]

Starts the local PostgreSQL dependency, waits until it is healthy, then runs
the NexusFlow backend in the foreground. Backend output is streamed to this
terminal and saved to logs/backend-local.log.

Prerequisites:
  - Docker Desktop is running.
  - .env exists at the repository root and contains the local backend config.

Stop the backend with Ctrl-C. PostgreSQL remains running for subsequent runs.
If a NexusFlow backend is already listening on port 8080, it is stopped before
the new backend starts. Other processes using port 8080 are left untouched.

By default, an unauthenticated HTTPS_PROXY or https_proxy value is translated
to JVM proxy settings for outbound Google verification requests. Use --no-proxy
to force the launcher not to add those settings.
EOF
}

case "${1:-}" in
  "")
    ;;
  -h|--help)
    usage
    exit 0
    ;;
  --no-proxy)
    PROXY_MODE="off"
    ;;
  *)
    echo "Unknown option: $1" >&2
    usage >&2
    exit 2
    ;;
esac

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing $ENV_FILE. Create it from .env.example and set the required local secrets." >&2
  exit 1
fi

if ! command -v docker >/dev/null 2>&1; then
  echo "docker not found. Install and start Docker Desktop first." >&2
  exit 1
fi

if ! docker info >/dev/null 2>&1; then
  echo "Docker is not ready. Start Docker Desktop and try again." >&2
  exit 1
fi

if ! command -v lsof >/dev/null 2>&1; then
  echo "lsof not found. It is required to safely restart a backend on port $BACKEND_PORT." >&2
  exit 1
fi

cd "$ROOT_DIR"

restart_existing_backend() {
  local listener_pid
  local listener_command

  listener_pid="$(lsof -tiTCP:"$BACKEND_PORT" -sTCP:LISTEN | head -n 1 || true)"
  if [[ -z "$listener_pid" ]]; then
    return
  fi

  listener_command="$(ps -p "$listener_pid" -o command= 2>/dev/null || true)"
  if [[ "$listener_command" != *"com.nexusflow.backend.ApplicationKt"* ]]; then
    echo "Port $BACKEND_PORT is used by a process that is not a NexusFlow backend (PID $listener_pid). Refusing to stop it." >&2
    exit 1
  fi

  echo "Stopping existing NexusFlow backend (PID $listener_pid)..."
  kill -TERM "$listener_pid"

  for ((elapsed = 0; elapsed < 10; elapsed++)); do
    if ! lsof -nP -iTCP:"$BACKEND_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
      return
    fi
    sleep 1
  done

  echo "Existing backend did not stop within 10s; forcing it to stop..."
  kill -KILL "$listener_pid"
  for ((elapsed = 0; elapsed < 5; elapsed++)); do
    if ! lsof -nP -iTCP:"$BACKEND_PORT" -sTCP:LISTEN >/dev/null 2>&1; then
      return
    fi
    sleep 1
  done

  echo "Port $BACKEND_PORT is still occupied after stopping the previous backend." >&2
  exit 1
}

configure_outbound_proxy() {
  local proxy_url
  local proxy_host
  local proxy_port

  if [[ "$PROXY_MODE" == "off" ]]; then
    echo "Local outbound proxy is disabled."
    return
  fi

  proxy_url="${HTTPS_PROXY:-${https_proxy:-}}"
  if [[ -z "$proxy_url" ]]; then
    echo "No local HTTPS proxy configured; backend will connect directly."
    return
  fi

  if [[ "$proxy_url" == *"@"* ]]; then
    echo "The local HTTPS proxy includes credentials. Refusing to place credentials in JVM options." >&2
    echo "Use an unauthenticated local proxy endpoint or start the backend with managed proxy credentials." >&2
    exit 1
  fi

  if [[ ! "$proxy_url" =~ ^https?://([^/:]+):([0-9]+)(/.*)?$ ]]; then
    echo "Unsupported HTTPS proxy format. Use http://host:port or https://host:port." >&2
    exit 1
  fi

  proxy_host="${BASH_REMATCH[1]}"
  proxy_port="${BASH_REMATCH[2]}"
  export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Dhttp.proxyHost=$proxy_host -Dhttp.proxyPort=$proxy_port -Dhttps.proxyHost=$proxy_host -Dhttps.proxyPort=$proxy_port -Dhttp.nonProxyHosts=localhost|127.*|[::1]"
  echo "Using the local HTTPS proxy for backend outbound requests."
}

restart_existing_backend
configure_outbound_proxy

echo "Starting local PostgreSQL..."
docker compose up -d postgres

POSTGRES_CONTAINER_ID="$(docker compose ps -q postgres)"
if [[ -z "$POSTGRES_CONTAINER_ID" ]]; then
  echo "PostgreSQL container was not created." >&2
  exit 1
fi

for ((elapsed = 0; elapsed < POSTGRES_WAIT_SECONDS; elapsed++)); do
  health_status="$(docker inspect --format '{{.State.Health.Status}}' "$POSTGRES_CONTAINER_ID" 2>/dev/null || true)"
  if [[ "$health_status" == "healthy" ]]; then
    break
  fi
  if [[ "$health_status" == "unhealthy" ]]; then
    echo "PostgreSQL became unhealthy. Inspect it with: docker compose logs postgres" >&2
    exit 1
  fi
  sleep 1
done

if [[ "${health_status:-}" != "healthy" ]]; then
  echo "PostgreSQL did not become healthy within ${POSTGRES_WAIT_SECONDS}s. Inspect it with: docker compose logs postgres" >&2
  exit 1
fi

mkdir -p "$LOG_DIR"
set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

echo "Starting backend. Live log: $LOG_FILE"
./gradlew --console=plain :backend:run 2>&1 | tee "$LOG_FILE"
