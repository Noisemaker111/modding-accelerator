// Ask server-check to restart its managed server, preserving its world and plugin data.
import net from "node:net";

const socket = net.connect(Number(process.env.MODACCEL_RESTART_PORT), "127.0.0.1");
socket.setTimeout(180000);
socket.on("connect", () => socket.write(process.env.MODACCEL_RESTART_TOKEN + "\n"));
socket.on("data", (data) => { process.exit(data.toString().trim() === "ok" ? 0 : 1); });
socket.on("timeout", () => process.exit(1));
socket.on("error", () => process.exit(1));
socket.on("end", () => process.exit(1));
