// Run AgentCraft's own bot unchanged except for its Docker-specific RCON and restart transports.
// The temporary module stays next to the original so Node resolves its existing dependencies.
import fs from "node:fs";
import path from "node:path";
import { spawn } from "node:child_process";
import { fileURLToPath } from "node:url";

const dir = path.resolve("test/e2e"), source = fs.readFileSync(path.join(dir, "bot.mjs"), "utf8");
const replacements = [
  ['execFileSync("docker", ["exec", CONTAINER, "rcon-cli", command]', 'execFileSync(process.execPath, [process.env.RCON_JS, command]'],
  ['execFileSync("docker", [...COMPOSE, "restart", "paper"]', 'execFileSync(process.execPath, [process.env.RESTART_JS]'],
];
let adapted = source;
for (const [before, after] of replacements) {
  if (adapted.split(before).length !== 2) throw new Error("AgentCraft bot transport changed; cannot run the original assertions safely.");
  adapted = adapted.replace(before, after);
}
const temp = path.join(dir, `.modaccel-bot-${process.pid}-${Date.now()}.mjs`);
const here = path.dirname(fileURLToPath(import.meta.url));
fs.writeFileSync(temp, adapted, { flag: "wx" });
try {
  const child = spawn(process.execPath, [temp], { stdio: "inherit", env: { ...process.env,
    RCON_JS: path.join(here, "agentcraft-rcon.mjs"), RESTART_JS: path.join(here, "restart-managed.mjs") } });
  const code = await new Promise((resolve, reject) => { child.on("error", reject); child.on("exit", (status) => resolve(status ?? 1)); });
  process.exitCode = code;
} finally { fs.unlinkSync(temp); }
