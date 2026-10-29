# DEVELOPMENT — Guía maestra de implementación

## 1. Objetivo y decisiones irrevocables del MVP
Crear SaaS multiempresa de facturación electrónica Ecuador, priorizando **factura (01)** con flujo completo firmado, recibido, autorizado y entregado; resto de documentos por fase. Backend **Java 25 + Spring Boot + JPA/Hibernate + Flyway**, PostgreSQL, RabbitMQ, Redis, S3/MinIO, frontend Next.js/TypeScript, Docker. Arquitectura monolito modular + workers.

## 2. Documentos que debe leer el equipo
1 PRD; 2 Cumplimiento SRI; 3 Arquitectura; 4 PostgreSQL; 5 Flujo; 6 Clave; 7 Firma; 8 SOAP; 9 Errores; 10 Seguridad; 11 API; 12 Pruebas; 13 Despliegue; 14 Integración XSD; 15 Certificación y paso a producción.

El desglose ejecutable de estas etapas está en el [plan maestro](superpowers/plans/2026-10-10-plan-maestro.md).

## 3. Orden exacto de trabajo
- **Etapa 0 (conformidad)**: verificar ficha v2.34, resolución de proveedores y anexos desde el SRI; contrastar con el paquete oficial los XSD/XML de comprobantes y `xmldsig-core-schema.xsd` ya incorporados en `src/main/resources/xsd/`, y registrar URL, hash, fecha y texto normativo. Ver [anexo de integración de XSD](14-xsd-integration.md). Crear tabla `schema_registry`/catálogos. Confirmar aplicación de `RUC Proveedor` y habilitación del emisor.
- **Etapa 1 (infra)**: repositorio con Maven, Docker Compose, PostgreSQL/Flyway, RabbitMQ, Redis; CI, healthchecks y Testcontainers. Next.js llega en el Plan 7 y el almacenamiento S3 local queda pendiente de elegir (doc 13).
- **Etapa 2 (seguridad)**: identidad (hoy, cuentas propias de la API: Plan 10), tenants, memberships/RBAC, taxpayers, sucursales/puntos; tenant isolation tests.
- **Etapa 3 (datos)**: clientes/productos, tasas efectivas por fecha, validaciones, BigDecimal, persistencia secuenciales, outbox transaccional, idempotencia.
- **Etapa 4 (fiscal)**: generador XML factura por versión, validación XSD y firma XAdES-BES. Fixture aceptado SRI es obligatorio antes de integrar emisión real.
- **Etapa 5 (SRI)**: clientes SOAP, mapping de estados, workers, reconciliación y DLQ; nunca equiparar RECIBIDA a AUTORIZADO.
- **Etapa 6 (documentos)**: RIDE, almacenamiento inmutable, entrega y auditoría.
- **Etapa 7 (frontend)**: onboarding emisor/certificado, catálogo, editor factura, monitor SRI, reintentos y descargas con scopes.
- **Etapa 8 (certificación)**: matriz integral doc 12, sandbox SRI, edge cases, security review, backup/restore y activación producción por tenant habilitado.
- **Etapa 9 (ampliación)**: notas/créditos, retenciones, guías y anexos.

## 4. Reglas de implementación que un asistente de código debe seguir
1. No fabricar respuestas del SRI ni hardcodear IVA/catálogo/XSD; tomar de fuentes controladas.
2. Ningún cambio de estado ocurre sin evento de auditoría y transacción correspondiente.
3. `Idempotency-Key` y secuenciales únicos con locks y restricciones SQL.
4. Cada acción background se deduplica; transacción de negocio y `outbox` atómicas.
5. Firma XAdES real verificada con documento aceptado por SRI; no usar XMLDSig solo ni dejar mock en producción.
6. `tenantId` procede del contexto autenticado, jamás se acepta sin verificación.
7. P12/password cifrados y referenciados, nunca enviados a mensajería ni logs.
8. Prohibido perder/editar XML firmado, autorización o mensajes SRI; archivo con hash.
9. No reemitir ni cambiar clave tras timeout sin consultar autorización y seguir política fiscal.
10. Todo PR incluye prueba unitaria/integración/documentación y migration Flyway cuando proceda.

## 5. Primer sprint — Definition of Done
- `docker compose up -d` levanta infra local con credenciales provistas por `.env` privado.
- Spring Boot `/actuator/health`, OpenAPI e integración Testcontainers funcionando.
- Migraciones de tenants, taxpayers, branches, points, sequences, invoice, outbox.
- Endpoints: crear emisor, cliente, producto y `POST /invoices` (aún **sin** transmisión real).
- Prueba concurrente demuestra secuenciales exclusivos; prueba de aislamiento bloquea lectura entre empresas.
- Generación de clave de acceso: pasan los vectores sintéticos del doc 06 y, cuando se obtengan, claves reales autorizadas por el SRI.

## 6. Segundo sprint — Definition of Done
- XML factura validado con XSD oficial, firma XAdES verificada, certificado P12 cifrado.
- SRI certificación devuelve documento recibido/autorizado real; persistir evidencia.
- Dashboard y PDF RIDE con artefactos descargables.
- Retry/reconciliación, email, DLQ, trazas y alertas probadas.

