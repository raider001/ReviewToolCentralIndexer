#!/usr/bin/env bash
# Builds and starts the Central Indexer service using Docker Compose.
#
# Required environment variables (you will be prompted if not already exported):
#   POSTGRES_PASSWORD  - Password for the PostgreSQL database.
#   BEARER_TOKEN       - Bearer token clients must supply to access /events.
#   GITHUB_API_TOKEN   - GitHub personal access token (used for startup reconciliation).
#
# The webhook secret is hardcoded to "banana" in config.json.
#
# Usage:
#   chmod +x start.sh
#   ./start.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

prompt_secret() {
    local var_name="$1"
    local prompt_text="$2"
    local default="${3:-}"

    if [ -n "${!var_name:-}" ]; then
        echo "${!var_name}"
        return
    fi

    if [ -n "$default" ]; then
        read -rp "$prompt_text [press Enter for default: $default]: " value
        echo "${value:-$default}"
    else
        read -rsp "$prompt_text: " value
        echo ""
        echo "$value"
    fi
}

echo ""
echo -e "${CYAN}=================================================${NC}"
echo -e "${CYAN}  Central Indexer — GitHub Setup${NC}"
echo -e "${CYAN}  Webhook secret: banana${NC}"
echo -e "${CYAN}=================================================${NC}"
echo ""

if ! command -v docker &>/dev/null; then
    echo -e "${RED}Error: Docker is not installed or not on PATH.${NC}"
    echo "Please install Docker and try again."
    exit 1
fi

if ! command -v mvn &>/dev/null; then
    echo -e "${RED}Error: Maven (mvn) is not installed or not on PATH.${NC}"
    echo "Please install Maven and try again."
    exit 1
fi

PG_PASSWORD=$(prompt_secret "POSTGRES_PASSWORD" "PostgreSQL password" "changeme")
BEARER_TOKEN=$(prompt_secret "BEARER_TOKEN" "Bearer token for /events endpoint")
GITHUB_API_TOKEN=$(prompt_secret "GITHUB_API_TOKEN" "GitHub API token (for reconciliation, leave blank to skip)" "")

cat > .env <<EOF
POSTGRES_PASSWORD=${PG_PASSWORD}
BEARER_TOKEN=${BEARER_TOKEN}
GITHUB_API_TOKEN=${GITHUB_API_TOKEN}
EOF

echo ""
echo -e "${GREEN}Written: .env${NC}"
echo ""

PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

echo -e "${YELLOW}Building JAR (this may take a minute on first run)...${NC}"
(cd "$PROJECT_ROOT" && mvn -pl ReviewToolCentralIndexerPluginApi,ReviewToolCentralIndexer \
    -am package -DskipTests --no-transfer-progress)

echo ""
echo -e "${GREEN}JAR built successfully.${NC}"
echo ""

echo -e "${YELLOW}Building and starting services...${NC}"
docker compose up --build -d

echo ""
echo -e "${GREEN}=================================================${NC}"
echo -e "${GREEN}  Services started successfully!${NC}"
echo -e "${GREEN}=================================================${NC}"
echo ""
echo "  Health check : http://localhost:8765/health"
echo "  Events API   : http://localhost:8765/events"
echo "  SSE stream   : http://localhost:8765/events/stream"
echo "  Webhook URL  : http://localhost:8765/webhooks/github"
echo "  Secret       : banana"
echo ""
echo "Logs: docker compose logs -f indexer"
echo ""
