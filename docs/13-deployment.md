# 13 — Docker, despliegue y operaciones

## Entornos
Local (`docker compose`), staging conectado a **SRI certificación**, producción conectada solo a **SRI producción**. Keys/secretos/colas/buckets distintos. No promover artefactos con certificados de prueba a producción.

## Servicios
`api` Spring Boot, `worker` Spring Boot, `web` Next.js, `postgres`, `rabbitmq`, `redis`, almacenamiento S3 local (pendiente, ver abajo), reverse proxy TLS. En nube PostgreSQL administrado + S3 real + gestor de secretos/KMS; RabbitMQ administrado o dedicado.

## docker-compose.yml local
El archivo real es `infra/docker-compose.yml` y sus variables están en `.env.example`. Extracto:
```yaml
services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: einvoice
      POSTGRES_USER: app
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:?set}
    volumes: [pgdata:/var/lib/postgresql/data]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U app -d einvoice"]
      interval: 10s
      retries: 5
  rabbitmq:
    image: rabbitmq:4-management
    environment:
      RABBITMQ_DEFAULT_USER: ${RABBITMQ_USER:?set}
      RABBITMQ_DEFAULT_PASS: ${RABBITMQ_PASSWORD:?set}
  redis:
    image: redis:7-alpine
    command: ["redis-server", "--requirepass", "${REDIS_PASSWORD:?set}"]
volumes:
  pgdata: {}
```
Para operación reproducible, cambiar tags por digest y añadir `api/worker/web` una vez construidas imágenes. Ninguna imagen usa `latest`. Los puertos se publican solo en `127.0.0.1` y se pueden cambiar con variables (`POSTGRES_PORT`, etc.).

**Almacenamiento de objetos local, decisión pendiente:** el 2026-10-10 la imagen comunitaria `minio/minio` ya no se podía descargar de Docker Hub ni de Quay, por lo que `minio` no forma parte del compose. Desde el Plan 4 los XML y los certificados cifrados se guardan en PostgreSQL. Antes de producción hay que elegir un servicio compatible con S3 para desarrollo (por ejemplo una imagen de MinIO de otro distribuidor fijada por digest, u otro servidor S3) y añadirlo con su healthcheck. En nube se sigue usando S3 real. No publicar puertos de PostgreSQL, Redis ni RabbitMQ en producción.

