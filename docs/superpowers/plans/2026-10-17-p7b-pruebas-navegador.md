# Plan 7b — Pruebas con navegador del recorrido completo

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** que una prueba automática abra la aplicación en un navegador de verdad y recorra lo que hace una empresa: darse de alta, cargar su certificado, emitir una factura y verla `AUTORIZADO`, descargar el XML y el RIDE, recibir el correo, reemitir un rechazo, invitar a un compañero y recibir un aviso (webhook). Hoy nada de eso se ha visto funcionar junto: el entregable del Plan 7 ("emitir hasta `AUTORIZADO` contra staging") sigue sin cumplirse.

**Architecture:** un paquete nuevo `e2e/` con Playwright. La infraestructura (PostgreSQL, RabbitMQ, Redis, Keycloak y un servidor SMTP que solo captura) corre en un compose propio, aislado del de desarrollo y sin datos persistentes. La API (el jar real) y la web (la compilación de producción) corren en la propia máquina, para que el proveedor de identidad tenga la misma dirección para el navegador y para la API. El SRI lo hace un simulador en Node que responde con la forma de los ejemplos de la ficha y se puede guionizar por RUC; el mismo proceso recibe los webhooks.

**Tech Stack:** `@playwright/test` (Chromium), Node 24, Docker Compose, `keytool` del JDK para el certificado de prueba, Mailpit como SMTP de captura. Nada nuevo en el backend ni en el frontend salvo lo que las pruebas obliguen a corregir.

**Spec:** [plan maestro](2026-10-10-plan-maestro.md) (P7: "Prueba end-to-end: emitir hasta `AUTORIZADO` contra staging"; P6b: "sin recorrer en un navegador"), [doc 15](../../15-produccion.md) ("Pruebas automatizadas con navegador. El recorrido se ha hecho a mano dos veces; nadie ha mirado las pantallas"), [frontend/README](../../../frontend/README.md) ("Límites conocidos"), [doc 12](../../12-testing-certification.md).

**Por qué no el Plan 9:** sigue bloqueado; faltan los XSD oficiales de los demás comprobantes. Este plan no depende de nada externo.

