#!/bin/bash
# Runs once on first start of the Postgres container: one database per service.
set -e
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE DATABASE payout;
    GRANT ALL PRIVILEGES ON DATABASE payout TO $POSTGRES_USER;
EOSQL
