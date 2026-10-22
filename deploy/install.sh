#!/bin/sh
set -eu
if [ "$(id -u)" -ne 0 ]; then echo "Run as root" >&2; exit 1; fi
if [ "$#" -ne 1 ] || [ ! -f "$1" ]; then echo "Usage: install.sh path/to/*-service.jar" >&2; exit 1; fi
base=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
getent group neo4j-rbac >/dev/null 2>&1 || groupadd --system neo4j-rbac
id neo4j-rbac >/dev/null 2>&1 || useradd --system --gid neo4j-rbac --no-create-home --shell /usr/sbin/nologin neo4j-rbac
install -d -m 0755 -o root -g root /opt/neo4j-rbac
install -d -m 0750 -o root -g neo4j-rbac /etc/neo4j-rbac
install -m 0644 -o root -g root "$1" /opt/neo4j-rbac/service.jar
if [ ! -f /etc/neo4j-rbac/service.env ]; then
    install -m 0640 -o root -g neo4j-rbac "$base/service.env.example" /etc/neo4j-rbac/service.env
fi
if [ ! -f /etc/neo4j-rbac/permissions.json ]; then
    printf '%s\n' '{"users":{}}' > /etc/neo4j-rbac/permissions.json
    chown root:neo4j-rbac /etc/neo4j-rbac/permissions.json
    chmod 0640 /etc/neo4j-rbac/permissions.json
fi
if [ ! -f /etc/neo4j-rbac/jwt.key ]; then
    umask 0077
    openssl rand -base64 32 > /etc/neo4j-rbac/jwt.key
    chown root:neo4j-rbac /etc/neo4j-rbac/jwt.key
    chmod 0640 /etc/neo4j-rbac/jwt.key
fi
install -m 0644 "$base/neo4j-rbac.service" /etc/systemd/system/neo4j-rbac.service
systemctl daemon-reload
echo "Installed. Configure /etc/neo4j-rbac/service.env and permissions.json, then:"
echo "systemctl enable --now neo4j-rbac"
