// Deja en marcha el SRI simulado, la API y la web con el entorno de las pruebas, para escribir o
// depurar pruebas sin esperar a que arranquen en cada ejecución: `npm run e2e` los reutiliza.
//   node scripts/servers.ts      (Ctrl+C los detiene)

import { spawn, type ChildProcess } from "node:child_process";
import path from "node:path";

import { E2E_DIR, FRONTEND_DIR, PORTS, REPO_DIR, apiEnv, apiJar, webEnv } from "../support/env.ts";

const children: ChildProcess[] = [];

function run(name: string, command: string, args: string[], cwd: string, env: Record<string, string>): void {
  const child = spawn(command, args, { cwd, env: { ...process.env, ...env }, stdio: "inherit" });
  child.on("exit", (code) => {
    console.error(`${name} terminó (código ${code})`);
    stop();
  });
  children.push(child);
}

function stop(): void {
  for (const child of children) {
    child.kill();
  }
  process.exit();
}

process.on("SIGINT", stop);
process.on("SIGTERM", stop);

// Sin intérprete de órdenes de por medio: así detenerlos detiene el proceso y no solo a quien lo lanzó.
const frontend = FRONTEND_DIR;
const next = path.join(frontend, "node_modules", "next", "dist", "bin", "next");

run("sri-simulado", process.execPath, ["simulator/server.mjs"], E2E_DIR, { SIMULATOR_PORT: String(PORTS.simulator) });
run("api", "java", ["-jar", apiJar()], REPO_DIR, apiEnv());
run("web", process.execPath, [next, "start", "-p", String(PORTS.web)], frontend, webEnv());
