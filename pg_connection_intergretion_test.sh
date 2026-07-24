#!/usr/bin/env bash

COMPOSE_FILE="kio-integration-test/pg-cleartext-password.docker-compose.yml"

export POSTGRES_HOST="127.0.0.1"
export POSTGRES_PORT="15432"
export POSTGRES_USER="test_user"
export POSTGRES_PASSWORD="test_password"
export POSTGRES_DB="test_database"

docker compose -f "$COMPOSE_FILE" up -d --wait
./gradlew :kio-integration-test:allTest
docker compose -f "$COMPOSE_FILE" down -v

