#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 1 || ${1:-} == --help || ${1:-} == -h ]]; then
  printf 'Usage: %s USER@HOST\nOptional: SENSOR_ID, API_PORT (8080), SSH_PORT (22), IMAGE_TAG\n' "$0"
  [[ ${1:-} == --help || ${1:-} == -h ]] && exit 0
  exit 1
fi

target=$1
export SENSOR_ID=${SENSOR_ID:-}
export API_PORT=${API_PORT:-8080}
export IMAGE_TAG=${IMAGE_TAG:-deploy-$(date -u +%Y%m%dT%H%M%SZ)-$$}
ssh_port=${SSH_PORT:-22}
[[ $target =~ ^[a-zA-Z0-9_][a-zA-Z0-9_.-]*@[a-zA-Z0-9][a-zA-Z0-9.-]*$ ]] ||
  { echo 'Expected USER@HOST.' >&2; exit 1; }
[[ -z $SENSOR_ID || $SENSOR_ID =~ ^28-[0-9a-fA-F]{12}$ ]] ||
  { echo 'Invalid SENSOR_ID.' >&2; exit 1; }
[[ $IMAGE_TAG =~ ^[a-zA-Z0-9_][a-zA-Z0-9_.-]{0,127}$ ]] ||
  { echo 'Invalid IMAGE_TAG.' >&2; exit 1; }
for port in "$API_PORT" "$ssh_port"; do
  [[ $port =~ ^[0-9]{1,5}$ ]] && (( 10#$port >= 1 && 10#$port <= 65535 )) ||
    { echo 'Ports must be between 1 and 65535.' >&2; exit 1; }
done
export API_PORT=$((10#$API_PORT))
ssh_port=$((10#$ssh_port))

project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
image="$(docker compose -f "$project_dir/compose.yaml" config --images)"
# A private, short socket path also works with macOS Unix socket length limits.
ssh_dir="$(mktemp -d /tmp/gpio-deploy-ssh.XXXXXX)"
ssh_options=(-p "$ssh_port" -o ConnectTimeout=10
  -o ControlMaster=auto -o ControlPersist=yes -o "ControlPath=$ssh_dir/control"
  -o ServerAliveInterval=15 -o ServerAliveCountMax=3)
cleanup() {
  ssh "${ssh_options[@]}" -O exit "$target" >/dev/null 2>&1 || true
  rm -f "$ssh_dir/control"
  rmdir "$ssh_dir" || true
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

echo "Connecting to $target..."
ssh "${ssh_options[@]}" -MNf "$target"
# Reuse the authenticated connection; fail instead of prompting again if it drops.
ssh_options+=(-o BatchMode=yes)

echo 'Building ARM64 image...'
docker buildx build --platform linux/arm64 --load --tag "$image" "$project_dir"

echo "Transferring image and Compose configuration to $target..."
docker image save "$image" | gzip | ssh "${ssh_options[@]}" "$target" 'gzip -dc | docker image load'
# Resolve the image tag, port and sensor locally; the Pi needs no source or .env file.
docker compose -f "$project_dir/compose.yaml" config |
  ssh "${ssh_options[@]}" "$target" 'mkdir -p "$HOME/gpio-temp-raspi-api" && cat > "$HOME/gpio-temp-raspi-api/compose.yaml"'

echo 'Starting the Compose service...'
ssh "${ssh_options[@]}" "$target" \
  'cd "$HOME/gpio-temp-raspi-api" && docker compose up -d --no-build --pull never'

printf 'Deployed %s. Check after a few seconds: curl http://%s:%s/api/temperature\n' "$image" "${target#*@}" "$API_PORT"