**Rama:** `p7b-pruebas-navegador`, salida de `p6b-webhooks` (la pantalla "Integraciones" solo existe ahí). El PR va contra `dev` y arrastra los commits de webhooks mientras [el suyo](https://github.com/TheWadjet-dev/sri-facturacion/pull/1) no se fusione.

## Global Constraints

- **Aislado del entorno de desarrollo.** Proyecto de compose `einvoice-e2e`, sin volúmenes con nombre (los datos viven en `tmpfs`), y puertos propios, todos en `127.0.0.1`: PostgreSQL 55432, RabbitMQ 55672, Redis 56379, Keycloak 8181, Mailpit 51025 (SMTP) y 58025 (HTTP), API 8180, web 3100, simulador 8190. Levantar o bajar esta pila no toca el compose `einvoice` ni su volumen `pgdata`.
- **Nunca se transmite al SRI real.** La API de estas pruebas arranca con `einvoice.sri.allow-custom-endpoints=true` y las dos direcciones del ambiente de pruebas apuntando al simulador; `SRI_ENABLED_ENVIRONMENTS=1` (nunca `2`). La configuración de la API de las pruebas no se construye si esas dos direcciones no apuntan al simulador.
- **Sin secretos.** Las credenciales de la pila son valores de prueba escritos en `e2e/support/env.ts` y solo valen para ella; la clave maestra y el secreto de sesión se generan en cada arranque. El certificado `.p12` se genera en `e2e/.tmp/` (sin versionar) con `keytool`, igual que `TestCertificates`: RSA 2048, `SHA256withRSA`, `KeyUsage=digitalSignature`.
- **Se prueba lo que se despliega:** `java -jar backend/target/einvoice-*.jar` y `next start` sobre `next build`. No `spring-boot:run` ni `next dev`.
- **Los selectores son los de una persona:** rol y nombre accesible (`getByRole`, `getByLabel`), no clases ni identificadores de prueba. Si una pantalla no se deja encontrar así, se corrige la pantalla (y su prueba de componente).
- **Cada archivo de pruebas trabaja con su empresa** (un RUC distinto por archivo), para que no dependan del orden. Lo que no es el objeto de la prueba se prepara llamando a la API a través del proxy de la web con la sesión del navegador (`page.request`), no clic a clic.
- **Un fallo encontrado en la aplicación se corrige en su capa**, con una prueba que falle primero (de componente o de integración), en un commit propio. Las pruebas con navegador no sustituyen a esas.
- Textos de interfaz en español; código en inglés. Sin esperas fijas (`waitForTimeout`) salvo la de caducidad de sesión de la Task 7.
- Comandos: `npm test` en `e2e/` (pruebas del simulador), `npm run stack:up`, `npm run e2e`, `npm run stack:down`. Los de backend y frontend no cambian.

## Decisiones de diseño

- **Paquete aparte (`e2e/`)** y no dentro de `frontend/`: lo que prueba es el sistema entero, y Playwright no debe entrar en la imagen ni en la auditoría de dependencias de la web.
- **El reino de Keycloak se deriva del de desarrollo** (`infra/keycloak/einvoice-realm.json`) al levantar la pila: mismas reglas, con la redirección a `http://localhost:3100`, un segundo usuario (`colega`, `colega@example.test`) y fichas de acceso de 60 segundos, para que toda prueba larga cruce una renovación.
- **El simulador se guioniza por RUC**, que va dentro de la clave de acceso (posiciones 11 a 23): `PUT /control/ruc/{ruc}` con `{ "reception": "RECIBIDA" | "DEVUELTA" | "CAIDO", "authorization": "AUTORIZADO" | "NO AUTORIZADO" | "EN PROCESO" }`. Sin guion, recibe y autoriza. `GET /control/ruc/{ruc}` devuelve cuántas recepciones y consultas llegaron. Los webhooks llegan a `POST /sink/{nombre}` y se leen en `GET /control/sink/{nombre}`.
- **Capturas de cada pantalla** del recorrido, como adjuntos del informe: es la primera vez que alguien las mira. Lo que se vea mal se anota en el doc 15; solo se corrige aquí lo que impida usar la pantalla.
- **En CI corre como un trabajo más** (`e2e`), con el informe y las trazas como artefacto cuando falla.

## Review Focus

1. **La sesión caduca a media tarea** (ficha de acceso vencida) → la siguiente acción funciona sin volver a iniciar sesión y sin perder lo escrito (Task 7).
2. **Doble clic en "Emitir"** → una sola factura (Task 3).
3. **El SRI no contesta** → la pantalla dice que el comprobante espera, no que falló, y se autoriza solo cuando el SRI vuelve (Task 4).
4. **Alguien sin permiso escribe la dirección de una pantalla que no le toca** (un facturador abre `/integraciones` o `/equipo`) → no ve datos ni puede cambiarlos (Task 5).
5. **Enlace directo sin sesión** (`/documentos/{id}` pegado en otro navegador) → pide iniciar sesión y vuelve a esa misma página (Task 7).

---

### Task 1: La pila de pruebas y el primer inicio de sesión

**Files:**
- Create: `infra/docker-compose.e2e.yml`, `e2e/package.json`, `e2e/tsconfig.json`, `e2e/playwright.config.ts`, `e2e/support/env.ts`, `e2e/scripts/stack.ts`, `e2e/scripts/servers.ts`, `e2e/simulator/server.mjs`, `e2e/simulator/soap.mjs`, `e2e/simulator/server.test.mjs`, `e2e/support/fixtures.ts`, `e2e/tests/auth.setup.ts`, `e2e/tests/inicio.spec.ts`
- Modify: `.gitignore` (`e2e/.tmp/`, `e2e/test-results/`, `e2e/playwright-report/`)

**Interfaces — Produces:**
- `e2e/support/env.ts`: `PORTS`, `URLS` (`web`, `api`, `issuer`, `simulator`, `mailpit`), `USERS` (`demo`, `colega`: `username`, `password`, `email`), `apiEnv(): Record<string,string>`, `webEnv(): Record<string,string>`, `TMP_DIR`, `CERT_PATH`, `CERT_PASSWORD`.
- `e2e/scripts/stack.ts up|down`: `up` escribe `e2e/.tmp/realm.json` y `e2e/.tmp/firma.p12`, y levanta el compose con `--wait`; `down` lo baja con `-v`.
- `e2e/simulator/soap.mjs`: `received()`, `returned(accessKey, {identifier, text})`, `authorized(accessKey)`, `notAuthorized(accessKey, {identifier, text})`, `processing(accessKey)`, con la forma exacta de `SriSoapSamples`.
- `e2e/simulator/server.mjs`: `start(port): Promise<Server>`; rutas `/recepcion`, `/autorizacion`, `/control/ruc/{ruc}`, `/sink/{nombre}`, `/control/sink/{nombre}`, `/health`.
- `e2e/support/fixtures.ts`: `test` extendido con `login(page, user)`, y los proyectos de Playwright `setup` (guarda `e2e/.tmp/demo.json` y `e2e/.tmp/colega.json`) y `chromium` (usa `demo.json`).

- [x] **Step 1:** pruebas del simulador (`node --test`): sin guion `/recepcion` responde `RECIBIDA` y `/autorizacion` responde `AUTORIZADO` con la clave consultada como número; con `PUT /control/ruc/{ruc}` `{"reception":"DEVUELTA"}` la recepción de una clave de ese RUC responde `DEVUELTA` con el mensaje `35`, y la de otro RUC sigue `RECIBIDA`; `CAIDO` corta la conexión sin responder; `GET /control/ruc/{ruc}` cuenta recepciones y consultas; `POST /sink/a` guarda cabeceras y cuerpo y `GET /control/sink/a` los devuelve. Ejecutarlas y verlas fallar.
- [x] **Step 2:** `soap.mjs` y `server.mjs` hasta que pasen.
- [x] **Step 3:** compose, `env.ts`, `stack.ts` y `playwright.config.ts` (`workers: 1`, `fullyParallel: false`, `webServer` con simulador, API y web, `reuseExistingServer` fuera de CI, `trace: "retain-on-failure"`). `apiEnv()` falla si las direcciones del SRI no apuntan al simulador.
- [x] **Step 4:** `auth.setup.ts` inicia sesión en Keycloak con cada usuario y guarda su estado; `inicio.spec.ts`: sin sesión, `/` lleva a `/ingresar`; con sesión, se ve el marco de la aplicación con el nombre del usuario.
- [x] **Step 5:** `npm run stack:up && npm run e2e` → 2 pruebas en verde. Commit `test(e2e): pila de pruebas con navegador y simulador del SRI`.

### Task 2: Alta de la empresa

**Files:** Create `e2e/tests/alta.spec.ts`, `e2e/support/seed.ts`, `e2e/support/ruc.ts`

**Interfaces — Produces:**
- `ruc.ts`: `newRuc(): string` (válido según la regla de la API, distinto en cada llamada).
- `seed.ts`: `seedCompany(page, opts?): Promise<{ tenantId, taxpayerId, ruc, establishment, emissionPoint }>` (empresa, emisor, establecimiento, punto de emisión y certificado por la API), `seedCustomer(page, company)`, `seedProduct(page, company)`, `seedInvoice(page, company, {customer, product}): Promise<{ id, accessKey }>`, `waitForState(page, company, documentId, state)`.

- [x] **Step 1:** `alta.spec.ts`, todo por la interfaz: crear la empresa, completar el emisor (RUC, razón social, dirección, establecimiento `001`, punto `001`), cargar `firma.p12` con su contraseña y ver el certificado vigente con su fecha de caducidad; crear un cliente con cédula y un producto con IVA 15 %; ambos aparecen en sus listas al recargar. Captura de cada pantalla.
- [x] **Step 2:** ejecutarla; corregir lo que falle (regla de "fallo encontrado").
- [x] **Step 3:** `seed.ts` y `ruc.ts`; una prueba corta que siembra una empresa y la ve en el selector de empresas.
- [x] **Step 4:** verde. Commit `test(e2e): alta de empresa, emisor, certificado y catálogos`.

### Task 3: Emitir hasta AUTORIZADO, descargas y correo

**Files:** Create `e2e/tests/factura.spec.ts`, `e2e/support/mail.ts`

**Interfaces — Produces:** `mail.ts`: `waitForMail(to: string): Promise<{ subject, attachments: {fileName, contentType}[] }>` (consulta la API de Mailpit).

- [x] **Step 1:** con una empresa sembrada: abrir "Nueva factura", elegir cliente y producto, cantidad 2; la vista previa muestra subtotal, IVA y total; guardar como borrador, verlo en "Borradores", reabrirlo y emitir. El monitor pasa a "Autorizado" sin recargar. El detalle muestra clave de acceso de 49 dígitos, número y fecha de autorización, y un total igual al de la vista previa.
- [x] **Step 2:** descargas desde el detalle: el XML autorizado contiene `<estado>AUTORIZADO</estado>` y la misma clave; el RIDE empieza por `%PDF-`; los nombres de archivo llevan el número del comprobante. Llega un correo al del cliente con el XML y el PDF adjuntos, y el detalle muestra la entrega como enviada.
- [x] **Step 3 (Review Focus 2):** doble clic en "Emitir" → un solo comprobante en la lista y un secuencial consumido.
- [x] **Step 4:** verde. Commit `test(e2e): emisión hasta AUTORIZADO, descargas y correo`.

### Task 4: Rechazo, reemisión y SRI caído

**Files:** Create `e2e/tests/rechazo.spec.ts`, `e2e/support/sri.ts`

**Interfaces — Produces:** `sri.ts`: `scriptSri(ruc, {reception?, authorization?})`, `sriCalls(ruc): Promise<{reception: number, authorization: number}>`.

- [x] **Step 1:** guion `DEVUELTA`: emitir → el detalle dice "Devuelta" con el mensaje del SRI (identificador y texto); quitar el guion, reemitir desde el detalle → el nuevo comprobante queda "Autorizado", con otro número, y cada uno enlaza al otro.
- [x] **Step 2:** guion `NO AUTORIZADO` en la autorización: mismo recorrido desde "No autorizado".
- [x] **Step 3 (Review Focus 3):** guion `CAIDO`: emitir → la pantalla muestra que espera al SRI (no un error ni "Devuelta"); quitar el guion → pasa a "Autorizado" sin intervención (la API de estas pruebas usa `SRI_RETRY_BACKOFF=2s`), y `sriCalls` muestra una sola recepción aceptada.
- [x] **Step 4:** verde. Commit `test(e2e): rechazo, reemisión y SRI caído`.

### Task 5: Equipo, invitación y permisos

**Files:** Create `e2e/tests/equipo.spec.ts`

- [x] **Step 1:** `demo` invita a `colega@example.test` como facturador y copia el enlace mostrado; en otro contexto de navegador, `colega` abre el enlace, acepta y ve la empresa; `demo` lo ve en "Equipo" con su rol.
- [x] **Step 2 (Review Focus 4):** `colega` no tiene "Equipo" ni "Integraciones" en el menú; al escribir `/integraciones` y `/equipo` no ve datos, y `POST`/`DELETE` por el proxy sobre suscripciones e invitaciones responden `403`. Sí puede emitir una factura.
- [x] **Step 3:** `demo` lo retira → al recargar, `colega` ya no ve la empresa.
- [x] **Step 4:** verde. Commit `test(e2e): equipo, invitación y permisos por rol`.

### Task 6: Integraciones (webhooks)

**Files:** Create `e2e/tests/integraciones.spec.ts`, `e2e/support/webhook.ts`

**Interfaces — Produces:** `webhook.ts`: `sinkUrl(name)`, `sinkRequests(name)`, `verifySignature(secret, headers, body): boolean` (convención Standard Webhooks).

- [x] **Step 1:** registrar `sinkUrl` con los eventos de autorización y rechazo; el secreto (`whsec_…`) se muestra una vez y, al recargar, ya no. Emitir una factura → llega un `document.authorized` cuya firma se verifica con ese secreto y cuyo cuerpo no lleva importes ni datos del cliente; el historial de la pantalla lo muestra entregado.
- [x] **Step 2:** rotar el secreto → el siguiente aviso lleva dos firmas y la nueva verifica. Desactivar la suscripción → emitir otra factura no produce aviso.
- [x] **Step 3:** verde. Commit `test(e2e): avisos a sistemas de clientes`.

### Task 7: Sesión

**Files:** Create `e2e/tests/sesion.spec.ts`

- [x] **Step 1 (Review Focus 1):** con la factura a medio escribir, esperar 65 s (la ficha dura 60) y emitir → funciona, sin pasar por la pantalla de ingreso.
- [x] **Step 2 (Review Focus 5):** en un contexto sin sesión, abrir `/documentos/{id}` → inicio de sesión → vuelve a ese detalle. Cerrar sesión → `/documentos` vuelve a pedir ingreso y la sesión de Keycloak también se cerró.
- [x] **Step 3:** verde. Commit `test(e2e): renovación de sesión, enlace directo y cierre`.

### Task 8: CI y documentación

**Files:** Modify `.github/workflows/ci.yml`, `README.md`, `frontend/README.md`, `docs/DEVELOPMENT.md`, `docs/12-testing-certification.md`, `docs/15-produccion.md`, `docs/superpowers/plans/2026-10-10-plan-maestro.md`; Create `e2e/README.md`

- [x] **Step 1:** trabajo `e2e` en CI: Java 21 y Node 24, `./mvnw -B -q -DskipTests -Djacoco.skip=true package`, `npm ci && npm run build` en `frontend`, `npm ci && npx playwright install --with-deps chromium && npm test && npm run stack:up && npm run e2e` en `e2e`; sube `e2e/playwright-report` siempre.
- [x] **Step 2:** mirar las capturas del informe una a una y anotar en el doc 15 lo que se vea mal.
- [x] **Step 3:** documentación: cómo se ejecuta (`e2e/README.md`, `DEVELOPMENT`), qué cubre y qué no (doc 12 y 15, límites conocidos del frontend), fila P7b y decisión en el plan maestro; P7 y P6b dejan de decir "sin pruebas con navegador".
- [x] **Step 4:** la suite completa tres veces seguidas en verde en local; backend y frontend con sus comandos de siempre si se tocaron. Commit `ci: pruebas con navegador` y `docs: pruebas con navegador`.
