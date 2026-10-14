#!/usr/bin/env bash
# Comprobación rápida de la aplicación levantada con docker-compose.stack.yml: que cada pieza está
# en pie, que no corre como root y que la web sirve con sus cabeceras y alcanza a la API.
#   infra/stack-smoke.sh [dirección de la web, por defecto http://localhost:3000]
set -uo pipefail
web="${1:-http://localhost:3000}"
failed=0
check() {
  if [ "$2" = "$3" ]; then
    echo "bien   $1"
  else
    echo "FALLA  $1: se esperaba '$3' y fue '$2'"
    failed=1
  fi
}
health() { docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$1" 2>/dev/null; }

check "la API está disponible" "$(health einvoice-api-1)" "healthy"
check "el worker está disponible" "$(health einvoice-worker-1)" "healthy"
check "la web está en marcha" "$(health einvoice-web-1)" "running"
for service in api worker web; do
  uid="$(docker exec "einvoice-$service-1" id -u 2>/dev/null)"
  check "$service no corre como root" "$([ "${uid:-0}" != "0" ] && echo sí || echo no)" "sí"
done
check "la base tiene las migraciones aplicadas" \
  "$(docker exec einvoice-postgres-1 psql -U app -d einvoice -At -c 'SELECT count(*) > 0 AND bool_and(success) FROM flyway_schema_history' 2>/dev/null)" "t"
check "la API no publica ningún puerto" "$(docker port einvoice-api-1 2>/dev/null | wc -l | tr -d ' ')" "0"
check "la web responde la pantalla de ingreso" "$(curl -s -o /dev/null -w '%{http_code}' "$web/ingresar")" "200"
policy="$(curl -s -D - -o /dev/null "$web/ingresar" | tr -d '\r' | grep -i '^content-security-policy:')"
check "la pantalla trae política de contenido con nonce" "$(echo "$policy" | grep -c "script-src 'self' 'nonce-")" "1"
check "la política no permite scripts en línea ni eval" "$(echo "$policy" | grep -c -E "unsafe-inline|unsafe-eval")" "0"
check "sin sesión, la web lo dice" "$(curl -s "$web/api/session")" '{"authenticated":false}'
check "sin sesión, el proxy de la API responde 401" "$(curl -s -o /dev/null -w '%{http_code}' "$web/api/backend/tenants")" "401"
check "la web alcanza a la API por la red interna" \
  "$(docker exec einvoice-web-1 wget -q -O - http://api:8080/actuator/health/readiness 2>/dev/null | grep -c '"UP"')" "1"
exit $failed
