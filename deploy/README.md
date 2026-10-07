# Deploying to an Ubuntu/Debian VM

1. Build (JDK 21): `mvn -DskipTests package`
2. Copy `target/vehicle-management-*.jar` and this `deploy/` folder to the VM.
3. `sudo ./setup.sh vehicle-management-*.jar` — installs JDK 21 and PostgreSQL 15+, creates the
   database, role and secrets, installs the systemd unit and starts it on :8080.
4. Edit `/etc/vehicle-management/env`: set `VM_ADMIN_USERNAME/PASSWORD/MOBILE` (first admin) and
   `VM_CORS_ORIGINS` (exact UI origin), then `sudo systemctl restart vehicle-management`.
5. Put nginx/Caddy with TLS in front and open only 80/443; keep 8080 and 5432 closed. One hostname,
   `https://trucks.rollingradius.com`, serves both: DNS points at the VM, Caddy answers `/api/*`
   itself and proxies everything else to the Netlify-hosted UI (`UI_UPSTREAM`); the proxy must send
   `X-Forwarded-Proto` / `X-Forwarded-Host` (Caddy does by default).

Logs: `journalctl -u vehicle-management -f`. Health: `curl localhost:8080/actuator/health`.
Upgrade: re-run `setup.sh` with the new jar (Liquibase migrates on boot).

Photos: with `VM_IMAGE_BACKEND=local` they live in `/var/lib/vehicle-management/uploads`; the OCR
worker must read the same directory (or use `gcs` with `VM_GCS_BUCKET`). Back this directory up
along with the database.

# Docker alternative

`./start-prod.sh` at the repo root builds the image and runs the app and PostgreSQL 16 with
Docker Compose. It creates `.env` with generated secrets on first run; fill in `VM_ADMIN_*` and
`VM_CORS_ORIGINS`, and optionally `DOMAIN` for automatic HTTPS. `./start-prod.sh logs` / `down`.
