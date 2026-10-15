// Levanta o baja la infraestructura de las pruebas con navegador.
//   node scripts/stack.ts up     genera el certificado y los secretos, y arranca los contenedores
//   node scripts/stack.ts down   los detiene y borra sus datos

import { execFileSync, spawnSync } from "node:child_process";
import { randomBytes } from "node:crypto";
import { existsSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
import path from "node:path";

import {
  CERT_PASSWORD,
  CERT_PATH,
  REPO_DIR,
  SECRETS_PATH,
  TMP_DIR,
  composeEnv,
} from "../support/env.ts";

const COMPOSE_FILE = path.join(REPO_DIR, "infra", "docker-compose.e2e.yml");

function compose(...args: string[]): void {
  const result = spawnSync("docker", ["compose", "-f", COMPOSE_FILE, ...args], {
    stdio: "inherit",
    env: { ...process.env, ...composeEnv() },
  });
  if (result.status !== 0) {
    throw new Error(`docker compose ${args.join(" ")} terminó con código ${result.status}`);
  }
}

/** El reino de desarrollo, con la dirección de esta pila, dos usuarios y fichas de acceso cortas. */
function javaHome(): string {
  if (process.env.JAVA_HOME) {
    return process.env.JAVA_HOME;
  }
  const settings = spawnSync("java", ["-XshowSettings:properties", "-version"], { encoding: "utf8" });
  const home = /^\s*java\.home = (.+)$/m.exec(`${settings.stderr}\n${settings.stdout}`)?.[1]?.trim();
  if (!home) {
    throw new Error("No se encontró el JDK: defina JAVA_HOME o ponga java en el PATH");
  }
  return home;
}

/** Certificado autofirmado, como los de las pruebas del backend: no sirve ante el SRI. */
function writeCertificate(): void {
  if (existsSync(CERT_PATH)) {
    return;
  }
  const keytool = path.join(javaHome(), "bin", "keytool");
  const env = { ...process.env };
  delete env.JAVA_TOOL_OPTIONS;
  execFileSync(
    keytool,
    [
      "-genkeypair", "-alias", "firma", "-keyalg", "RSA", "-keysize", "2048", "-sigalg", "SHA256withRSA",
      "-dname", "CN=Emisor de Pruebas, O=Pruebas, C=EC", "-startdate", "-1d", "-validity", "365",
      "-storetype", "PKCS12", "-keystore", CERT_PATH, "-storepass", CERT_PASSWORD,
      "-ext", "KeyUsage=digitalSignature",
    ],
    { stdio: ["ignore", "ignore", "inherit"], env },
  );
}

/** Los datos se pierden al bajar la pila, así que los secretos que los cifran duran lo mismo. */
function writeSecrets(): void {
  if (existsSync(SECRETS_PATH)) {
    return;
  }
  writeFileSync(
    SECRETS_PATH,
    JSON.stringify({
      masterKey: randomBytes(32).toString("base64"),
      sessionSecret: randomBytes(48).toString("base64"),
      jwtSecret: randomBytes(48).toString("base64"),
    }),
  );
}

async function up(): Promise<void> {
  mkdirSync(TMP_DIR, { recursive: true });
  writeCertificate();
  writeSecrets();
  compose("up", "-d", "--wait");
  console.log("Pila de pruebas en marcha.");
}

function down(): void {
  compose("down", "-v", "--remove-orphans");
  rmSync(TMP_DIR, { recursive: true, force: true });
}

const command = process.argv[2];
if (command === "up") {
  await up();
} else if (command === "down") {
  down();
} else {
  console.error("Uso: node scripts/stack.ts up|down");
  process.exit(1);
}
