#!/usr/bin/env bash
set -Eeuo pipefail

# ============================================================
# LEDGERBASE — POSTGRESQL READ REPLICA STARTUP
# ============================================================
#
# Initialize an empty replica using a physical base backup
# of the existing PostgreSQL primary.
#
# On subsequent starts, reuse the replica's own data volume.
#
# Never initialize a new independent PostgreSQL database
# and never overwrite an existing database directory.

PGDATA="${PGDATA:-/var/lib/postgresql/data}"
export PGDATA

# ============================================================
# PREPARE SECURE REPLICATION AUTHENTICATION
# ============================================================

# Copy the Docker secret to a PostgreSQL password file.
# PostgreSQL requires this file to have restrictive
# permissions before it will use the credentials.

install -o postgres -g postgres -m 0600 \
  /run/secrets/replica_pgpass \
  /var/lib/postgresql/.pgpass

export PGPASSFILE=/var/lib/postgresql/.pgpass

# ============================================================
# PREPARE THE REPLICA DATA DIRECTORY
# ============================================================

mkdir -p "$PGDATA"
chown postgres:postgres "$PGDATA"
chmod 0700 "$PGDATA"

# ============================================================
# INITIALIZE ONLY WHEN NO DATABASE EXISTS
# ============================================================

if [[ ! -f "$PGDATA/PG_VERSION" ]]; then

  # Refuse to overwrite partial or unexpected data.
  if [[ -n "$(find "$PGDATA" -mindepth 1 -maxdepth 1 -print -quit)" ]]; then
    echo "Replica data directory is not empty." >&2
    echo "Refusing automatic initialization." >&2
    exit 1
  fi

  echo "Initializing LedgerBase PostgreSQL replica..."

  # Copy the primary database cluster.
  #
  # -R creates standby.signal and primary_conninfo.
  # -X stream includes required WAL.
  # -C creates a permanent physical replication slot.
  # -S uses that slot for this replica.
  #
  # Run as the postgres operating-system user.

  gosu postgres pg_basebackup \
    -h postgres \
    -p 5432 \
    -U ledgerbase_replicator \
    -D "$PGDATA" \
    -Fp \
    -X stream \
    -R \
    -P \
    -w \
    -C \
    -S ledgerbase_replica_slot

fi

# ============================================================
# ENSURE WE ARE STARTING A STANDBY
# ============================================================

if [[ ! -f "$PGDATA/standby.signal" ]]; then
  echo "Missing standby.signal; refusing to start." >&2
  exit 1
fi

# ============================================================
# START POSTGRESQL
# ============================================================

# The official entrypoint starts PostgreSQL using the
# existing replicated database directory.
#
# hot_standby enables read-only queries during recovery.

exec docker-entrypoint.sh postgres -c hot_standby=on
