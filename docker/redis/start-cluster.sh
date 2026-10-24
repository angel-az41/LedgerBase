#!/bin/sh

# ============================================================
# LEDGERBASE — LOCAL REDIS CLUSTER STARTUP
# ============================================================
#
# Start six Redis Cluster nodes in one container.
#
# DEVELOPMENT TOPOLOGY:
#
# - Redis ports: 7000 through 7005
# - Cluster bus ports: 17000 through 17005
# - Persistent node identities in /data
# - Temporary runtime configurations in /run
#
# Cluster roles and hash slots are assigned separately
# using redis-cli --cluster create.
#
# No application data or PostgreSQL files are modified.

set -eu

# ============================================================
# VALIDATE REDIS CREDENTIALS
# ============================================================

: "${REDIS_PASSWORD:?REDIS_PASSWORD must be configured}"

# Require a hexadecimal password so generated Redis
# configuration values remain unambiguous.
#
# Generate a suitable password with:
#
# openssl rand -hex 24

case "$REDIS_PASSWORD" in
    *[!0123456789abcdefABCDEF]*)
        echo "REDIS_PASSWORD must be hexadecimal." >&2
        exit 1
        ;;
esac

# ============================================================
# PROTECT GENERATED CONFIGURATION FILES
# ============================================================

umask 077

mkdir -p /run/ledgerbase-redis

# ============================================================
# GRACEFUL SHUTDOWN
# ============================================================

shutdown_nodes() {

    echo "Stopping local Redis Cluster nodes..."

    for port in 7000 7001 7002 7003 7004 7005; do

        redis-cli -p "$port" shutdown nosave \
            >/dev/null 2>&1 || true

    done

    exit 0
}

trap shutdown_nodes TERM INT

# ============================================================
# START SIX REDIS INSTANCES
# ============================================================

for port in 7000 7001 7002 7003 7004 7005; do

    # --------------------------------------------------------
    # INDIVIDUAL NODE DIRECTORIES
    # --------------------------------------------------------

    node_dir="/data/$port"

    config="/run/ledgerbase-redis/$port.conf"

    mkdir -p "$node_dir"

    # --------------------------------------------------------
    # GENERATE REDIS NODE CONFIGURATION
    # --------------------------------------------------------

    # PostgreSQL remains the source of truth.
    #
    # Redis cache values do not require disk persistence.
    # Redis Cluster identity metadata remains in /data.
    #
    # Password authentication is enabled.
    # Client ports are published to host loopback only.
    #
    # Cluster bus traffic stays within this container.

    cat > "$config" <<CONF
port $port
bind 0.0.0.0
protected-mode no

requirepass $REDIS_PASSWORD
masterauth $REDIS_PASSWORD

cluster-enabled yes
cluster-config-file $node_dir/nodes.conf
cluster-node-timeout 5000

cluster-announce-ip 127.0.0.1
cluster-announce-port $port
cluster-announce-bus-port $((port + 10000))

dir $node_dir
pidfile $node_dir/redis.pid
logfile $node_dir/redis.log

daemonize yes

appendonly no
save ""

maxmemory 64mb
maxmemory-policy allkeys-lru
CONF

    # --------------------------------------------------------
    # START THIS REDIS NODE
    # --------------------------------------------------------

    redis-server "$config"

done

echo "Six local Redis Cluster nodes started."
echo "Initialize the cluster slots using redis-cli."

# ============================================================
# MONITOR NODE HEALTH
# ============================================================

# Keep the container running while all six nodes respond.
#
# Docker can restart the container if a node becomes
# unavailable. This is basic process monitoring, not
# independent node-level high availability.

while :; do

    for port in 7000 7001 7002 7003 7004 7005; do

        if ! redis-cli -p "$port" ping | grep -qx PONG; then

            echo "Redis node $port failed its PING check" >&2

            exit 1
        fi

    done

    sleep 5

done