## 7. Guía de comandos
```bash
# Infraestructura local: desde la raíz. Antes, copiar .env.example a .env y poner valores locales.
docker compose --env-file .env -f infra/docker-compose.yml up -d
# Compilar y probar: desde la raíz de este repositorio. Las pruebas *IT levantan sus propios contenedores (requiere Docker).
./mvnw clean verify
# Arrancar la API contra la infraestructura local: desde la raíz de este repositorio, con las variables de abajo definidas.
./mvnw spring-boot:run
# Pruebas con navegador del recorrido completo: desde /e2e, con su propia infraestructura (e2e/README.md).
npm run stack:up && npm run e2e
```
Para `spring-boot:run` la aplicación necesita `EINVOICE_JWT_SECRET` (clave con la que firma sus tokens, al menos 32 bytes: `openssl rand -base64 48`), `EINVOICE_WEB_URL` (dirección de la web, `http://localhost:3000`) y `EINVOICE_MASTER_KEY` (clave maestra de 32 bytes en Base64 que cifra los certificados; para desarrollo, `openssl rand -base64 32`); sin ellas no arranca. La base de datos, RabbitMQ y Redis se conectan con las mismas `DATABASE_URL`, `RABBITMQ_URL` y `REDIS_URL` del `.env` con las que se levantó la infraestructura: basta exportarlas.

**Transmisión al SRI.** Por defecto la aplicación **no transmite**: genera y firma, y los comprobantes esperan en `SIGNED`. Para enviar al ambiente de pruebas del SRI hay que definir `SRI_ENABLED_ENVIRONMENTS=1` (y `1,2` para producción, solo después de la certificación). El procesamiento en segundo plano necesita RabbitMQ en marcha; `EINVOICE_WORKER_ENABLED=false` lo apaga en esa instancia. Los tiempos de espera y reintentos están en el doc 08.

**Entrega por correo.** Apagada por defecto: el comprobante autorizado se archiva (XML autorizado y RIDE) pero no se envía. Para probarla en local hace falta un servidor SMTP de desarrollo (por ejemplo uno que solo capture los mensajes) y definir `DELIVERY_EMAIL_ENABLED=true`, `DELIVERY_EMAIL_FROM`, `SPRING_MAIL_HOST`, `SPRING_MAIL_PORT` y, si ese servidor no habla TLS, `SMTP_STARTTLS=false`. `./mvnw test -Dtest=RidePdfBuilderTest` deja un RIDE de ejemplo en `target/ride-ejemplo.pdf`.

**Cobertura y certificación.** `./mvnw verify` falla si la cobertura de líneas de algún paquete crítico baja del 90 %. La cobertura se mide con todas las pruebas: al ejecutar solo algunas (`-Dtest=…`, `-Dit.test=…`) hay que añadir `-Djacoco.skip=true`, o la comprobación fallará. `./mvnw -B verify -Pcertification` es la certificación contra el SRI de pruebas: transmite comprobantes reales y solo corre con sus variables definidas ([doc 15](15-produccion.md)).

**Dependencias con licencia a tener en cuenta.** El RIDE se genera con OpenPDF (LGPL 2.1 / MPL 2.0), usado como biblioteca sin modificar.

### Arrancar todo en local, con la aplicación web

No hace falta proveedor de identidad: las cuentas las lleva la API.

1. API, desde la raíz de este repositorio, con las variables de arriba (y, si se quieren recibir los correos de la cuenta, un SMTP en `SPRING_MAIL_*` y `DELIVERY_EMAIL_FROM`):
   ```bash
   ./mvnw spring-boot:run
   ```
2. Aplicación web, desde el repositorio de la web (`client-factura-facil`), tras copiar `.env.example` a `.env.local` y poner un `SESSION_SECRET`:
   ```bash
   npm ci
   ```
   ```bash
   npm run dev
   ```
3. Abrir `http://localhost:3000`, crear una cuenta en "Crear una cuenta" e ingresar. Sin SMTP el correo de confirmación no llega, pero se puede usar la aplicación igual.

Sin certificado, una factura emitida queda en "Sin firmar"; con uno de prueba queda en "Firmada" y no avanza, porque la transmisión al SRI está apagada (`SRI_ENABLED_ENVIRONMENTS`). Detalles de la aplicación web en [frontend/README.md](https://github.com/DevNation593/client-factura-facil/blob/dev/README.md).

## 8. Preguntas abiertas antes de desarrollo productivo
- ¿Forma societaria/RUC del proveedor y actividades actualizadas al registro SRI?
- ¿Documentos/tipos especiales requeridos por clientes iniciales?
- ¿Tarifas vigentes y excepciones por segmento de clientes?
- ¿Política de firma bajo custodio vs certificado aportado por emisor y validez legal de delegación?
- ¿Cloud/región, políticas de privacidad, respaldo y retención verificados?
- ¿Costos por documento, límites por plan y proceso comercial B2B?

## 9. Regla de aceptación final
No habilitar un tenant en producción sin: comprobación de habilitación emisor, certificado válido, pruebas XSD+XAdES+SOAP certificación, política proveedor SRI, controles de seguridad y respaldo auditado.

## Fuentes oficiales y control de cambios
- [SRI — Facturación electrónica](https://www.sri.gob.ec/facturacion-electronica): ficha técnica esquema off-line v2.34 (julio 2026), XSD y normativa.
- [SRI — registro de proveedores (28-07-2026)](https://www.sri.gob.ec/detalle-noticias?idnoticia=1318&marquesina=1): Resolución NAC-DGERCGC26-00000027.
- [SRI — adhesión y firma](https://www.sri.gob.ec/herramienta-de-comprobantes-electronicos).

> **Límite jurídico/técnico:** estos documentos son una especificación de ingeniería, no sustituyen los XSD oficiales, la ficha técnica integral, las resoluciones publicadas en Registro Oficial ni asesoría tributaria. Antes de pasar a producción, congelar versiones de normativa, XSD y catálogos, y ejecutar la matriz de conformidad con el ambiente de certificación del SRI. El detalle y fechas de entrada en vigor de cada reforma deben corroborarse en su texto normativo publicado.
