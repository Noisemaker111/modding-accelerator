// RCON transport for AgentCraft's original bot, using server-check's assigned credentials.
import net from "node:net";

const packet = (id, type, body) => {
  const text = Buffer.from(body, "utf8"), data = Buffer.alloc(14 + text.length);
  data.writeInt32LE(10 + text.length, 0); data.writeInt32LE(id, 4); data.writeInt32LE(type, 8); text.copy(data, 12);
  return data;
};
const socket = net.connect(Number(process.env.RCON_PORT), "127.0.0.1");
let pending = Buffer.alloc(0), authenticated = false, response = "";
socket.setTimeout(5000);
socket.on("timeout", () => process.exit(1));
socket.on("error", () => process.exit(1));
socket.on("connect", () => socket.write(packet(1, 3, process.env.RCON_PASSWORD)));
socket.on("data", (data) => {
  pending = Buffer.concat([pending, data]);
  while (pending.length >= 4 && pending.length >= 4 + pending.readInt32LE(0)) {
    const length = pending.readInt32LE(0), id = pending.readInt32LE(4), body = pending.subarray(12, length + 2).toString("utf8");
    pending = pending.subarray(length + 4);
    if (!authenticated) {
      if (id === -1) process.exit(2);
      authenticated = true; socket.write(packet(2, 2, process.argv[2] ?? "list")); socket.write(packet(3, 2, ""));
    } else if (id === 2) response += body;
    else if (id === 3) { process.stdout.write(response.replace(/§./g, "")); socket.end(); process.exit(0); }
  }
});
