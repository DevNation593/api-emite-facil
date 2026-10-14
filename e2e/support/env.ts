// Direcciones, puertos y credenciales de la pila de pruebas. Son valores de prueba: solo valen para
// los contenedores de infra/docker-compose.e2e.yml, que escuchan en 127.0.0.1 y no guardan datos.
// La clave maestra y el secreto de sesión no están aquí: se generan al levantar la pila.

import { existsSync, readFileSync, readdirSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

export const E2E_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
export const REPO_DIR = path.resolve(E2E_DIR, "..");
/**
 * La aplicación web vive en otro repositorio (client-factura-facil). Por defecto, clonado junto a
 * este; si no, E2E_FRONTEND_DIR dice dónde.
 */
export const FRONTEND_DIR = path.resolve(process.env.E2E_FRONTEND_DIR ?? path.join(REPO_DIR, "..", "client-factura-facil"));
export const TMP_DIR = path.join(E2E_DIR, ".tmp");

export const SECRETS_PATH = path.join(TMP_DIR, "secrets.json");
export const CERT_PATH = path.join(TMP_DIR, "firma.p12");
export const CERT_PASSWORD = "clave-de-prueba-p12";

export const PORTS = {
  postgres: 55432,
  rabbitmq: 55672,
  redis: 56379,
  smtp: 51025,
  mailpit: 58025,
  api: 8180,
  web: 3100,
  simulator: 8190,
} as const;

export const URLS = {
  web: `http://localhost:${PORTS.web}`,
  api: `http://localhost:${PORTS.api}`,
  simulator: `http://127.0.0.1:${PORTS.simulator}`,
  mailpit: `http://127.0.0.1:${PORTS.mailpit}`,
} as const;

/** Cuenta de prueba: se registra desde la pantalla de registro al empezar (tests/auth.setup.ts). */
export type TestUser = { email: string; password: string };

export const USERS = {
  demo: {
    email: "demo@example.test",
    password: "demo-solo-pruebas",
  },
} as const satisfies Record<string, TestUser>;

/**
 * Conexiones de la pila, una URL cada una. Las leen tanto los contenedores (que sacan de ellas sus
 * credenciales) como la API.
 */
const CONNECTIONS = {
  database: `postgresql://app:postgres-solo-pruebas@localhost:${PORTS.postgres}/einvoice`,
  rabbitmq: `amqp://einvoice:rabbitmq-solo-pruebas@localhost:${PORTS.rabbitmq}`,
  redis: `redis://:redis-solo-pruebas@localhost:${PORTS.redis}`,
};

/** Variables con las que se levanta infra/docker-compose.e2e.yml. */
export function composeEnv(): Record<string, string> {
  return {
    E2E_DATABASE_URL: CONNECTIONS.database,
    E2E_POSTGRES_PORT: String(PORTS.postgres),
    E2E_RABBITMQ_URL: CONNECTIONS.rabbitmq,
    E2E_RABBITMQ_PORT: String(PORTS.rabbitmq),
    E2E_REDIS_URL: CONNECTIONS.redis,
    E2E_REDIS_PORT: String(PORTS.redis),
    E2E_SMTP_PORT: String(PORTS.smtp),
    E2E_MAILPIT_PORT: String(PORTS.mailpit),
  };
}

type Secrets = { masterKey: string; sessionSecret: string; jwtSecret: string };

function secrets(): Secrets {
  if (!existsSync(SECRETS_PATH)) {
    throw new Error("La pila de pruebas no está levantada: ejecute `npm run stack:up` en /e2e");
  }
  return JSON.parse(readFileSync(SECRETS_PATH, "utf8")) as Secrets;
}

/** Entorno de la API: transmite solo al SRI simulado y entrega el correo al servidor de captura. */
export function apiEnv(): Record<string, string> {
  const env = {
    SERVER_PORT: String(PORTS.api),
    DATABASE_URL: CONNECTIONS.database,
    RABBITMQ_URL: CONNECTIONS.rabbitmq,
    REDIS_URL: CONNECTIONS.redis,
    EINVOICE_MASTER_KEY: secrets().masterKey,
    EINVOICE_JWT_SECRET: secrets().jwtSecret,
    // Los enlaces de los correos (confirmar el correo, cambiar la contraseña) llevan a la web de la pila.
    EINVOICE_WEB_URL: URLS.web,
    // Fichas de acceso cortas: la prueba de sesión espera a que una caduque y se renueve sola.
    EINVOICE_AUTH_ACCESS_TOKEN_TTL: "60s",
    // Todo llega desde esta máquina: el límite por dirección IP no debe estorbar a las pruebas.
    EINVOICE_AUTH_PUBLIC_REQUESTS_PER_MINUTE: "1000",
    SRI_ENABLED_ENVIRONMENTS: "1",
    EINVOICE_SRI_ALLOW_CUSTOM_ENDPOINTS: "true",
    EINVOICE_SRI_TEST_RECEPTION: `${URLS.simulator}/recepcion`,
    EINVOICE_SRI_TEST_AUTHORIZATION: `${URLS.simulator}/autorizacion`,
    SRI_AUTHORIZATION_DELAY: "1s",
    SRI_RETRY_BACKOFF: "2s",
    SRI_READ_TIMEOUT: "5s",
    DELIVERY_EMAIL_ENABLED: "true",
    DELIVERY_EMAIL_FROM: "facturas@example.test",
    SPRING_MAIL_HOST: "127.0.0.1",
    SPRING_MAIL_PORT: String(PORTS.smtp),
    SMTP_STARTTLS: "false",
    EINVOICE_WEBHOOKS_ENABLED: "true",
    EINVOICE_WEBHOOKS_ALLOW_INSECURE_HTTP: "true",
    EINVOICE_WEBHOOKS_ALLOW_PRIVATE_ADDRESSES: "true",
    EINVOICE_WORKER_ENABLED: "true",
  };
  assertNeverTheRealSri(env);
  return env;
}

/** Estas pruebas no transmiten al SRI: solo el ambiente de pruebas, y apuntando al simulador. */
export function assertNeverTheRealSri(env: Record<string, string>): void {
  if (env.SRI_ENABLED_ENVIRONMENTS !== "1") {
    throw new Error("Las pruebas con navegador solo habilitan el ambiente de pruebas (1)");
  }
  for (const name of ["EINVOICE_SRI_TEST_RECEPTION", "EINVOICE_SRI_TEST_AUTHORIZATION"]) {
    const value = env[name] ?? "";
    if (!value.startsWith(`${URLS.simulator}/`) || value.includes("sri.gob.ec")) {
      throw new Error(`${name} debe apuntar al SRI simulado, no a ${value || "(vacío)"}`);
    }
  }
}

/** Entorno de la web. Lo opcional va vacío para que no lo rellene un .env.local de desarrollo. */
export function webEnv(): Record<string, string> {
  return {
    PORT: String(PORTS.web),
    API_BASE_URL: URLS.api,
    API_TIMEOUT_MS: "",
    APP_BASE_URL: URLS.web,
    SESSION_SECRET: secrets().sessionSecret,
    NEXT_TELEMETRY_DISABLED: "1",
  };
}

/** El jar de la API, ya construido (`./mvnw -DskipTests -Djacoco.skip=true package` en la raíz). */
export function apiJar(): string {
  const target = path.join(REPO_DIR, "target");
  const jar = existsSync(target) ? readdirSync(target).find((name) => /^einvoice-.*\.jar$/.test(name)) : undefined;
  if (!jar) {
    throw new Error("Falta el jar de la API: ejecute `./mvnw -DskipTests -Djacoco.skip=true package` en la raíz del repositorio");
  }
  return path.join(target, jar);
}
