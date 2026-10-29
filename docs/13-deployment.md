# 13 — Docker, despliegue y operaciones

## Entornos
Local (`docker compose`), staging conectado a **SRI certificación**, producción conectada solo a **SRI producción**. Keys/secretos/colas/buckets distintos. No promover artefactos con certificados de prueba a producción.

## Servicios
`api` Spring Boot, `worker` Spring Boot, `web` Next.js, `postgres`, `rabbitmq`, `redis`, almacenamiento S3 local (pendiente, ver abajo), reverse proxy TLS. En nube PostgreSQL administrado + S3 real + gestor de secretos/KMS; RabbitMQ administrado o dedicado.

## docker-compose.yml local
El archivo real es `infra/docker-compose.yml`: PostgreSQL 16, RabbitMQ 4 con su consola, Redis 7. Sus credenciales salen de las mismas `DATABASE_URL`, `RABBITMQ_URL` y `REDIS_URL` del `.env` que lee la API (ver `.env.example`): cada contenedor arranca con `infra/connection-url.sh`, que saca de su URL el usuario, la contraseña y la base, así que no se repiten en otras variables. Los puertos son fijos (5432, 5672 y 6379) y las URL deben usarlos; la consola de RabbitMQ está en el 15672 o en `RABBITMQ_MANAGEMENT_PORT`. Todos se publican solo en `127.0.0.1`. La pila completa (`infra/docker-compose.stack.yml`) usa esas mismas URL y cambia `localhost` por el nombre de cada servicio. Para operación reproducible, cambiar tags por digest. Ninguna imagen usa `latest`.

**Almacenamiento de objetos local, decisión pendiente:** el 2026-10-10 la imagen comunitaria `minio/minio` ya no se podía descargar de Docker Hub ni de Quay, por lo que `minio` no forma parte del compose. Desde el Plan 4 los XML y los certificados cifrados se guardan en PostgreSQL. Antes de producción hay que elegir un servicio compatible con S3 para desarrollo (por ejemplo una imagen de MinIO de otro distribuidor fijada por digest, u otro servidor S3) y añadirlo con su healthcheck. En nube se sigue usando S3 real. No publicar puertos de PostgreSQL, Redis ni RabbitMQ en producción.

