#!/usr/bin/env bash
# Respaldo de la base: un volcado de PostgreSQL en formato propio (pg_dump -Fc) y su huella SHA-256.
#
#   infra/backup/backup.sh <directorio de destino>
#
# La conexión se toma de las variables de PostgreSQL: PGHOST, PGPORT, PGUSER, PGPASSWORD, PGDATABASE.
# Si no hay pg_dump en esta máquina, BACKUP_CONTAINER=<contenedor de postgres> lo ejecuta dentro de
# ese contenedor (en local: einvoice-postgres-1).
#
# El volcado contiene los certificados cifrados y todos los comprobantes: guárdelo cifrado y con
# acceso restringido. Sin la clave maestra (EINVOICE_MASTER_KEY) los certificados no se recuperan:
# respáldela aparte, nunca junto al volcado.
set -euo pipefail
# El volcado solo lo lee quien lo hace.
umask 077

target="${1:?uso: backup.sh <directorio de destino>}"
: "${PGUSER:?defina PGUSER}" "${PGDATABASE:?defina PGDATABASE}"
mkdir -p "$target"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
file="$target/einvoice-$stamp.dump"

if [ -n "${BACKUP_CONTAINER:-}" ]; then
  # La contraseña pasa por el entorno, no por la línea de órdenes (que cualquiera ve con ps).
  export PGPASSWORD="${PGPASSWORD:-}"
  docker exec -e PGPASSWORD "$BACKUP_CONTAINER" \
    pg_dump -U "$PGUSER" -Fc "$PGDATABASE" > "$file"
else
  pg_dump -Fc -f "$file" "$PGDATABASE"
fi

[ -s "$file" ] || { echo "el volcado quedó vacío" >&2; rm -f "$file"; exit 1; }
(cd "$target" && sha256sum "$(basename "$file")" > "$(basename "$file").sha256")
echo "$file"
