#!/usr/bin/env bash

set -Eeuo pipefail

COMPOSE_FILE="kio-integration-test/pg-integration.docker-compose.yml"

export POSTGRES_HOST="127.0.0.1"
export POSTGRES_USER="test_user"
export POSTGRES_PASSWORD="test_password"
export POSTGRES_DB="test_database"

cleanup() {
    docker compose -f "$COMPOSE_FILE" down -v
}

trap cleanup EXIT

run_pg_tests() {
    local auth_type="$1"
    local port="$2"

    echo
    echo "========================================"
    echo "PostgreSQL integration test"
    echo "auth=$auth_type port=$port"
    echo "========================================"

    export POSTGRES_PORT="$port"

    ./gradlew \
        :kio-integration-test:macosArm64Test \
        --rerun-tasks
}

docker compose \
    -f "$COMPOSE_FILE" \
    up -d --build --wait

run_pg_tests "trust" "15430"
run_pg_tests "password" "15431"
run_pg_tests "scram-sha-256" "15432"
run_pg_tests "md5" "15433"
run_pg_tests "password+tls" "15434"
run_pg_tests "scram+tls" "15435"