## Variables sensibles
`DATABASE_URL`, `RABBITMQ_URL`, `REDIS_URL`, `S3_BUCKET`, `S3_ENDPOINT`, `KMS_KEY_ID`, `SRI_ENABLED_ENVIRONMENTS`, `EINVOICE_JWT_SECRET`, `EINVOICE_WEB_URL`, `SMTP_*` / proveedor transaccional. Secretos por secret manager, nunca commit. La base de datos, RabbitMQ y Redis se configuran con una variable cada una: `DATABASE_URL` (`postgresql://usuario:contraseña@host:5432/einvoice`, con parámetros como `?sslmode=require` si hacen falta), `RABBITMQ_URL` (`amqp://usuario:contraseña@host:5672`, o `amqps://` con TLS; un vhost distinto de `/` va al final, codificado) y `REDIS_URL` (`redis://:contraseña@host:6379`, o `rediss://` con TLS); los caracteres especiales del usuario o la contraseña se escriben codificados (`%40` para la arroba). Una `DATABASE_URL` o una `RABBITMQ_URL` mal formada detiene el arranque. Si la base no es solo de esta aplicación, `?currentSchema=einvoice` al final de `DATABASE_URL` la lleva a su propio esquema: Flyway lo crea y migra ese, y no toca `public`. Es lo que hace falta en **Supabase**, que deja en `public` una función propia (`rls_auto_enable`, por la que Flyway lo da por ocupado) y publica ese esquema en su API; allí, además, se usa el pooler en modo sesión (puerto 5432), no el de modo transacción (6543), que rompe los bloqueos de Flyway y las sentencias preparadas. Desde el Plan 10 la API lleva las cuentas y sus tokens: `EINVOICE_JWT_SECRET` (al menos 32 bytes aleatorios, `openssl rand -base64 48`) firma los tokens de acceso y `EINVOICE_WEB_URL` es la dirección de la web, a la que llevan los enlaces de los correos de la cuenta; sin ellas la aplicación no arranca. Esos correos salen por el SMTP de la entrega (`SPRING_MAIL_*`) con el remitente `DELIVERY_EMAIL_FROM`, aunque la entrega de comprobantes esté apagada. Desde el Plan 4 también es obligatoria `EINVOICE_MASTER_KEY` (32 bytes en Base64; se puede generar con `openssl rand -base64 32`), que protege los certificados de firma guardados: perderla los deja irrecuperables y filtrarla los expone. `EINVOICE_PROVIDER_RUC` es opcional: si se define, cada comprobante lleva el campo adicional `RUC Proveedor` (ficha, Anexo 26). Desde el Plan 7, la aplicación web necesita sus propias variables (`API_BASE_URL`, `APP_BASE_URL`, `SESSION_SECRET` de al menos 32 caracteres; ver el `.env.example` de la web): sin ellas responde con error en cuanto se usa. `APP_BASE_URL` debe ser `https` en producción para que las cookies de sesión lleven `Secure`, y el navegador no llama a la API: la web lo hace por él. La web llama a la API por su dirección interna (`API_BASE_URL`), no a través del proxy de entrada: pone ella misma la IP del navegador en `X-Forwarded-For`, y si pasara por un proxy que reemplaza esa cabecera todos los ingresos compartirían un mismo cupo. El puerto interno de la API solo debe alcanzarlo la web y el proxy de entrada. Los sistemas externos (con API key) sí llaman a la API directamente, en su propia dirección pública detrás del proxy de entrada con TLS; en esa dirección el proxy debe reemplazar `X-Forwarded-For` o añadir su entrada al final (la API toma la IP contando desde la derecha, `EINVOICE_TRUSTED_PROXY_HOPS`, 1 por defecto), y conviene que publique solo `/api/v1/**` sin `/api/v1/auth/**`, que los sistemas externos no necesitan. Desde el Plan 6, el correo de entrega está apagado salvo `DELIVERY_EMAIL_ENABLED=true`; entonces son obligatorios `DELIVERY_EMAIL_FROM` (la dirección remitente, sin nombre) y un servidor SMTP (`SPRING_MAIL_HOST`, `SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD`), o la aplicación no arranca. La conexión exige STARTTLS; `SMTP_STARTTLS=false` lo desactiva y solo debe usarse con un servidor de desarrollo local. El dominio remitente necesita SPF y DKIM configurados fuera de la aplicación. Desde el Plan 5, `SRI_ENABLED_ENVIRONMENTS` decide si se transmite al SRI: vacío no envía nada, `1` solo al ambiente de pruebas y `1,2` también a producción; en producción solo debe incluir `2` después de la certificación (doc 12). `EINVOICE_WORKER_ENABLED=false` deja una instancia solo como API; al menos una instancia debe quedar como worker.

