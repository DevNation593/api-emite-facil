#!/bin/sh
# Imprime una parte de una URL de conexión (esquema://usuario:contraseña@host:puerto/ruta?parámetros), con los
# %XX ya decodificados. Lo usan los contenedores de desarrollo para crear sus credenciales a partir de las mismas
# DATABASE_URL, RABBITMQ_URL y REDIS_URL que lee la API, sin repetirlas en otras variables.
#   connection-url.sh user|password|path URL
# Solo POSIX sh y awk: corre igual en las imágenes de PostgreSQL y RabbitMQ (Debian, Ubuntu) y de Redis (Alpine).
set -eu
part="$1"
rest="${2#*://}"
case "$rest" in
  *@*) userinfo="${rest%@*}"; hostpart="${rest##*@}" ;;
  *) userinfo=""; hostpart="$rest" ;;
esac
case "$part" in
  user) value="${userinfo%%:*}" ;;
  password) case "$userinfo" in *:*) value="${userinfo#*:}" ;; *) value="" ;; esac ;;
  path) case "$hostpart" in */*) value="${hostpart#*/}"; value="${value%%\?*}" ;; *) value="" ;; esac ;;
  *) echo "connection-url.sh: parte desconocida: $part" >&2; exit 2 ;;
esac
printf '%s' "$value" | LC_ALL=C awk '
  BEGIN { hex = "0123456789abcdef" }
  {
    out = ""
    for (i = 1; i <= length($0); i++) {
      c = substr($0, i, 1)
      if (c == "%" && i + 2 <= length($0)) {
        h = tolower(substr($0, i + 1, 2))
        out = out sprintf("%c", (index(hex, substr(h, 1, 1)) - 1) * 16 + index(hex, substr(h, 2, 1)) - 1)
        i += 2
      } else {
        out = out c
      }
    }
    printf "%s", out
  }'
