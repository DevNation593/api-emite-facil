#!/usr/bin/env bash
# Restaura un volcado hecho con backup.sh en una base VACÍA y comprueba el resultado.
#
#   infra/backup/restore.sh <archivo .dump> <base de destino>
#
# Conexión por PGHOST, PGPORT, PGUSER, PGPASSWORD; con BACKUP_CONTAINER=<contenedor> se ejecuta
# dentro de ese contenedor. La base de destino debe existir y no tener tablas: este script nunca
# escribe sobre una base con datos. Tras restaurar, la aplicación necesita la misma clave maestra
# (EINVOICE_MASTER_KEY) con la que se cifraron los certificados.
#
# Con los dos usuarios de producción (infra/postgres/roles.sql) el orden es:
#   1. crear la base vacía;
#   2. ejecutar roles.sql sobre ella, como superusuario;
#   3. restaurar con PGUSER=einvoice_migrator.
# Quien restaura queda como dueño de las tablas: si se restaura como superusuario, el usuario que
# migra no podrá aplicar migraciones y la aplicación no arrancará.
#
# La restauración es todo o nada: si falla (por ejemplo, porque el volcado nombra un rol que este
# servidor no tiene), la base queda vacía y se puede repetir tras corregir la causa.
set -euo pipefail

file="${1:?uso: restore.sh <archivo .dump> <base de destino>}"
database="${2:?uso: restore.sh <archivo .dump> <base de destino>}"
: "${PGUSER:?defina PGUSER}"
[ -f "$file" ] || { echo "no existe $file" >&2; exit 1; }
[ -f "$file.sha256" ] || { echo "falta la huella $file.sha256: no se restaura un volcado sin comprobar" >&2; exit 1; }
(cd "$(dirname "$file")" && sha256sum -c "$(basename "$file").sha256" >/dev/null) \
  || { echo "la huella no coincide: el volcado está dañado o fue alterado" >&2; exit 1; }

# La contraseña pasa por el entorno, no por la línea de órdenes (que cualquiera ve con ps).
export PGPASSWORD="${PGPASSWORD:-}"
run() {
  if [ -n "${BACKUP_CONTAINER:-}" ]; then
    docker exec -i -e PGPASSWORD "$BACKUP_CONTAINER" "$@"
  else
    "$@"
  fi
}
query() { run psql -U "$PGUSER" -d "$database" -v ON_ERROR_STOP=1 -At -c "$1"; }

tables="$(query "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'")"
[ "$tables" = "0" ] || { echo "la base $database ya tiene $tables tablas: restaure solo en una base vacía" >&2; exit 1; }

run pg_restore -U "$PGUSER" --no-owner --single-transaction -d "$database" < "$file"

mismatched="$(query "SELECT count(*) FROM document_artifacts WHERE encode(sha256(content), 'hex') <> sha256")"
triggers="$(query "SELECT count(*) FROM pg_trigger WHERE tgrelid = 'document_artifacts'::regclass AND NOT tgisinternal")"
echo "comprobantes: $(query 'SELECT count(*) FROM fiscal_documents')"
echo "artefactos: $(query 'SELECT count(*) FROM document_artifacts')"
echo "artefactos cuya huella no coincide: $mismatched"
echo "disparadores de inmutabilidad: $triggers"
echo "migraciones aplicadas: $(query 'SELECT count(*) FROM flyway_schema_history WHERE success')"

# Lo restaurado queda ahí para examinarlo, pero no se da por bueno.
[ "$mismatched" = "0" ] || { echo "hay artefactos cuyo contenido no corresponde a su huella: el respaldo se tomó de una base ya alterada" >&2; exit 2; }
[ "$triggers" != "0" ] || { echo "faltan los disparadores de inmutabilidad: el volcado no es de esta aplicación o está incompleto" >&2; exit 2; }