## Variables sensibles
`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `SPRING_RABBITMQ_*`, `SPRING_DATA_REDIS_*`, `S3_BUCKET`, `S3_ENDPOINT`, `KMS_KEY_ID`, `SRI_ENABLED_ENVIRONMENTS`, `OIDC_ISSUER`, `SMTP_*` / proveedor transaccional. Secretos por secret manager, nunca commit. `OIDC_ISSUER` (URL del emisor OIDC que firma los tokens de acceso) y `OIDC_AUDIENCE` (valor del claim `aud` que identifica a esta API; admite varios separados por coma) son obligatorias desde el Plan 2: sin ellas la aplicación no arranca. Desde el Plan 4 también es obligatoria `EINVOICE_MASTER_KEY` (32 bytes en Base64; se puede generar con `openssl rand -base64 32`), que protege los certificados de firma guardados: perderla los deja irrecuperables y filtrarla los expone. `EINVOICE_PROVIDER_RUC` es opcional: si se define, cada comprobante lleva el campo adicional `RUC Proveedor` (ficha, Anexo 26). Desde el Plan 7, la aplicación web necesita sus propias variables (`API_BASE_URL`, `APP_BASE_URL`, `OIDC_ISSUER`, `OIDC_CLIENT_ID`, `SESSION_SECRET` de al menos 32 caracteres; ver `frontend/.env.example`): sin ellas responde con error en cuanto se usa. `APP_BASE_URL` debe ser `https` en producción para que las cookies de sesión lleven `Secure`, y la API no debe quedar expuesta directamente al navegador: se llega a ella a través de la aplicación web. Desde el Plan 6, el correo de entrega está apagado salvo `DELIVERY_EMAIL_ENABLED=true`; entonces son obligatorios `DELIVERY_EMAIL_FROM` (la dirección remitente, sin nombre) y un servidor SMTP (`SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD`), o la aplicación no arranca. La conexión exige STARTTLS; `SMTP_STARTTLS=false` lo desactiva y solo debe usarse con un servidor de desarrollo local. El dominio remitente necesita SPF y DKIM configurados fuera de la aplicación. Desde el Plan 5, `SRI_ENABLED_ENVIRONMENTS` decide si se transmite al SRI: vacío no envía nada, `1` solo al ambiente de pruebas y `1,2` también a producción; en producción solo debe incluir `2` después de la certificación (doc 12). `EINVOICE_WORKER_ENABLED=false` deja una instancia solo como API; al menos una instancia debe quedar como worker. La audiencia impide que un token que el mismo proveedor emitió para otra aplicación sirva aquí; en el proveedor hay que registrar esta API como recurso y pedir los tokens para ella.

Desde el Plan 6b: `EINVOICE_WEBHOOKS_ENABLED` (por defecto `false`) enciende los avisos a sistemas de los clientes; debe valer lo mismo en la API y en el worker. El worker necesita entonces salida a internet por `https`, y conviene que esa salida no alcance la red interna (doc 10). `EINVOICE_WEBHOOKS_ALLOW_INSECURE_HTTP` y `EINVOICE_WEBHOOKS_ALLOW_PRIVATE_ADDRESSES` son solo para desarrollo. RabbitMQ gana la cola `webhook.deliver` y su cola de mensajes muertos, que se declaran solas. Desde el Plan 8b: `EINVOICE_INVITATIONS_ALLOW_WITHOUT_EMAIL` (por defecto `false`) solo hace falta si el proveedor de identidad no incluye el correo en el token de acceso; ver el doc 10 antes de activarla. El proveedor debe admitir el ámbito `email` (la web lo pide por defecto; se cambia con `OIDC_SCOPE`). Desde el Plan 8: `EINVOICE_METRICS_TOKEN` (al menos 24 caracteres) abre las métricas de `/actuator/prometheus`; sin ella no existen. `EINVOICE_RATE_LIMIT_REQUESTS` y `EINVOICE_RATE_LIMIT_WRITES` cambian el cupo por usuario y minuto (1200 y 300); `REDIS_TIMEOUT` (500ms) es cuánto se espera a Redis antes de seguir sin él. `SPRING_FLYWAY_USER` y `SPRING_FLYWAY_PASSWORD` permiten migrar con un usuario distinto del que opera (`infra/postgres/roles.sql`). En la web, `API_TIMEOUT_MS` (30000) es cuánto se espera a la API. `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` pone los registros de la API en JSON.

## Imágenes y staging de referencia
El `Dockerfile` de este repositorio y el de [la web](https://github.com/DevNation593/client-factura-facil) construyen `einvoice-api` (API y worker, según `EINVOICE_WORKER_ENABLED`) y `einvoice-web`; ninguna corre como root. `infra/docker-compose.stack.yml` las levanta sobre la infraestructura del compose, con solo la web publicada, e `infra/stack-smoke.sh` comprueba el resultado. Es una referencia, no un despliegue: no hay proxy TLS, ni registro de imágenes, ni nube elegidos, y las imágenes base van por etiqueta, no por digest.

## CI/CD
Objetivo: format + tests + SAST/SCA + SBOM -> build OCI -> firma/scan de imagen -> migración Flyway controlada -> deploy canary -> smoke test -> rollback de aplicación sin revertir arbitrariamente migraciones destructivas. Test de restore previo a grandes cambios.

Desde el 2026-10-13 el sistema vive en dos repositorios públicos de DevNation593, cada uno con su workflow. El de este hace: pruebas de la API, cobertura mínima de los paquetes críticos, SBOM, verificación de los XSD, pruebas con navegador del recorrido completo, construcción de la imagen y revisión de dependencias en cada pull request. El de [la web](https://github.com/DevNation593/client-factura-facil): lint, tipos, pruebas, auditoría de dependencias, SBOM, imagen y la comprobación de que su copia del contrato es la de este repositorio. No firman ni analizan imágenes, no publican y no despliegan.

## SLO, backup, alertas
Objetivo: monitorear p95 API, colas, SRI, DLQ, disco, conexiones, certificaciones, expiración certificados y almacenamiento. Backup diario con PITR PostgreSQL y versionado objetos; RPO/RTO aprobados; restauración ensayada. Job reconciliador de estados SRI y verificador de hashes de objetos.

Hoy existen las métricas de comprobantes, colas, outbox, entregas, certificados, artefactos y llamadas al SRI, con sus alertas de partida (`infra/observability/prometheus-alerts.yml`); los scripts de respaldo y restauración, ensayados en local; el reconciliador y el verificador de huellas. Faltan p95 de la API como objetivo, disco y conexiones, PITR, RPO y RTO acordados y un Prometheus desplegado. Detalle en el [doc 15](15-produccion.md).

## Fuentes oficiales y control de cambios
- [SRI — Facturación electrónica](https://www.sri.gob.ec/facturacion-electronica): ficha técnica esquema off-line v2.34 (julio 2026), XSD y normativa.
- [SRI — registro de proveedores (28-07-2026)](https://www.sri.gob.ec/detalle-noticias?idnoticia=1318&marquesina=1): Resolución NAC-DGERCGC26-00000027.
- [SRI — adhesión y firma](https://www.sri.gob.ec/herramienta-de-comprobantes-electronicos).

> **Límite jurídico/técnico:** estos documentos son una especificación de ingeniería, no sustituyen los XSD oficiales, la ficha técnica integral, las resoluciones publicadas en Registro Oficial ni asesoría tributaria. Antes de pasar a producción, congelar versiones de normativa, XSD y catálogos, y ejecutar la matriz de conformidad con el ambiente de certificación del SRI. El detalle y fechas de entrada en vigor de cada reforma deben corroborarse en su texto normativo publicado.