Desde el Plan 6b: `EINVOICE_WEBHOOKS_ENABLED` (por defecto `false`) enciende los avisos a sistemas de los clientes; debe valer lo mismo en la API y en el worker. El worker necesita entonces salida a internet por `https`, y conviene que esa salida no alcance la red interna (doc 10). `EINVOICE_WEBHOOKS_ALLOW_INSECURE_HTTP` y `EINVOICE_WEBHOOKS_ALLOW_PRIVATE_ADDRESSES` son solo para desarrollo. RabbitMQ gana la cola `webhook.deliver` y su cola de mensajes muertos, que se declaran solas. Desde el Plan 8: `EINVOICE_METRICS_TOKEN` (al menos 24 caracteres) abre las métricas de `/actuator/prometheus`; sin ella no existen. `EINVOICE_RATE_LIMIT_REQUESTS` y `EINVOICE_RATE_LIMIT_WRITES` cambian el cupo por usuario y minuto (1200 y 300); `REDIS_TIMEOUT` (500ms) es cuánto se espera a Redis antes de seguir sin él. `DB_POOL_SIZE` (5) y `DB_POOL_MIN_IDLE` (1) son las conexiones a PostgreSQL de cada instancia, como máximo y en reposo; `EINVOICE_WORKER_CONSUMERS` (1) y `EINVOICE_WORKER_MAX_CONSUMERS` (4), los consumidores del worker por cola; `EINVOICE_WORKER_POLL_INTERVAL_MS` (2000), cada cuánto el worker publica el outbox y encola lo vencido. `SPRING_FLYWAY_USER` y `SPRING_FLYWAY_PASSWORD` permiten migrar con un usuario distinto del que opera (`infra/postgres/roles.sql`). En la web, `API_TIMEOUT_MS` (30000) es cuánto se espera a la API. `LOGGING_STRUCTURED_FORMAT_CONSOLE` pone los registros de la API en JSON (`logstash` o `ecs`); la imagen trae `logstash`, y definirla vacía vuelve al texto plano. La API escucha en `PORT` (8080 si no se define; `SERVER_PORT`, si existe, gana).

