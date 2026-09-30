#!/usr/bin/env bash
# Provision Ubuntu/Debian for the vehicle-management backend.
#   mvn -DskipTests package        (on a build machine, JDK 21)
#   scp target/vehicle-management-*.jar deploy/* user@vm:~
#   sudo ./setup.sh vehicle-management-0.0.1-SNAPSHOT.jar
# Safe to re-run: an existing env file, database and role are left alone; the jar is replaced.
set -euo pipefail
JAR="${1:?usage: sudo ./setup.sh path/to/vehicle-management.jar}"
[ "$(id -u)" = 0 ] || { echo "run as root (sudo)"; exit 1; }
HERE="$(cd "$(dirname "$0")" && pwd)"
ENV=/etc/vehicle-management/env

apt-get update
apt-get install -y openjdk-21-jre-headless postgresql openssl
systemctl enable --now postgresql

PGV="$(psql --version | grep -oE '[0-9]+' | head -1)"
[ "$PGV" -ge 15 ] || { echo "PostgreSQL $PGV found; the schema needs 15+ (NULLS NOT DISTINCT). Install 15+ from apt.postgresql.org."; exit 1; }

id vmgmt >/dev/null 2>&1 || useradd --system --home /var/lib/vehicle-management --shell /usr/sbin/nologin vmgmt
install -d -o vmgmt -g vmgmt /var/lib/vehicle-management /var/lib/vehicle-management/uploads /opt/vehicle-management
install -d -m 750 -o root -g vmgmt /etc/vehicle-management

if [ ! -f "$ENV" ]; then
  DBPASS="$(openssl rand -hex 24)"
  sudo -u postgres psql -v ON_ERROR_STOP=1 <<SQL
DO \$\$ BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'vmgmt') THEN
    CREATE ROLE vmgmt LOGIN PASSWORD '$DBPASS';
  END IF;
END \$\$;
SQL
  sudo -u postgres psql -tc "SELECT 1 FROM pg_database WHERE datname='vehicle_management'" | grep -q 1 \
    || sudo -u postgres createdb -O vmgmt vehicle_management
  cat > "$ENV" <<EOT
VM_DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/vehicle_management
VM_DATABASE_USER=vmgmt
VM_DATABASE_PASSWORD=$DBPASS
VM_JWT_SECRET=$(openssl rand -base64 48 | tr -d '\n')
VM_ADMIN_USERNAME=
VM_ADMIN_PASSWORD=
VM_ADMIN_MOBILE=
VM_CORS_ORIGINS=http://localhost:5173
VM_IMAGE_BACKEND=local
VM_IMAGE_DIR=/var/lib/vehicle-management/uploads
VM_SEED_SAMPLE=false
EOT
  chown root:vmgmt "$ENV"; chmod 640 "$ENV"
  echo ">> Created $ENV. Set VM_ADMIN_* and VM_CORS_ORIGINS in it, then: systemctl restart vehicle-management"
fi

install -o vmgmt -g vmgmt -m 640 "$JAR" /opt/vehicle-management/app.jar
install -m 644 "$HERE/vehicle-management.service" /etc/systemd/system/vehicle-management.service
systemctl daemon-reload
systemctl enable vehicle-management
systemctl restart vehicle-management
sleep 15
curl -fsS http://127.0.0.1:8080/actuator/health && echo || journalctl -u vehicle-management -n 40 --no-pager
