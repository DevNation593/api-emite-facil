# Pruebas con navegador

Abren la aplicación en un navegador de verdad (Chromium, con Playwright) y recorren lo que hace una empresa: darse de alta, emitir una factura hasta `AUTORIZADO`, descargarla, recibirla por correo, reemitir un rechazo, invitar a un compañero y recibir avisos en su sistema. Prueban lo que se despliega: el jar de la API y la compilación de producción de la web.

**No transmiten nada al SRI.** El SRI lo hace un simulador (`simulator/`) y la configuración se niega a arrancar si las direcciones del SRI no apuntan a él.

## Qué hay

| Ruta | Qué es |
|---|---|
| `tests/` | Las pruebas. Cada archivo trabaja con su propia empresa, así que no dependen del orden. |
| `support/env.ts` | Puertos, direcciones y credenciales de prueba de la pila. No son secretos: solo valen para estos contenedores. |
| `support/seed.ts` | Prepara por la API (a través del proxy de la web, con la sesión del navegador) lo que no es el objeto de una prueba. |
| `support/sri.ts`, `mail.ts`, `webhook.ts` | Guion del SRI simulado, correo capturado y avisos recibidos. |
| `simulator/` | SRI simulado y receptor de avisos, en Node, con sus propias pruebas. |
| `scripts/stack.ts` | Levanta y baja la infraestructura. |
| `scripts/servers.ts` | Deja en marcha simulador, API y web mientras se escriben pruebas. |
| `../infra/docker-compose.e2e.yml` | PostgreSQL, RabbitMQ, Redis y un servidor SMTP que solo captura (Mailpit). |

La pila está aislada de la de desarrollo: otro proyecto de compose (`einvoice-e2e`), datos en memoria y puertos propios, todos en `127.0.0.1`: PostgreSQL 55432, RabbitMQ 55672, Redis 56379, Mailpit 51025 y 58025, API 8180, web 3100, simulador 8190.

## Ejecutarlas

Hacen falta Docker, Node 24 y un JDK 21 o posterior.

1. Construir lo que se prueba. Desde la raíz de este repositorio:

   ```bash
   ./mvnw -B -q -DskipTests -Djacoco.skip=true package
   ```

   Y en el repositorio de la web ([client-factura-facil](https://github.com/DevNation593/client-factura-facil)), clonado junto a este (o donde diga `E2E_FRONTEND_DIR`):

   ```bash
   npm ci && npm run build
   ```

2. Desde `/e2e`, la primera vez:

   ```bash
   npm ci && npx playwright install chromium
   ```

3. Levantar la infraestructura. Genera en `.tmp/` (sin versionar) un certificado de firma de prueba y los secretos de esta pila:

   ```bash
   npm run stack:up
   ```

4. Ejecutar. Playwright arranca el simulador, la API y la web, y los detiene al terminar:

   ```bash
   npm run e2e
   ```

5. Al acabar, bajar la infraestructura (borra sus datos):

   ```bash
   npm run stack:down
   ```

El informe queda en `playwright-report/` (`npx playwright show-report`), con una captura de cada pantalla del recorrido. Si una prueba falla, guarda además la traza.

Para escribir o depurar pruebas, `npm run servers` deja simulador, API y web en marcha en otra terminal y `npm run e2e` los reutiliza; `npx playwright test tests/factura.spec.ts` ejecuta un solo archivo. Tras cambiar el backend o el frontend hay que volver a construirlos y reiniciar esos servidores.

`npm test` ejecuta las pruebas del simulador, sin Docker ni navegador.

## Cómo está hecha la pila

- **Las cuentas son las de la propia API.** `tests/auth.setup.ts` registra al usuario `demo` desde la pantalla de registro y guarda su sesión; `tests/cuenta.spec.ts` recorre la confirmación del correo, el cambio y la recuperación de contraseña con los enlaces que captura Mailpit. La API de esta pila da fichas de acceso de 60 segundos, para que las pruebas largas crucen una renovación de sesión.
- **El SRI simulado** responde con la forma de los ejemplos de la ficha técnica (la misma que usa `SriSoapSamples` en el backend). Recibe y autoriza todo, salvo que un guion diga otra cosa para el RUC de un emisor: `DEVUELTA`, `NO AUTORIZADO`, `EN PROCESO` o `CAIDO` (corta la conexión). De una clave que no recibió responde que no la conoce, como el real.
- **El certificado** es autofirmado, generado con `keytool` como los de las pruebas del backend. No sirve ante el SRI.
- **La API** arranca con la transmisión habilitada solo para el ambiente de pruebas y apuntando al simulador, el correo hacia Mailpit y los avisos (webhooks) encendidos con permiso para direcciones locales. Nada de eso es configuración para un entorno real.

## Límites

- Un solo navegador (Chromium). En el ancho de un teléfono solo se comprueba que las pantallas principales no se desplacen de lado (`tests/movil.spec.ts`); el recorrido completo se hace en escritorio. No se prueban Firefox ni Safari.
- Las capturas se guardan para mirarlas; no se comparan con una referencia.
- No miden accesibilidad más allá de que cada control se encuentra por su rol y su nombre.
- El paso a producción de un emisor no se recorre aquí: la pila nunca habilita el ambiente 2.
- En Windows, si la API no arranca con `Unable to establish loopback connection`, el directorio temporal del usuario no admite los sockets que usa el JDK: definir `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=<otro directorio>` antes de ejecutar.