## Imágenes y staging de referencia
El `Dockerfile` de este repositorio y el de [la web](https://github.com/DevNation593/client-factura-facil) construyen `einvoice-api` (API y worker, según `EINVOICE_WORKER_ENABLED`) y `einvoice-web`; ninguna corre como root. `infra/docker-compose.stack.yml` las levanta sobre la infraestructura del compose, con solo la web publicada, e `infra/stack-smoke.sh` comprueba el resultado. Con solo la web publicada, un sistema externo no llega a la API: para usar API keys hay que publicar también la API detrás del proxy de entrada (solo `/api/v1/**`, sin `/api/v1/auth/**`), mientras la web sigue llamándola por la red interna. Es una referencia, no un despliegue: no hay proxy TLS, y las imágenes base van por etiqueta, no por digest. La nube elegida es Google Cloud Run (siguiente sección).

## Google Cloud Run
Tres servicios de Cloud Run con dos imágenes: `einvoice-api` y `einvoice-worker` con la imagen de este repositorio (`EINVOICE_WORKER_ENABLED` `false` y `true`), y `einvoice-web` con la de la web. PostgreSQL (Supabase), RabbitMQ (CloudAMQP) y Redis (Redis Cloud) siguen fuera de Google y se alcanzan por sus URL.

**Lo que las imágenes ya resuelven.** Las dos:
- escuchan en el puerto que Cloud Run da en `PORT`;
- corren con un usuario sin privilegios (10001 en la API, `node` en la web);
- se construyen en dos etapas: la final solo lleva el JRE o Node y la aplicación;
- no guardan nada en disco: lo que escriben va a `/tmp`, que en Cloud Run vive en memoria y cuenta contra el límite de memoria;
- cierran de forma ordenada con SIGTERM: Cloud Run lo envía y mata la instancia 10 s después; la API deja de aceptar peticiones, espera las que están en curso (como mucho 8 s por fase, `spring.lifecycle.timeout-per-shutdown-phase`) y cierra conexiones, y la web termina enseguida.

En la API, además:
- el jar va descomprimido por capas, así que un cambio de código sube una capa de menos de 1 MB;
- las opciones de la JVM van en `JAVA_TOOL_OPTIONS` (`-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError`): el heap es el 75 % de la memoria del contenedor, y sin memoria la JVM termina para que Cloud Run la reemplace. Si se redefine al desplegar, hay que repetir esas dos opciones. La JVM anuncia la variable en la primera línea del registro, en texto plano;
- los registros salen en JSON por la salida estándar con `severity` (DEBUG, INFO, WARNING, ERROR), `message` y la traza de los errores en `stack_trace`, que es lo que Cloud Logging y Error Reporting leen.

Los registros de la web son texto: Cloud Logging los guarda como INFO (salida estándar) o ERROR (salida de errores).

**Construir y subir las imágenes.** Cloud Run solo ejecuta imágenes `linux/amd64`, así que hay que pedirla al construir desde un Mac con ARM. En la API, con BuildKit (lo predeterminado en Docker Desktop y en `docker buildx`), Maven corre en la arquitectura de la máquina y solo la etapa final es amd64. En la web las dos etapas lo son, porque lleva binarios nativos. Las imágenes van a Artifact Registry:

```bash
export PROJECT=mi-proyecto REGION=us-east1 TAG=$(git rev-parse --short HEAD)
export REPO=$REGION-docker.pkg.dev/$PROJECT/einvoice
gcloud artifacts repositories create einvoice --repository-format=docker --location=$REGION
gcloud auth configure-docker $REGION-docker.pkg.dev
docker build --platform linux/amd64 -t $REPO/api:$TAG .                          # este repositorio
docker build --platform linux/amd64 -t $REPO/web:$TAG ../client-factura-facil    # la web
docker push $REPO/api:$TAG && docker push $REPO/web:$TAG
```

Se despliega con la etiqueta del commit, nunca con `latest`, para saber qué corre y poder volver a la anterior. La imagen de la API también se construye con el constructor clásico de Docker, sin BuildKit, que es el que usa Cloud Build (`gcloud builds submit --tag …` o un activador de Cloud Run): Maven corre entonces en linux/amd64.

**Secretos.** Van en Secret Manager y Cloud Run los entrega como variables de entorno. Cada servicio usa su propia cuenta de servicio con `roles/secretmanager.secretAccessor` sobre sus secretos, y ningún secreto va en `--set-env-vars` ni en la imagen. Cada uno se crea una vez y se rota añadiendo una versión:

```bash
printf '%s' 'postgresql://…?currentSchema=einvoice&sslmode=require' | gcloud secrets create einvoice-database-url --data-file=-
# Igual con einvoice-rabbitmq-url, einvoice-redis-url, einvoice-master-key, einvoice-jwt-secret,
# einvoice-metrics-token, einvoice-smtp-password y einvoice-session-secret (este, de la web).
gcloud iam service-accounts create einvoice-api
gcloud secrets add-iam-policy-binding einvoice-database-url \
  --member=serviceAccount:einvoice-api@$PROJECT.iam.gserviceaccount.com --role=roles/secretmanager.secretAccessor
```

Con `:latest`, una versión nueva del secreto llega con la siguiente revisión, no a las instancias que ya corren: después de rotar hay que desplegar otra vez. `EINVOICE_MASTER_KEY` no se rota así: cambiarla deja ilegibles los certificados guardados. Las variables que no son secretas (`EINVOICE_WEB_URL`, `SRI_ENABLED_ENVIRONMENTS`, `EINVOICE_PROVIDER_RUC` —obligatoria en la API y en el worker cuando la anterior incluye `2`—, `DELIVERY_EMAIL_*`, `SPRING_MAIL_HOST`, etc.) van con `--set-env-vars`.

**API.** La autenticación la hace la propia aplicación (tokens y API keys), así que el servicio admite invocaciones sin IAM. Solo la alcanza la web, por la red interna (ver «IP del cliente» más abajo):

```bash
SECRETOS=DATABASE_URL=einvoice-database-url:latest,RABBITMQ_URL=einvoice-rabbitmq-url:latest,REDIS_URL=einvoice-redis-url:latest,EINVOICE_MASTER_KEY=einvoice-master-key:latest,EINVOICE_JWT_SECRET=einvoice-jwt-secret:latest,EINVOICE_METRICS_TOKEN=einvoice-metrics-token:latest
gcloud run deploy einvoice-api --image=$REPO/api:$TAG --region=$REGION \
  --service-account=einvoice-api@$PROJECT.iam.gserviceaccount.com --allow-unauthenticated --ingress=internal \
  --cpu=1 --memory=1Gi --cpu-boost --concurrency=80 --min-instances=0 --max-instances=4 \
  --set-env-vars=EINVOICE_WORKER_ENABLED=false,EINVOICE_WEB_URL=https://app.ejemplo.ec,SRI_ENABLED_ENVIRONMENTS=1,EINVOICE_TRUSTED_PROXY_HOPS=2,DB_POOL_SIZE=5 \
  --set-secrets=$SECRETOS \
  --startup-probe=httpGet.path=/actuator/health/readiness,periodSeconds=5,timeoutSeconds=3,failureThreshold=48 \
  --liveness-probe=httpGet.path=/actuator/health/liveness,periodSeconds=30,timeoutSeconds=3,failureThreshold=3
```

- **Memoria**: 1 GiB deja 768 MB de heap. Con 512 MiB la JVM queda justa y se reinicia con carga.
- **Arranque**: `--cpu-boost` da CPU extra mientras arranca: Spring, Hibernate y Flyway tardan unos 10 s en una máquina de desarrollo y más con 1 vCPU. La prueba de arranque da hasta 240 s.
- **Sondas**: la de arranque y la de vida solo miran el estado interno de la aplicación, no la base ni RabbitMQ, para que una caída de estos no haga que Cloud Run reinicie instancias sanas una y otra vez.
- **Instancias**: `--min-instances=1` evita la espera del primer arranque a cambio de pagar una instancia siempre encendida.
- **Conexiones a la base**: cada instancia abre hasta `DB_POOL_SIZE` y en reposo se queda con una. El pooler de Supabase en modo sesión da a cada una su propia conexión real, y rechaza las que pasan de su *Pool Size* (Database → Settings): `--max-instances` × `DB_POOL_SIZE` de la API más el `DB_POOL_SIZE` del worker debe quedar por debajo, con margen para las migraciones y las herramientas. Con los valores de aquí son 4 × 5 + 8 = 28; si el plan de Supabase da menos, bajar `--max-instances` o el `DB_POOL_SIZE` de la API (las transacciones son cortas: 3 bastan con poca carga).
- **Métricas**: sin `EINVOICE_METRICS_TOKEN` no se recalculan, así que ninguna instancia consulta la base ni el broker para ellas.

**Worker.** Es la misma imagen como servicio aparte: consume RabbitMQ y tiene tareas periódicas, así que necesita CPU aunque no reciba peticiones, y no sirve un Cloud Run Job, que termina. No recibe tráfico: solo responde a las sondas.

```bash
gcloud run deploy einvoice-worker --image=$REPO/api:$TAG --region=$REGION \
  --service-account=einvoice-api@$PROJECT.iam.gserviceaccount.com --no-allow-unauthenticated --ingress=internal \
  --cpu=1 --memory=1Gi --cpu-boost --no-cpu-throttling --min-instances=1 --max-instances=1 \
  --set-env-vars=EINVOICE_WORKER_ENABLED=true,EINVOICE_WEB_URL=https://app.ejemplo.ec,SRI_ENABLED_ENVIRONMENTS=1,DB_POOL_SIZE=8 \
  --set-secrets=$SECRETOS \
  --startup-probe=httpGet.path=/actuator/health/readiness,periodSeconds=5,timeoutSeconds=3,failureThreshold=48 \
  --liveness-probe=httpGet.path=/actuator/health/liveness,periodSeconds=30,timeoutSeconds=3,failureThreshold=3
```

- **CPU siempre asignada** (`--no-cpu-throttling`) y una instancia fija: sin eso, Cloud Run congela la CPU entre peticiones y el worker deja de consumir.
- **Variables**: las de entrega, webhooks y SRI deben valer lo mismo que en la API.
- **Consumo**: el worker tiene en reposo un consumidor por cola y sube hasta `EINVOICE_WORKER_MAX_CONSUMERS` (4) mientras llegan mensajes seguidos; usa más conexiones que la API (`DB_POOL_SIZE=8`) porque sus consumidores y tareas periódicas las piden a la vez. Su latido (`EINVOICE_WORKER_POLL_INTERVAL_MS`, 2 s) consulta la base aunque no haya trabajo: subirlo reduce esas consultas a cambio de que un comprobante nuevo tarde más en empezar a procesarse.
- **Si Cloud Run reemplaza la instancia**: lo que estaba a medias vuelve a la cola (mensajes sin confirmar) o se retoma al vencer su plazo (`redelivery-lease`, `send-lease`).

**Web.**

```bash
gcloud run deploy einvoice-web --image=$REPO/web:$TAG --region=$REGION \
  --service-account=einvoice-web@$PROJECT.iam.gserviceaccount.com --allow-unauthenticated \
  --cpu=1 --memory=512Mi --cpu-boost --min-instances=0 --max-instances=4 \
  --network=default --subnet=default --vpc-egress=all-traffic \
  --set-env-vars=API_BASE_URL=https://einvoice-api-…-ue.a.run.app,APP_BASE_URL=https://app.ejemplo.ec,TRUSTED_PROXY_HOPS=1 \
  --set-secrets=SESSION_SECRET=einvoice-session-secret:latest
```

- **Puerto**: Cloud Run da `PORT=8080` y la web escucha ahí; no hace falta `--port`.
- **Dominio**: el propio se asigna con un balanceador HTTPS externo o con el mapeo de dominios de Cloud Run, y `APP_BASE_URL` y `EINVOICE_WEB_URL` deben ser esa dirección `https`. Con balanceador, `TRUSTED_PROXY_HOPS=2` (ver «IP del cliente»).
- **Llamadas a la API**: la web llega a la API por su URL `run.app`, a través de la VPC: la API va con `--ingress=internal` y la web con salida directa a la VPC (`--network`, `--subnet`, `--vpc-egress=all-traffic`), en una subred con acceso privado a Google (`gcloud compute networks subnets update default --region=$REGION --enable-private-ip-google-access`). Los sistemas externos con API key necesitan otra entrada: un balanceador que publique solo `/api/v1/**` sin `/api/v1/auth/**`, con la API en `--ingress=internal-and-cloud-load-balancing`.

**IP del cliente.** Los endpoints públicos de `/api/v1/auth` limitan los intentos por IP, y la IP sale de `X-Forwarded-For`. El frente de Google *añade* la IP de quien llama al final de esa cabecera en vez de reemplazarla, y lo que queda a su izquierda lo escribe quien llama. Por eso la web y la API no toman la primera entrada, sino la que queda en la posición N contando desde la derecha, con N el número de proxies de confianza que añaden la suya:

| Servicio | Variable | Web detrás del frente de Google | Web detrás de un balanceador HTTPS |
|---|---|---|---|
| Web | `TRUSTED_PROXY_HOPS` | 1: la IP que añade el frente | 2: el balanceador añade la IP del navegador y luego la suya |
| API | `EINVOICE_TRUSTED_PROXY_HOPS` | 2 | 2 |

- **En la API son 2 en los dos casos**: la web le pasa una sola entrada, la IP del navegador que ya calculó, y el frente de Google añade detrás la de la web. Si el camino interno no añadiera ninguna, la única entrada sería la de la web y se tomaría esa: con menos entradas que N vale la primera, porque todas las puso un proxy de confianza.
- **Por eso la API no puede quedar abierta a internet** (`--ingress=all`): quien la llamara directo pondría una entrada inventada delante de la suya y elegiría la IP con la que se le cuenta. Un balanceador delante de la API no cambia el número: añade la IP real y luego la suya, y la segunda desde la derecha sigue siendo la real.
- **Un N equivocado**: mayor de lo que corresponde, quien llama elige su IP; menor, todos los usuarios comparten la IP de un proxy, y con ella el cupo de 20 peticiones por minuto, y los ingresos empiezan a fallar con `429 RATE_LIMITED`.
- **Fuera de Cloud Run** las dos valen 1 por defecto, que es lo correcto en el compose: la web llama directo a la API y deja una sola entrada, y un proxy de entrada que reemplaza la cabecera (o que añade la suya al final) deja la buena en la última posición.

**Antes de abrirlo al público.**
- **Listas de IP permitidas**: si Supabase, CloudAMQP o Redis Cloud filtran por IP, la salida de Cloud Run necesita una IP fija (salida a la VPC con Cloud NAT y una dirección reservada).
- **Migraciones**: Flyway migra al arrancar la API y el worker; varias instancias a la vez esperan su bloqueo. Una migración que rompa la versión anterior obliga a desplegar con todo el tráfico de golpe (no por porcentajes) o a partirla en dos versiones.
- **Región**: `us-east1` es solo un ejemplo. Conviene la más cercana a la base de Supabase (y a los usuarios); `southamerica-west1` (Santiago) o `us-east1` suelen ser las de menor latencia desde Ecuador.

## CI/CD
Objetivo: format + tests + SAST/SCA + SBOM -> build OCI -> firma/scan de imagen -> migración Flyway controlada -> deploy canary -> smoke test -> rollback de aplicación sin revertir arbitrariamente migraciones destructivas. Test de restore previo a grandes cambios.

Desde el 2026-10-13 el sistema vive en dos repositorios públicos de DevNation593, cada uno con su workflow. El de este hace: pruebas de la API, cobertura mínima de los paquetes críticos, SBOM, verificación de los XSD, pruebas con navegador del recorrido completo, y construcción de la imagen. El de [la web](https://github.com/DevNation593/client-factura-facil): lint, tipos, pruebas, auditoría de dependencias, SBOM, imagen y la comprobación de que su copia del contrato es la de este repositorio. No firman ni analizan imágenes, no publican y no despliegan. No usan Code Security de GitHub (ni revisión de dependencias en los pull requests ni Dependabot): las dependencias de Maven no tienen comprobación de vulnerabilidades en CI; las de npm sí (`npm audit`).

## SLO, backup, alertas
Objetivo: monitorear p95 API, colas, SRI, DLQ, disco, conexiones, certificaciones, expiración certificados y almacenamiento. Backup diario con PITR PostgreSQL y versionado objetos; RPO/RTO aprobados; restauración ensayada. Job reconciliador de estados SRI y verificador de hashes de objetos.

Hoy existen las métricas de comprobantes, colas, outbox, entregas, certificados, artefactos y llamadas al SRI, con sus alertas de partida (`infra/observability/prometheus-alerts.yml`); los scripts de respaldo y restauración, ensayados en local; el reconciliador y el verificador de huellas. Faltan p95 de la API como objetivo, disco y conexiones, PITR, RPO y RTO acordados y un Prometheus desplegado. Detalle en el [doc 15](15-produccion.md).

## Fuentes oficiales y control de cambios
- [SRI — Facturación electrónica](https://www.sri.gob.ec/facturacion-electronica): ficha técnica esquema off-line v2.34 (julio 2026), XSD y normativa.
- [SRI — registro de proveedores (28-07-2026)](https://www.sri.gob.ec/detalle-noticias?idnoticia=1318&marquesina=1): Resolución NAC-DGERCGC26-00000027.
- [SRI — adhesión y firma](https://www.sri.gob.ec/herramienta-de-comprobantes-electronicos).

> **Límite jurídico/técnico:** estos documentos son una especificación de ingeniería, no sustituyen los XSD oficiales, la ficha técnica integral, las resoluciones publicadas en Registro Oficial ni asesoría tributaria. Antes de pasar a producción, congelar versiones de normativa, XSD y catálogos, y ejecutar la matriz de conformidad con el ambiente de certificación del SRI. El detalle y fechas de entrada en vigor de cada reforma deben corroborarse en su texto normativo publicado.
