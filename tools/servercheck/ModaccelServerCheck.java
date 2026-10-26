import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.regex.*;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.TranslationArgument;
import org.geysermc.mcprotocollib.auth.GameProfile;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.network.session.ClientNetworkSession;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftTypes;
import org.geysermc.mcprotocollib.protocol.data.game.Holder;
import org.geysermc.mcprotocollib.protocol.data.game.chat.ChatType;
import org.geysermc.mcprotocollib.protocol.data.game.chunk.ChunkSection;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.EntityMetadata;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponent;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.Filterable;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.WrittenBookContent;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockChangeEntry;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundDisconnectPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundRegistryDataPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.*;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.clientbound.ClientboundLoginDisconnectPacket;

/**
 * server-check, in its own JVM: runs one scenario file against a real, headless server with the project
 * loaded, and a protocol-level bot player (MCProtocolLib) that joins it. Every step is a line of the
 * scenario; every expect-* step must see the effect itself (chat the bot received, an entity or block the
 * server sent to the bot, an item in its inventory, a server log line, an RCON answer, an HTTP answer).
 * Secrets named ${secret:NAME} come from the environment or .modaccel/secrets.properties and are masked in
 * everything this writes. Usage: ModaccelServerCheck <spec.json>
 */
public final class ModaccelServerCheck {
    // ---------- spec, results, masking ----------
    static Map<String, Object> spec;
    static Path root, logDir;
    static final List<Map<String, Object>> steps = new ArrayList<>();
    static final Map<String, String> secrets = new LinkedHashMap<>();
    static final Map<String, Integer> ports = new LinkedHashMap<>();
    static final Map<String, String> serverEnv = new LinkedHashMap<>();
    static final Map<String, String> serverProperties = new LinkedHashMap<>();
    static final List<Process> helpers = new ArrayList<>();
    static final Map<String, Path> helperLogs = new LinkedHashMap<>();
    static String botName = "ModaccelBot";

    static String mask(String text) {
        if (text == null) return null;
        for (var secret : secrets.entrySet()) if (secret.getValue().length() >= 1) text = text.replace(secret.getValue(), "${secret:" + secret.getKey() + "}");
        return text;
    }

    public static void main(String[] args) throws Exception {
        spec = Json.read(Path.of(args[0]));
        root = Path.of(spec.get("root").toString());
        logDir = Path.of(spec.get("logDir").toString()); Files.createDirectories(logDir);
        Path resultFile = Path.of(spec.get("result").toString());
        Map<String, Object> result = new LinkedHashMap<>();
        long begin = System.nanoTime();
        Scenario scenario = null;
        try {
            scenario = Scenario.parse(Path.of(spec.get("scenario").toString()));
            result.put("scenario", scenario.file.toString());
            String missing = loadSecrets(scenario);
            if (missing != null) {
                result.put("ok", false); result.put("failed", true);
                result.put("reason", missing);
                result.put("steps", List.of());
            } else run(scenario, result);
        } catch (Throwable error) {
            result.put("ok", false); result.put("failed", true);
            result.put("reason", mask(error instanceof ScenarioError ? error.getMessage() : "server-check stopped: " + error));
            if (!(error instanceof ScenarioError)) result.put("trace", mask(stack(error)));
        } finally {
            try { shutdown(result); } catch (Throwable ignored) { }
            result.put("steps", steps);
            result.put("seconds", (System.nanoTime() - begin) / 1e9);
            result.put("helpers", helperLogs.entrySet().stream().map(e -> Json.map("name", e.getKey(), "log", e.getValue().toString())).toList());
            result.put("secretsUsed", new ArrayList<>(secrets.keySet()));
            Json.save(resultFile, result);
        }
        System.exit(0);
    }
    static String stack(Throwable error) { StringWriter out = new StringWriter(); error.printStackTrace(new PrintWriter(out)); return out.toString(); }

    /** Names in ${secret:NAME}: the environment first, then .modaccel/secrets.properties. Missing ones fail before anything starts. */
    static String loadSecrets(Scenario scenario) throws IOException {
        Properties file = new Properties();
        Path secretsFile = Path.of(spec.get("secretsFile").toString());
        if (Files.isRegularFile(secretsFile)) try (var in = Files.newBufferedReader(secretsFile, StandardCharsets.UTF_8)) { file.load(in); }
        List<String> missing = new ArrayList<>();
        for (String name : scenario.secretNames()) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) value = file.getProperty(name);
            if (value == null || value.isBlank()) missing.add(name);
            else secrets.put(name, value.strip());
        }
        if (missing.isEmpty()) return null;
        return "This scenario needs secret" + (missing.size() == 1 ? " " : "s ") + String.join(", ", missing) + ", and "
                + (missing.size() == 1 ? "it is" : "they are") + " not set, so nothing ran and nothing passed. Set "
                + (missing.size() == 1 ? "it" : "each") + " as an environment variable, or add a line NAME=value to " + secretsFile
                + " (keep that file out of git: .modaccel/ is modaccel's local folder).";
    }

    // ---------- the scenario ----------
    static final class ScenarioError extends Exception { ScenarioError(String message) { super(message); } }
    record Line(int number, String text) { }
    static final class Scenario {
        Path file; List<Line> lines = new ArrayList<>();
        static Scenario parse(Path file) throws IOException {
            Scenario s = new Scenario(); s.file = file;
            List<String> raw = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < raw.size(); i++) {
                String line = raw.get(i).strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                s.lines.add(new Line(i + 1, line));
            }
            return s;
        }
        Set<String> secretNames() {
            Set<String> names = new LinkedHashSet<>();
            Matcher m = Pattern.compile("\\$\\{secret:([A-Za-z0-9_.-]+)}").matcher("");
            for (Line l : lines) { m.reset(l.text()); while (m.find()) names.add(m.group(1)); }
            return names;
        }
    }

    static final Set<String> SERVER_STEPS = Set.of("join", "rcon", "expect-log", "expect-no-log", "expect-rcon", "start-server");
    static final Set<String> BOT_STEPS = Set.of("say", "command", "expect-chat", "expect-no-chat", "expect-title", "expect-actionbar", "expect-entity", "expect-no-entity",
            "expect-block", "expect-inventory", "expect-no-inventory", "expect-position", "leave");
    static final Set<String> VERBS;
    static {
        Set<String> v = new HashSet<>(Set.of("needs", "server-env", "server-property", "background", "wait-port", "wait", "expect-http", "bot-name"));
        v.addAll(SERVER_STEPS); v.addAll(BOT_STEPS); VERBS = v;
    }

    static Server server; static Bot bot;
    /** Where the bot connects: the server itself, or ViaProxy in front of it when the server runs another version. */
    static int joinPort;
    static long actionMark; static int chatMark, logMark; static String lastAction = "the start";
    static int assertions;

    static void run(Scenario scenario, Map<String, Object> result) throws Exception {
        for (Line line : scenario.lines) {
            String verb = line.text().split("\\s+", 2)[0];
            if (!VERBS.contains(verb)) throw new ScenarioError(scenario.file.getFileName() + " line " + line.number() + ": unknown step \"" + verb + "\". Steps: " + String.join(", ", new TreeSet<>(VERBS)) + ".");
        }
        for (Line line : scenario.lines) {
            String verb = line.text().split("\\s+", 2)[0];
            String rest = line.text().length() > verb.length() ? line.text().substring(verb.length()).strip() : "";
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("line", line.number()); step.put("step", line.text());
            steps.add(step);
            long start = System.nanoTime();
            try {
                if ((SERVER_STEPS.contains(verb) || BOT_STEPS.contains(verb)) && server == null) startServer(step);
                if (BOT_STEPS.contains(verb) && !verb.equals("leave") && bot == null) throw new ScenarioError("\"" + verb + "\" needs the bot in the game: add a join step before it.");
                if (BOT_STEPS.contains(verb) && bot != null && bot.disconnectReason != null && !verb.equals("leave")) throw new ScenarioError("The bot is no longer in the game: the server disconnected it (" + bot.disconnectReason + "). Nothing after that can be checked.");
                String details = execute(verb, substitute(rest), step);
                step.put("status", verb.startsWith("expect-") ? "pass" : "done");
                if (details != null) step.put("details", mask(details));
            } catch (ScenarioError error) {
                ScenarioError failure = error;
                if (bot != null && bot.disconnectReason != null && !error.getMessage().contains("disconnected"))
                    failure = new ScenarioError(error.getMessage() + "\nThe bot had been disconnected by then: " + bot.disconnectReason);
                step.put("status", "fail"); step.put("details", mask(failure.getMessage()));
                step.put("seconds", (System.nanoTime() - start) / 1e9);
                result.put("ok", false); result.put("failed", true);
                result.put("reason", mask("line " + line.number() + ": " + line.text() + "\n  " + failure.getMessage().replace("\n", "\n  ")));
                return;
            }
            step.put("seconds", (System.nanoTime() - start) / 1e9);
            if (server != null && !server.process.isAlive() && !verb.equals("leave")) {
                result.put("ok", false); result.put("failed", true);
                result.put("reason", "The server exited during line " + line.number() + " (" + line.text() + "). Last log lines:\n  " + String.join("\n  ", server.tail(12)));
                return;
            }
        }
        if (assertions == 0) {
            result.put("ok", false); result.put("failed", true);
            result.put("reason", "The scenario has no expect step, so it proves nothing. Add at least one expect-* line that checks the project's own effect.");
            return;
        }
        result.put("ok", true); result.put("failed", false);
    }

    /** ${port:NAME} a free local port, the same one for each NAME; ${bot} the bot's name; ${root}; ${secret:NAME}; ${server.port}. */
    static String substitute(String text) throws IOException {
        Matcher m = Pattern.compile("\\$\\{([a-z.]+)(?::([A-Za-z0-9_.-]+))?}").matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String kind = m.group(1), name = m.group(2), value;
            switch (kind) {
                case "port" -> { Integer port = ports.get(name); if (port == null) ports.put(name, port = freePort()); value = port.toString(); }
                case "secret" -> value = secrets.get(name);
                case "bot" -> value = botName;
                case "root" -> value = root.toString();
                case "server.port" -> value = server == null ? "?" : Integer.toString(server.port);
                default -> value = m.group();
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value == null ? m.group() : value));
        }
        m.appendTail(out);
        return out.toString();
    }
    static int freePort() throws IOException { try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { return s.getLocalPort(); } }

    /** during: the time was written "for Ns" (true the whole time), not "within Ns" (true by then). */
    record Expect(Pattern pattern, String source, String rest, double seconds, boolean during) { }
    /** "... ~regex~ ... within 10s": the regex and what is left; seconds is the within/for value or the fallback. */
    static Expect expect(String rest, double fallback, boolean needsRegex) throws ScenarioError { return expect(rest, fallback, needsRegex, false); }
    /** noRegex: tildes are block coordinates (~ ~-1 ~), not a pattern. */
    static Expect expect(String rest, double fallback, boolean needsRegex, boolean noRegex) throws ScenarioError {
        double seconds = fallback; boolean during = false;
        Matcher time = Pattern.compile("\\s+(within|for)\\s+(\\d+(?:\\.\\d+)?)\\s*(ms|s|m)?\\s*$").matcher(rest);
        if (time.find()) {
            seconds = Double.parseDouble(time.group(2)) * ("ms".equals(time.group(3)) ? 0.001 : "m".equals(time.group(3)) ? 60 : 1);
            during = time.group(1).equals("for"); rest = rest.substring(0, time.start()).strip();
        }
        int a = noRegex ? -1 : rest.indexOf('~'), b = noRegex ? -1 : rest.lastIndexOf('~');
        if (a < 0 || b <= a) {
            if (needsRegex) throw new ScenarioError("Put the expected text between tildes, e.g. ~is on it~ (a Java regular expression).");
            return new Expect(null, null, rest, seconds, during);
        }
        String source = rest.substring(a + 1, b);
        try { return new Expect(Pattern.compile(source), source, (rest.substring(0, a) + " " + rest.substring(b + 1)).strip(), seconds, during); }
        catch (PatternSyntaxException bad) { throw new ScenarioError("~" + source + "~ is not a valid regular expression: " + bad.getDescription()); }
    }

    static void markAction(String what) {
        actionMark = System.nanoTime(); lastAction = what;
        if (bot != null) chatMark = bot.chat.size();
        if (server != null) logMark = server.lines.size();
    }

    static String execute(String verb, String rest, Map<String, Object> step) throws Exception {
        switch (verb) {
            case "needs" -> {
                for (String program : rest.split("\\s+")) if (!onPath(program))
                    throw new ScenarioError("This scenario needs " + program + " on PATH, and it is not there, so nothing ran. Install " + program + " (or put it on PATH) and run server-check again.");
                return "found " + rest;
            }
            case "bot-name" -> { if (bot != null) throw new ScenarioError("bot-name must come before join."); botName = rest.strip(); return null; }
            case "server-env" -> {
                if (server != null) throw new ScenarioError("server-env must come before the server starts (before the first join, rcon or expect-log).");
                int eq = rest.indexOf('='); if (eq <= 0) throw new ScenarioError("Write server-env NAME=value.");
                serverEnv.put(rest.substring(0, eq).strip(), rest.substring(eq + 1).strip()); return null;
            }
            case "server-property" -> {
                if (server != null) throw new ScenarioError("server-property must come before the server starts.");
                int eq = rest.indexOf('='); if (eq <= 0) throw new ScenarioError("Write server-property key=value.");
                serverProperties.put(rest.substring(0, eq).strip(), rest.substring(eq + 1).strip()); return null;
            }
            case "background" -> {
                int colon = rest.indexOf(':'); if (colon <= 0) throw new ScenarioError("Write background NAME: command and arguments.");
                String name = rest.substring(0, colon).strip(); List<String> command = words(rest.substring(colon + 1).strip());
                Path log = logDir.resolve("helper-" + name + ".log");
                ProcessBuilder builder = new ProcessBuilder(resolveProgram(command)).directory(root.toFile()).redirectErrorStream(true);
                Process process;
                try { process = builder.start(); } catch (IOException e) { throw new ScenarioError("Could not start " + command.get(0) + ": " + e.getMessage()); }
                helpers.add(process); helperLogs.put(name, log);
                pump(process.getInputStream(), log, null);
                return "started " + name + " (pid " + process.pid() + "), log " + log;
            }
            case "wait-port" -> {
                Expect e = expect(rest, 10, false); int port = Integer.parseInt(e.rest().strip());
                long deadline = System.nanoTime() + (long) (e.seconds() * 1e9);
                while (System.nanoTime() < deadline) {
                    try (Socket s = new Socket()) { s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 250); return "port " + port + " is open"; }
                    catch (IOException closed) { Thread.sleep(100); }
                }
                throw new ScenarioError("Nothing listened on port " + port + " within " + secs(e.seconds()) + "." + helperTails());
            }
            case "wait" -> { Thread.sleep((long) (expect("x for " + rest.replaceFirst("^(for\\s+)", ""), 1, false).seconds() * 1000)); return null; }
            case "start-server" -> { return "server is up"; }
            case "join" -> {
                if (!rest.isBlank()) botName = rest.strip();
                if (bot != null) throw new ScenarioError("The bot is already in the game.");
                bot = Bot.join(botName, joinPort, 60);
                markAction("join");
                return "joined as " + botName + " at " + bot.positionText() + " (protocol " + bot.protocolVersion + ")";
            }
            case "leave" -> { if (bot != null) bot.leave(); bot = null; return null; }
            case "say" -> { markAction("say " + rest); bot.say(rest); return null; }
            case "command" -> { String c = rest.startsWith("/") ? rest.substring(1) : rest; markAction("command /" + c); bot.command(c); return null; }
            case "rcon" -> { markAction("rcon " + rest); String out = server.rcon(rest); step.put("output", mask(out)); return out.isBlank() ? "(no output)" : out; }
            case "expect-rcon" -> {
                Expect e = expect(rest, 10, true); assertions++;
                String command = e.rest(); if (command.isBlank()) throw new ScenarioError("Write expect-rcon COMMAND ~regex~ [within Ns].");
                String last = ""; long deadline = System.nanoTime() + (long) (e.seconds() * 1e9);
                do { last = server.rcon(command); if (e.pattern().matcher(last).find()) return "rcon " + command + " -> " + last; Thread.sleep(200); } while (System.nanoTime() < deadline);
                throw new ScenarioError("Expected the RCON answer to /" + command + " to match ~" + e.source() + "~ within " + secs(e.seconds()) + ".\n  It answered: " + (last.isBlank() ? "(nothing)" : last));
            }
            case "expect-log" -> {
                Expect e = expect(rest, 10, true); assertions++;
                String found = waitFor(e.seconds(), () -> { synchronized (server.lines) { for (int i = logMark; i < server.lines.size(); i++) if (e.pattern().matcher(server.lines.get(i)).find()) return server.lines.get(i); } return null; });
                if (found != null) return found;
                throw new ScenarioError("Expected a server log line matching ~" + e.source() + "~ within " + secs(e.seconds()) + " of " + lastAction + ".\n  Server log since then (last lines):\n  " + String.join("\n  ", server.since(logMark, 10)));
            }
            case "expect-no-log" -> {
                Expect e = expect(rest, 0, true); assertions++;
                if (e.seconds() > 0) Thread.sleep((long) (e.seconds() * 1000));
                synchronized (server.lines) { for (String l : server.lines) if (e.pattern().matcher(l).find()) throw new ScenarioError("Expected no server log line matching ~" + e.source() + "~, but the server logged:\n  " + l); }
                return null;
            }
            case "expect-http" -> {
                Expect e = expect(rest, 10, true); assertions++;
                String url = e.rest().replaceFirst("^GET\\s+", "").strip();
                HttpClient client = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(3)).build();
                String last = ""; long deadline = System.nanoTime() + (long) (e.seconds() * 1e9);
                do {
                    try { last = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(java.time.Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString()).body(); }
                    catch (IOException unreachable) { last = "(no answer: " + unreachable.getMessage() + ")"; }
                    if (e.pattern().matcher(last).find()) return "GET " + url + " matched";
                    Thread.sleep(250);
                } while (System.nanoTime() < deadline);
                throw new ScenarioError("Expected GET " + url + " to answer with ~" + e.source() + "~ within " + secs(e.seconds()) + ".\n  It answered: " + clip(last, 600));
            }
            case "expect-chat", "expect-title", "expect-actionbar" -> {
                Expect e = expect(rest, 10, true); assertions++;
                String kind = verb.substring("expect-".length());
                String found = waitFor(e.seconds(), () -> bot.findChat(chatMark, kind, e.pattern()));
                if (found != null) return found;
                throw new ScenarioError("Expected " + (kind.equals("chat") ? "a chat line" : kind.equals("title") ? "a title" : "an action bar") + " matching ~" + e.source() + "~ within " + secs(e.seconds())
                        + " of " + lastAction + ".\n  " + bot.chatSince(chatMark, 12));
            }
            case "expect-no-chat" -> {
                Expect e = expect(rest, 3, true); assertions++;
                String found = waitFor(e.seconds(), () -> bot.findChat(chatMark, "chat", e.pattern()));
                if (found != null) throw new ScenarioError("Expected no chat line matching ~" + e.source() + "~ for " + secs(e.seconds()) + " after " + lastAction + ", but the bot got:\n  " + found);
                return null;
            }
            case "expect-entity", "expect-no-entity" -> {
                boolean negative = verb.equals("expect-no-entity");
                Expect e = expect(rest, negative ? 3 : 10, false); assertions++;
                List<String> words = new ArrayList<>(Arrays.asList(e.rest().split("\\s+")));
                String type = words.isEmpty() ? "" : words.remove(0);
                if (type.isEmpty()) throw new ScenarioError("Write expect-entity TYPE [named ~regex~] [near BLOCKS] [within Ns].");
                double near = Double.MAX_VALUE;
                for (int i = 0; i < words.size(); i++) if (words.get(i).equals("near") && i + 1 < words.size()) near = Double.parseDouble(words.get(i + 1));
                String want = (type.contains(":") ? type : "minecraft:" + type).toLowerCase(Locale.ROOT);
                double radius = near;
                Supplier<String> probe = () -> bot.findEntity(want, e.pattern(), radius);
                String what = want + (e.pattern() == null ? "" : " named ~" + e.source() + "~") + (near == Double.MAX_VALUE ? "" : " within " + fmt(near) + " blocks of the bot");
                if (negative) {
                    // "for Ns": none may appear in that time; "within Ns": any there must be gone by then.
                    String seen = e.during() ? waitFor(e.seconds(), probe) : waitGone(e.seconds(), probe);
                    if (seen != null) throw new ScenarioError("Expected no " + what + (e.during() ? " for " : " within ") + secs(e.seconds()) + ", but the bot sees " + seen);
                    return null;
                }
                String found = waitFor(e.seconds(), probe);
                if (found != null) return found;
                throw new ScenarioError("Expected a " + what + " within " + secs(e.seconds()) + " of " + lastAction + ".\n  The bot sees " + bot.entitiesText(want, 10));
            }
            case "expect-block" -> {
                Expect e = expect(rest, 10, false, true); assertions++;
                String[] w = e.rest().split("\\s+");
                if (w.length < 4) throw new ScenarioError("Write expect-block X Y Z BLOCK [within Ns]; X Y Z may be ~ or ~N relative to the bot.");
                int[] at = bot.blockCoordinates(w[0], w[1], w[2]);
                String want = w[3].contains(":") ? w[3] : "minecraft:" + w[3];
                String[] last = {null};
                String found = waitFor(e.seconds(), () -> { last[0] = bot.blockAt(at[0], at[1], at[2]); return last[0] != null && blockMatches(last[0], want) ? last[0] : null; });
                if (found != null) return "block at " + at[0] + " " + at[1] + " " + at[2] + " is " + found;
                throw new ScenarioError("Expected " + want + " at " + at[0] + " " + at[1] + " " + at[2] + " within " + secs(e.seconds()) + ", but the bot sees " + (last[0] == null ? "no loaded chunk there" : last[0]) + ".");
            }
            case "expect-inventory", "expect-no-inventory" -> {
                boolean negative = verb.equals("expect-no-inventory");
                Expect e = expect(rest, negative ? 2 : 10, false); assertions++;
                List<String> words = new ArrayList<>(Arrays.asList(e.rest().split("\\s+")));
                String item = words.isEmpty() ? "" : words.remove(0);
                if (item.isEmpty()) throw new ScenarioError("Write expect-inventory ITEM [count N] [~text~] [within Ns].");
                int count = 1;
                for (int i = 0; i < words.size(); i++) if (words.get(i).equals("count") && i + 1 < words.size()) count = Integer.parseInt(words.get(i + 1));
                String want = item.contains(":") ? item : "minecraft:" + item; int need = count;
                Supplier<String> probe = () -> bot.findItem(want, need, e.pattern());
                String found = negative && !e.during() ? waitGone(e.seconds(), probe) : waitFor(e.seconds(), probe);
                String what = (need > 1 ? need + " x " : "") + want + (e.pattern() == null ? "" : " with text ~" + e.source() + "~");
                if (negative) { if (found != null) throw new ScenarioError("Expected no " + what + " in the bot's inventory, but it holds " + found); return null; }
                if (found != null) return found;
                throw new ScenarioError("Expected " + what + " in the bot's inventory within " + secs(e.seconds()) + " of " + lastAction + ".\n  It holds: " + bot.inventoryText());
            }
            case "expect-position" -> {
                Expect e = expect(rest, 5, false); assertions++;
                String[] w = e.rest().split("\\s+");
                if (w.length < 3) throw new ScenarioError("Write expect-position X Y Z [tolerance T] [within Ns].");
                double x = Double.parseDouble(w[0]), y = Double.parseDouble(w[1]), z = Double.parseDouble(w[2]);
                double tolerance = w.length >= 5 && w[3].equals("tolerance") ? Double.parseDouble(w[4]) : 0.5;
                String found = waitFor(e.seconds(), () -> Math.abs(bot.x - x) <= tolerance && Math.abs(bot.y - y) <= tolerance && Math.abs(bot.z - z) <= tolerance ? bot.positionText() : null);
                if (found != null) return "at " + found;
                throw new ScenarioError("Expected the bot within " + fmt(tolerance) + " of " + fmt(x) + " " + fmt(y) + " " + fmt(z) + " within " + secs(e.seconds()) + ", but it is at " + bot.positionText() + ".");
            }
            default -> throw new ScenarioError("Unknown step " + verb);
        }
    }

    static String waitFor(double seconds, Supplier<String> probe) throws InterruptedException {
        long deadline = System.nanoTime() + (long) (seconds * 1e9);
        while (true) {
            String found = probe.get();
            if (found != null) return found;
            if (System.nanoTime() >= deadline) return null;
            if (bot != null && bot.disconnectReason != null) return null;
            Thread.sleep(50);
        }
    }
    /** minecraft:oak_stairs[facing=east] matches any oak_stairs state with facing=east; a bare name matches every state. */
    static boolean blockMatches(String state, String want) {
        int a = state.indexOf('['), b = want.indexOf('[');
        String stateName = a < 0 ? state : state.substring(0, a), wantName = b < 0 ? want : want.substring(0, b);
        if (!stateName.equals(wantName)) return false;
        if (b < 0) return true;
        Set<String> have = a < 0 ? Set.of() : new HashSet<>(Arrays.asList(state.substring(a + 1, state.length() - 1).split(",")));
        for (String p : want.substring(b + 1).replace("]", "").split(",")) if (!p.isBlank() && !have.contains(p.strip())) return false;
        return true;
    }
    /** null once the probe finds nothing; what it still finds when the time runs out. */
    static String waitGone(double seconds, Supplier<String> probe) throws InterruptedException {
        long deadline = System.nanoTime() + (long) (seconds * 1e9);
        while (true) {
            String found = probe.get();
            if (found == null || System.nanoTime() >= deadline) return found;
            Thread.sleep(50);
        }
    }
    static String secs(double s) { return fmt(s) + "s"; }
    static String fmt(double d) { return d == Math.rint(d) ? Long.toString((long) d) : String.format(Locale.ROOT, "%.1f", d); }
    static String clip(String s, int n) { return s.length() <= n ? s : s.substring(0, n) + "..."; }
    static String helperTails() {
        StringBuilder b = new StringBuilder();
        for (var h : helperLogs.entrySet()) {
            try { List<String> l = Files.readAllLines(h.getValue(), StandardCharsets.UTF_8); b.append("\n  ").append(h.getKey()).append(" log: ").append(String.join("\n    ", l.subList(Math.max(0, l.size() - 6), l.size()))); }
            catch (IOException ignored) { }
        }
        return b.toString();
    }

    /** Split a command line on spaces, keeping "double" or 'single' quoted words together. */
    static List<String> words(String line) {
        List<String> out = new ArrayList<>(); StringBuilder w = new StringBuilder(); char quote = 0; boolean any = false;
        for (char c : line.toCharArray()) {
            if (quote != 0) { if (c == quote) quote = 0; else w.append(c); }
            else if (c == '"' || c == '\'') { quote = c; any = true; }
            else if (Character.isWhitespace(c)) { if (w.length() > 0 || any) { out.add(w.toString()); w.setLength(0); any = false; } }
            else w.append(c);
        }
        if (w.length() > 0 || any) out.add(w.toString());
        return out;
    }
    static final boolean WINDOWS = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    static boolean onPath(String program) { return locate(program) != null || starts(program); }
    /** Whether the OS itself can start the program (Windows resolves names a PATH walk can miss). */
    static boolean starts(String program) {
        try {
            Process p = new ProcessBuilder(program, "--version").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly();
            return true;
        } catch (IOException | InterruptedException e) { return false; }
    }
    static Path locate(String program) {
        String path = System.getenv("PATH");
        if (path == null) path = System.getenv("Path");
        for (String dir : (path == null ? "" : path).split(File.pathSeparator)) {
            dir = dir.strip();
            if (dir.length() > 1 && dir.startsWith("\"") && dir.endsWith("\"")) dir = dir.substring(1, dir.length() - 1);
            if (dir.isBlank()) continue;
            for (String ext : WINDOWS ? List.of(".exe", ".cmd", ".bat", "") : List.of("")) {
                try {
                    Path p = Path.of(dir, program + ext);
                    if (Files.isRegularFile(p) && (WINDOWS || Files.isExecutable(p))) return p;
                } catch (java.nio.file.InvalidPathException ignored) { }
            }
        }
        return null;
    }
    static List<String> resolveProgram(List<String> command) {
        List<String> out = new ArrayList<>(command);
        Path found = locate(command.get(0));
        if (found != null) out.set(0, found.toString());
        if (WINDOWS && found != null && (found.toString().endsWith(".cmd") || found.toString().endsWith(".bat"))) { out.add(0, "/c"); out.add(0, "cmd.exe"); }
        return out;
    }
    /** Copy a stream to a log, masking secrets, and optionally into a list of lines. */
    static Thread pump(InputStream in, Path log, List<String> lines) {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                 Writer out = Files.newBufferedWriter(log, StandardCharsets.UTF_8)) {
                for (String line; (line = reader.readLine()) != null; ) {
                    line = mask(line.replaceAll("\u001B\\[[0-9;]*[A-Za-z]", ""));
                    out.write(line); out.write('\n'); out.flush();
                    if (lines != null) synchronized (lines) { lines.add(line); }
                }
            } catch (IOException ignored) { }
        }, "pump-" + log.getFileName());
        t.setDaemon(true); t.start(); return t;
    }

    // ---------- the server ----------
    static void startServer(Map<String, Object> step) throws Exception {
        long begin = System.nanoTime();
        server = new Server(Json.object(spec.get("server")));
        joinPort = server.port;
        Map<String, Object> started = new LinkedHashMap<>();
        if (spec.get("proxy") instanceof Map<?, ?> proxy) {
            long proxyBegin = System.nanoTime();
            joinPort = viaProxy(Json.object(proxy), server.port);
            started.put("proxySeconds", (System.nanoTime() - proxyBegin) / 1e9);
        }
        started.put("seconds", (System.nanoTime() - begin) / 1e9); started.put("port", server.port); started.put("log", server.log.toString());
        step.put("serverStart", started);
        markAction("the server start");
        logMark = 0;
    }

    /**
     * ViaProxy between the bot and a server of another version: it listens on a free local port, translates to
     * targetVersion, offline and unsigned like the test server. Ready once it says it bound the port and the port answers.
     */
    static int viaProxy(Map<String, Object> proxy, int target) throws Exception {
        int port = freePort();
        Path dir = Path.of(proxy.get("dir").toString()); Files.createDirectories(dir);
        List<String> command = new ArrayList<>();
        for (Object o : Json.array(proxy.get("command"))) command.add(o.toString());
        command.addAll(List.of("--bind-address", "127.0.0.1:" + port, "--target-address", "127.0.0.1:" + target, "--target-version", proxy.get("targetVersion").toString()));
        ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true);
        builder.environment().put("VP_RUN_DIR", dir.toString());
        Path log = logDir.resolve("viaproxy.log");
        Process process = builder.start();
        helpers.add(process); helperLogs.put("viaproxy", log);
        List<String> lines = new ArrayList<>();
        pump(process.getInputStream(), log, lines);
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (System.nanoTime() < deadline) {
            boolean bound; synchronized (lines) { bound = lines.stream().anyMatch(l -> l.contains("Binding proxy server to")); }
            if (bound) try (Socket s = new Socket()) { s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 250); return port; } catch (IOException notYet) { }
            if (!process.isAlive()) break;
            Thread.sleep(100);
        }
        List<String> tail; synchronized (lines) { tail = new ArrayList<>(lines.subList(Math.max(0, lines.size() - 15), lines.size())); }
        throw new ScenarioError("ViaProxy, which lets the bot join a Minecraft " + proxy.get("targetVersion") + " server, " + (process.isAlive() ? "did not open its port in 60 seconds" : "exited (exit " + process.exitValue() + ")")
                + ". Last log lines:\n  " + String.join("\n  ", tail) + "\n  Log: " + log);
    }

    static final class Server {
        final Process process; final Path log, dir; final int port, rconPort; final String rconPassword = UUID.randomUUID().toString().replace("-", "");
        final List<String> lines = new ArrayList<>();
        Server(Map<String, Object> s) throws Exception {
            dir = Path.of(s.get("dir").toString());
            port = freePort(); rconPort = freePort();
            Files.createDirectories(dir);
            // A fresh, throwaway world and plugin folder every run; Paper's patched jar and libraries stay for the next one.
            for (String name : List.of("world", "world_nether", "world_the_end", "plugins", "logs", "crash-reports")) deleteTree(dir.resolve(name));
            Path plugins = dir.resolve("plugins"); Files.createDirectories(plugins);
            for (Object jar : Json.array(s.get("plugins"))) { Path p = Path.of(jar.toString()); Files.copy(p, plugins.resolve(p.getFileName()), StandardCopyOption.REPLACE_EXISTING); }
            Map<String, String> props = new LinkedHashMap<>();
            props.put("server-ip", "127.0.0.1"); props.put("server-port", Integer.toString(port));
            props.put("online-mode", "false"); props.put("enforce-secure-profile", "false"); props.put("prevent-proxy-connections", "false");
            props.put("enable-rcon", "true"); props.put("rcon.port", Integer.toString(rconPort)); props.put("rcon.password", rconPassword); props.put("broadcast-rcon-to-ops", "false");
            props.put("level-seed", "modaccel"); props.put("level-type", flatWorld(Json.text(s, "minecraft", ""))); props.put("generate-structures", "false");
            props.put("gamemode", "creative"); props.put("difficulty", "peaceful"); props.put("spawn-protection", "0"); props.put("allow-flight", "true");
            props.put("view-distance", "4"); props.put("simulation-distance", "4"); props.put("max-players", "4"); props.put("motd", "modaccel server-check");
            props.put("white-list", "false"); props.put("enforce-whitelist", "false"); props.put("enable-status", "true"); props.put("sync-chunk-writes", "false"); props.put("network-compression-threshold", "-1");
            for (var e : serverProperties.entrySet()) props.put(e.getKey(), e.getValue().replace(":", "\\:"));
            StringBuilder text = new StringBuilder("# Written by modaccel server-check for a throwaway local test server.\n");
            props.forEach((k, v) -> text.append(k).append('=').append(v).append('\n'));
            Files.writeString(dir.resolve("server.properties"), text, StandardCharsets.UTF_8);
            // EULA accepted by modaccel only for this throwaway, offline, localhost-only test server.
            Files.writeString(dir.resolve("eula.txt"), "# Accepted by modaccel server-check for a throwaway local test server (offline mode, 127.0.0.1 only).\neula=true\n", StandardCharsets.UTF_8);
            List<String> command = new ArrayList<>();
            for (Object o : Json.array(s.get("command"))) command.add(o.toString());
            ProcessBuilder builder = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true);
            builder.environment().putAll(serverEnv);
            log = logDir.resolve("server.log");
            process = builder.start();
            pump(process.getInputStream(), log, lines);
            int timeout = ((Number) s.getOrDefault("startSeconds", 240)).intValue();
            long deadline = System.nanoTime() + timeout * 1_000_000_000L;
            while (true) {
                synchronized (lines) { if (lines.stream().anyMatch(l -> l.contains("Done (") && l.contains("For help"))) break; }
                if (!process.isAlive()) throw new ScenarioError("The server exited before it was ready (exit " + process.exitValue() + "). Last log lines:\n  " + String.join("\n  ", tail(15)) + "\n  Log: " + log);
                if (System.nanoTime() > deadline) throw new ScenarioError("The server was not ready in " + timeout + " seconds. Last log lines:\n  " + String.join("\n  ", tail(15)) + "\n  Log: " + log);
                Thread.sleep(100);
            }
            synchronized (lines) {
                for (String l : lines) if (l.contains("Error occurred while enabling") || l.contains("Could not load plugin") || l.contains("Could not load 'plugins"))
                    throw new ScenarioError("The server started, but a plugin did not load or enable:\n  " + l + "\n  Log: " + log);
            }
        }
        /** 1.19 named world presets minecraft:flat; older servers call the flat world type flat. */
        static String flatWorld(String minecraft) {
            String[] p = minecraft.split("\\.");
            try { int major = Integer.parseInt(p[0]), minor = p.length > 1 ? Integer.parseInt(p[1]) : 0; return major > 1 || minor >= 19 ? "minecraft\\:flat" : "flat"; }
            catch (NumberFormatException unknown) { return "minecraft\\:flat"; }
        }
        List<String> tail(int n) { synchronized (lines) { return new ArrayList<>(lines.subList(Math.max(0, lines.size() - n), lines.size())); } }
        List<String> since(int mark, int n) { synchronized (lines) { List<String> l = lines.subList(Math.min(mark, lines.size()), lines.size()); return l.isEmpty() ? List.of("(nothing)") : new ArrayList<>(l.subList(Math.max(0, l.size() - n), l.size())); } }

        Socket rcon; DataInputStream rin; OutputStream rout; int rid = 1;
        synchronized String rcon(String command) throws Exception {
            if (rcon == null) {
                rcon = new Socket(); rcon.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), rconPort), 5000); rcon.setSoTimeout(15000);
                rin = new DataInputStream(new BufferedInputStream(rcon.getInputStream())); rout = rcon.getOutputStream();
                send(3, rconPassword); int[] answer = new int[1]; read(answer);
                if (answer[0] == -1) throw new ScenarioError("The server refused modaccel's RCON password.");
            }
            int id = send(2, command.startsWith("/") ? command.substring(1) : command);
            int[] answer = new int[1]; String body = read(answer);
            return body.replaceAll("§.", "").strip();
        }
        int send(int type, String body) throws IOException {
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            ByteBuffer b = ByteBuffer.allocate(14 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
            int id = rid++;
            b.putInt(10 + payload.length).putInt(id).putInt(type).put(payload).put((byte) 0).put((byte) 0);
            rout.write(b.array()); rout.flush(); return id;
        }
        String read(int[] id) throws IOException {
            byte[] head = new byte[4]; rin.readFully(head);
            int length = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getInt();
            byte[] rest = new byte[length]; rin.readFully(rest);
            ByteBuffer b = ByteBuffer.wrap(rest).order(ByteOrder.LITTLE_ENDIAN);
            id[0] = b.getInt(); b.getInt();
            return new String(rest, 8, Math.max(0, length - 10), StandardCharsets.UTF_8);
        }
        void stop() {
            try { if (rcon != null) rcon.close(); } catch (IOException ignored) { }
            if (!process.isAlive()) return;
            try { process.getOutputStream().write("stop\n".getBytes(StandardCharsets.UTF_8)); process.getOutputStream().flush(); } catch (IOException ignored) { }
            try { if (!process.waitFor(30, TimeUnit.SECONDS)) { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); } }
            catch (InterruptedException e) { process.destroyForcibly(); }
        }
    }

    static void shutdown(Map<String, Object> result) {
        if (bot != null) try { bot.leave(); } catch (Throwable ignored) { }
        long stopBegin = System.nanoTime();
        if (server != null) {
            server.stop();
            result.put("serverLog", server.log.toString());
            maskServerFiles(server.dir);
            result.put("serverStopSeconds", (System.nanoTime() - stopBegin) / 1e9);
        }
        for (Process p : helpers) { p.descendants().forEach(ProcessHandle::destroyForcibly); p.destroyForcibly(); }
    }
    /**
     * Paper writes its own logs/latest.log and plugins write their data folders, outside modaccel's masked copy:
     * with a secret in play, mask every text file the server left, and drop compressed old logs we cannot rewrite.
     */
    static void maskServerFiles(Path dir) {
        if (secrets.isEmpty()) return;
        for (String sub : List.of("logs", "crash-reports", "plugins")) {
            Path folder = dir.resolve(sub);
            if (!Files.isDirectory(folder)) continue;
            try (var walk = Files.walk(folder)) {
                for (Path f : walk.filter(Files::isRegularFile).toList()) {
                    String name = f.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (name.endsWith(".gz")) { Files.deleteIfExists(f); continue; }
                    if (!name.matches(".*\\.(log|txt|yml|yaml|json|properties|conf|toml|cfg)$")) continue;
                    try {
                        String text = Files.readString(f, StandardCharsets.UTF_8), masked = mask(text);
                        if (!masked.equals(text)) Files.writeString(f, masked, StandardCharsets.UTF_8);
                    } catch (IOException notText) { Files.deleteIfExists(f); }
                }
            } catch (IOException | UncheckedIOException ignored) { }
        }
    }
    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (var walk = Files.walk(p)) { for (Path q : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(q); }
    }

    // ---------- names: blocks, items, translations ----------
    static Map<Integer, String> blockStates, items;
    static Map<String, String> lang;
    static int blockBits = 15, biomeBits = 7;
    static void loadIds() throws IOException {
        if (blockStates != null) return;
        blockStates = new HashMap<>(); items = new HashMap<>(); lang = new HashMap<>();
        Object idsPath = spec.get("ids");
        if (idsPath != null && Files.isRegularFile(Path.of(idsPath.toString()))) {
            var ids = Json.read(Path.of(idsPath.toString()));
            for (var e : Json.object(ids.get("blocks")).entrySet()) blockStates.put(Integer.parseInt(e.getKey()), e.getValue().toString());
            for (var e : Json.object(ids.get("items")).entrySet()) items.put(Integer.parseInt(e.getKey()), e.getValue().toString());
            blockBits = bits(blockStates.size()); biomeBits = bits(((Number) ids.getOrDefault("biomes", 64)).intValue());
        }
        Object langPath = spec.get("lang");
        if (langPath != null && Files.isRegularFile(Path.of(langPath.toString())))
            for (var e : Json.read(Path.of(langPath.toString())).entrySet()) lang.put(e.getKey(), String.valueOf(e.getValue()));
    }
    static int bits(int n) { return Math.max(1, 32 - Integer.numberOfLeadingZeros(Math.max(1, n - 1))); }

    /** A chat component as the player reads it: text, translations from the server's own en_us, and children. */
    static String plain(Component component) {
        if (component == null) return "";
        StringBuilder out = new StringBuilder(); plain(component, out); return out.toString();
    }
    static void plain(Component c, StringBuilder out) {
        if (c instanceof TextComponent t) out.append(t.content());
        else if (c instanceof TranslatableComponent t) {
            String format = lang == null ? null : lang.get(t.key());
            if (format == null) format = t.fallback() != null ? t.fallback() : t.key();
            List<String> args = new ArrayList<>();
            for (TranslationArgument a : t.arguments()) args.add(a.value() instanceof Component ac ? plain(ac) : String.valueOf(a.value()));
            Matcher m = Pattern.compile("%(?:(\\d+)\\$)?([sd%])").matcher(format); int next = 0; StringBuilder f = new StringBuilder();
            while (m.find()) {
                String rep;
                if ("%".equals(m.group(2))) rep = "%";
                else { int i = m.group(1) != null ? Integer.parseInt(m.group(1)) - 1 : next++; rep = i < args.size() ? args.get(i) : ""; }
                m.appendReplacement(f, Matcher.quoteReplacement(rep));
            }
            m.appendTail(f); out.append(f);
        } else {
            try { var method = c.getClass().getMethod("content"); out.append(method.invoke(c)); } catch (Exception ignored) { }
        }
        for (Component child : c.children()) plain(child, out);
    }

    // ---------- the bot ----------
    record Chat(long at, String kind, String text) { }
    static final class Entity { int id; UUID uuid; String type; double x, y, z; final Map<Integer, String> texts = new TreeMap<>(); }

    static final class Bot extends SessionAdapter {
        final String name; ClientNetworkSession session; int protocolVersion;
        final List<Chat> chat = Collections.synchronizedList(new ArrayList<>());
        final Map<Integer, Entity> entities = new ConcurrentHashMap<>();
        final Map<Long, ChunkSection[]> chunks = new ConcurrentHashMap<>();
        final Map<Integer, ItemStack> inventory = new ConcurrentHashMap<>();
        volatile double x, y, z; volatile float yaw, pitch; volatile boolean spawned; volatile String disconnectReason;
        int entityId, minY = -64, sections = 24;
        final CountDownLatch ready = new CountDownLatch(1);
        Bot(String name) { this.name = name; }

        static Bot join(String name, int port, int seconds) throws Exception {
            loadIds();
            Bot bot = new Bot(name);
            MinecraftProtocol protocol = new MinecraftProtocol(new GameProfile(UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)), name), null);
            bot.protocolVersion = protocol.getCodec().getProtocolVersion();
            bot.session = ClientNetworkSessionFactory.factory().setAddress("127.0.0.1", port).setProtocol(protocol).create();
            bot.session.addListener(bot);
            bot.session.connect(true);
            if (!bot.ready.await(seconds, TimeUnit.SECONDS) || bot.disconnectReason != null)
                throw new ScenarioError(bot.disconnectReason != null ? "The bot could not join: " + bot.disconnectReason + "\n  The bot speaks protocol " + bot.protocolVersion + " (" + spec.get("botLibrary") + "); the server must run the same Minecraft version."
                        : "The bot did not spawn within " + seconds + " seconds of connecting.");
            Thread.sleep(500); // the first inventory and chunk packets follow the spawn position
            return bot;
        }
        void leave() { if (session != null && session.isConnected()) session.disconnect("modaccel server-check finished"); }

        @Override public void packetReceived(Session s, Packet packet) {
            try { handle(s, packet); } catch (Throwable error) { System.err.println("bot: " + packet.getClass().getSimpleName() + ": " + error); }
        }
        @Override public void disconnected(DisconnectedEvent event) {
            if (disconnectReason == null) disconnectReason = plain(event.getReason()) + (event.getCause() == null ? "" : " (" + event.getCause() + ")");
            ready.countDown();
        }
        void handle(Session s, Packet packet) throws Exception {
            if (packet instanceof ClientboundLoginPacket login) {
                entityId = login.getEntityId();
                String dimension = login.getCommonPlayerSpawnInfo().getWorldName().asString();
                if (dimension.contains("nether") || dimension.contains("end")) { minY = 0; sections = 16; }
            } else if (packet instanceof ClientboundPlayerPositionPacket p) {
                var rel = p.getRelatives();
                x = (rel.contains(PositionElement.X) ? x : 0) + p.getPosition().getX();
                y = (rel.contains(PositionElement.Y) ? y : 0) + p.getPosition().getY();
                z = (rel.contains(PositionElement.Z) ? z : 0) + p.getPosition().getZ();
                yaw = (rel.contains(PositionElement.Y_ROT) ? yaw : 0) + p.getYRot();
                pitch = (rel.contains(PositionElement.X_ROT) ? pitch : 0) + p.getXRot();
                s.send(new ServerboundAcceptTeleportationPacket(p.getId(), x, y, z, yaw, pitch));
                if (!spawned) s.send(new ServerboundMovePlayerPosRotPacket(true, false, x, y, z, yaw, pitch));
                if (!spawned) { spawned = true; s.send(ServerboundPlayerLoadedPacket.INSTANCE); ready.countDown(); }
            } else if (packet instanceof ClientboundSystemChatPacket c) add(c.isOverlay() ? "actionbar" : "chat", plain(c.getContent()));
            else if (packet instanceof ClientboundPlayerChatPacket c) {
                String body = c.getUnsignedContent() != null ? plain(c.getUnsignedContent()) : c.getContent();
                add("chat", decorate(c.getChatType(), plain(c.getName()), body, plain(c.getTargetName())));
            } else if (packet instanceof ClientboundDisguisedChatPacket c) add("chat", decorate(c.getChatType(), plain(c.getName()), plain(c.getMessage()), plain(c.getTargetName())));
            else if (packet instanceof ClientboundRegistryDataPacket r) { if (r.getRegistry().asString().equals("minecraft:chat_type")) for (var entry : r.getEntries()) chatTypes.add(entry.getId().asString()); }
            else if (packet instanceof ClientboundSetTitleTextPacket t) add("title", plain(t.getText()));
            else if (packet instanceof ClientboundSetSubtitleTextPacket t) add("title", plain(t.getText()));
            else if (packet instanceof ClientboundSetActionBarTextPacket t) add("actionbar", plain(t.getText()));
            else if (packet instanceof ClientboundDisconnectPacket d) disconnectReason = plain(d.getReason());
            else if (packet instanceof ClientboundLoginDisconnectPacket d) disconnectReason = plain(d.getReason());
            else if (packet instanceof ClientboundAddEntityPacket a) {
                Entity e = new Entity(); e.id = a.getEntityId(); e.uuid = a.getUuid(); e.x = a.getX(); e.y = a.getY(); e.z = a.getZ();
                e.type = "minecraft:" + a.getType().name().toLowerCase(Locale.ROOT);
                entities.put(e.id, e);
            } else if (packet instanceof ClientboundRemoveEntitiesPacket r) { for (int id : r.getEntityIds()) entities.remove(id); }
            else if (packet instanceof ClientboundSetEntityDataPacket d) {
                Entity e = entities.get(d.getEntityId()); if (e == null) return;
                for (EntityMetadata<?, ?> m : d.getMetadata()) {
                    Object v = m.getValue();
                    if (v instanceof Optional<?> o) v = o.orElse(null);
                    if (v instanceof Component c) e.texts.put(m.getId(), plain(c));
                }
            } else if (packet instanceof ClientboundTeleportEntityPacket t) {
                Entity e = entities.get(t.getId()); if (e == null) return;
                var rel = t.getRelatives();
                e.x = (rel.contains(PositionElement.X) ? e.x : 0) + t.getPosition().getX();
                e.y = (rel.contains(PositionElement.Y) ? e.y : 0) + t.getPosition().getY();
                e.z = (rel.contains(PositionElement.Z) ? e.z : 0) + t.getPosition().getZ();
            } else if (packet instanceof ClientboundEntityPositionSyncPacket p) {
                Entity e = entities.get(p.getId()); if (e == null) return;
                e.x = p.getEndPosition().getX(); e.y = p.getEndPosition().getY(); e.z = p.getEndPosition().getZ();
            } else if (packet instanceof ClientboundMoveEntityPosPacket m) move(m.getEntityId(), m.getMoveX(), m.getMoveY(), m.getMoveZ());
            else if (packet instanceof ClientboundMoveEntityPosRotPacket m) move(m.getEntityId(), m.getMoveX(), m.getMoveY(), m.getMoveZ());
            else if (packet instanceof ClientboundLevelChunkWithLightPacket c) {
                ByteBuf buf = Unpooled.wrappedBuffer(c.getChunkData());
                ChunkSection[] column = new ChunkSection[sections];
                for (int i = 0; i < sections && buf.isReadable(); i++) column[i] = MinecraftTypes.readChunkSection(buf, blockBits, biomeBits);
                chunks.put(key(c.getX(), c.getZ()), column);
            } else if (packet instanceof ClientboundForgetLevelChunkPacket f) chunks.remove(key(f.getX(), f.getZ()));
            else if (packet instanceof ClientboundBlockUpdatePacket u) setBlock(u.getEntry());
            else if (packet instanceof ClientboundSectionBlocksUpdatePacket u) { for (BlockChangeEntry entry : u.getEntries()) setBlock(entry); }
            else if (packet instanceof ClientboundContainerSetContentPacket c) {
                if (c.getContainerId() != 0) return;
                inventory.clear();
                ItemStack[] stacks = c.getItems();
                for (int i = 0; i < stacks.length; i++) if (stacks[i] != null && stacks[i].getAmount() > 0) inventory.put(i, stacks[i]);
            } else if (packet instanceof ClientboundContainerSetSlotPacket c) {
                if (c.getContainerId() != 0 && c.getContainerId() != -2) return;
                if (c.getItem() == null || c.getItem().getAmount() <= 0) inventory.remove(c.getSlot()); else inventory.put(c.getSlot(), c.getItem());
            } else if (packet instanceof ClientboundSetPlayerInventoryPacket c) {
                int slot = c.getSlot() < 9 ? c.getSlot() + 36 : c.getSlot(); // hotbar 0-8 are window slots 36-44
                if (c.getContents() == null || c.getContents().getAmount() <= 0) inventory.remove(slot); else inventory.put(slot, c.getContents());
            }
        }
        final List<String> chatTypes = new ArrayList<>();
        static final Map<String, String> VANILLA_CHAT = Map.of("minecraft:chat", "chat.type.text", "minecraft:emote_command", "chat.type.emote",
                "minecraft:say_command", "chat.type.announcement", "minecraft:msg_command_incoming", "commands.message.display.incoming",
                "minecraft:msg_command_outgoing", "commands.message.display.outgoing", "minecraft:team_msg_command_incoming", "chat.type.team.text",
                "minecraft:team_msg_command_outgoing", "chat.type.team.sent");
        /** A player's chat line the way the client prints it: the chat type's own translation with sender, content and target. */
        String decorate(Holder<ChatType> type, String sender, String content, String target) {
            String key = null; List<String> params = List.of("sender", "content");
            if (type != null && type.isCustom() && type.custom() != null) {
                key = type.custom().chat().translationKey();
                params = type.custom().chat().parameters().stream().map(p -> p.name().toLowerCase(Locale.ROOT)).toList();
            } else if (type != null && type.isId() && type.id() >= 0 && type.id() < chatTypes.size()) {
                key = VANILLA_CHAT.get(chatTypes.get(type.id()));
                if (key != null && (key.startsWith("chat.type.team") || key.equals("commands.message.display.outgoing")))
                    params = key.equals("commands.message.display.outgoing") ? List.of("target", "content") : List.of("target", "sender", "content");
            }
            String format = key == null || lang == null ? null : lang.get(key);
            if (format == null) return "<" + sender + "> " + content;
            List<String> args = new ArrayList<>();
            for (String p : params) args.add(p.equals("sender") ? sender : p.equals("target") ? target : content);
            Matcher m = Pattern.compile("%(?:(\\d+)\\$)?s").matcher(format); int next = 0; StringBuilder out = new StringBuilder();
            while (m.find()) { int i = m.group(1) != null ? Integer.parseInt(m.group(1)) - 1 : next++; m.appendReplacement(out, Matcher.quoteReplacement(i < args.size() ? args.get(i) : "")); }
            return m.appendTail(out).toString();
        }
        void add(String kind, String text) { chat.add(new Chat(System.nanoTime(), kind, mask(text))); }
        void move(int id, double dx, double dy, double dz) { Entity e = entities.get(id); if (e != null) { e.x += dx; e.y += dy; e.z += dz; } }
        static long key(int cx, int cz) { return ((long) cx << 32) ^ (cz & 0xffffffffL); }
        void setBlock(BlockChangeEntry entry) {
            var p = entry.getPosition(); ChunkSection[] column = chunks.get(key(p.getX() >> 4, p.getZ() >> 4));
            int index = (p.getY() - minY) >> 4;
            if (column == null || index < 0 || index >= column.length) return;
            if (column[index] == null) column[index] = new ChunkSection(0, 0, 0, 0);
            column[index].setBlock(p.getX() & 15, p.getY() & 15, p.getZ() & 15, entry.getBlock());
        }
        String blockAt(int bx, int by, int bz) {
            ChunkSection[] column = chunks.get(key(bx >> 4, bz >> 4)); int index = (by - minY) >> 4;
            if (column == null || index < 0 || index >= column.length || column[index] == null) return null;
            int state = column[index].getBlock(bx & 15, by & 15, bz & 15);
            return blockStates.getOrDefault(state, "block state " + state);
        }
        int[] blockCoordinates(String sx, String sy, String sz) throws ScenarioError {
            return new int[] { coordinate(sx, x), coordinate(sy, y), coordinate(sz, z) };
        }
        static int coordinate(String text, double base) throws ScenarioError {
            try {
                if (text.startsWith("~")) return (int) Math.floor(base) + (text.length() == 1 ? 0 : Integer.parseInt(text.substring(1)));
                return Integer.parseInt(text);
            } catch (NumberFormatException e) { throw new ScenarioError("\"" + text + "\" is not a block coordinate (a whole number, ~ or ~N)."); }
        }
        void say(String text) {
            session.send(new ServerboundChatPacket(text, System.currentTimeMillis(), 0L, null, 0, new BitSet(20), 0));
        }
        void command(String text) { session.send(new ServerboundChatCommandPacket(text)); }

        String findChat(int mark, String kind, Pattern pattern) {
            synchronized (chat) {
                for (int i = mark; i < chat.size(); i++) {
                    Chat c = chat.get(i);
                    if ((kind.equals("chat") ? c.kind().equals("chat") : c.kind().equals(kind)) && pattern.matcher(c.text()).find()) return c.text();
                }
            }
            return null;
        }
        String chatSince(int mark, int n) {
            synchronized (chat) {
                List<Chat> since = chat.subList(Math.min(mark, chat.size()), chat.size());
                if (since.isEmpty()) return "The bot received no chat, title or action bar since then." + (disconnectReason == null ? "" : " It was disconnected: " + disconnectReason);
                StringBuilder b = new StringBuilder("The bot received" + (since.size() > n ? " (last " + n + " of " + since.size() + ")" : "") + ":");
                for (Chat c : since.subList(Math.max(0, since.size() - n), since.size())) b.append("\n    [").append(c.kind()).append("] ").append(clip(c.text(), 300));
                return b.toString();
            }
        }
        double distance(Entity e) { return Math.sqrt((e.x - x) * (e.x - x) + (e.y - y) * (e.y - y) + (e.z - z) * (e.z - z)); }
        String describe(Entity e) {
            return e.type + (e.texts.isEmpty() ? "" : " \"" + String.join("\" / \"", e.texts.values()) + "\"") + " at " + fmt1(e.x) + " " + fmt1(e.y) + " " + fmt1(e.z) + " (" + fmt1(distance(e)) + " blocks away)";
        }
        static String fmt1(double d) { return String.format(Locale.ROOT, "%.1f", d); }
        String findEntity(String type, Pattern named, double radius) {
            for (Entity e : entities.values()) {
                if (e.id == entityId || !e.type.equals(type) || distance(e) > radius) continue;
                if (named != null && e.texts.values().stream().noneMatch(t -> named.matcher(t).find())) continue;
                return describe(e);
            }
            return null;
        }
        String entitiesText(String type, int n) {
            List<Entity> all = new ArrayList<>(entities.values()); all.removeIf(e -> e.id == entityId);
            if (all.isEmpty()) return "no entities at all.";
            all.sort(Comparator.comparing((Entity e) -> !e.type.equals(type)).thenComparingDouble(this::distance));
            StringBuilder b = new StringBuilder(all.size() + " entit" + (all.size() == 1 ? "y" : "ies") + (all.size() > n ? ", the nearest " + n : "") + ":");
            for (Entity e : all.subList(0, Math.min(n, all.size()))) b.append("\n    ").append(describe(e));
            return b.toString();
        }
        String positionText() { return fmt1(x) + " " + fmt1(y) + " " + fmt1(z); }

        String itemName(ItemStack stack) { return items.getOrDefault(stack.getId(), "item " + stack.getId()); }
        /** Everything a player can read on an item: its name, lore and, for books, title, author and pages. */
        String itemText(ItemStack stack) {
            StringBuilder b = new StringBuilder();
            DataComponents patch = stack.getDataComponentsPatch();
            if (patch == null) return "";
            for (DataComponent<?, ?> component : patch.getDataComponents().values()) {
                Object v = component.getValue();
                if (v instanceof Component c) b.append(plain(c)).append('\n');
                else if (v instanceof WrittenBookContent book) {
                    b.append(book.getTitle() == null ? "" : book.getTitle().getRaw()).append('\n').append(book.getAuthor()).append('\n');
                    for (Filterable<Component> page : book.getPages()) b.append(plain(page.getRaw())).append('\n');
                } else if (v instanceof List<?> list) { for (Object o : list) if (o instanceof Component c) b.append(plain(c)).append('\n'); else if (o != null) b.append(o).append('\n'); }
                else if (v != null) b.append(v).append('\n');
            }
            return b.toString();
        }
        String findItem(String item, int count, Pattern text) {
            int total = 0; String first = null;
            for (var e : new TreeMap<>(inventory).entrySet()) {
                ItemStack s = e.getValue();
                if (!itemName(s).equals(item)) continue;
                String t = itemText(s).replaceAll("\\s+", " "); // a book's wrapped lines read as one text
                if (text != null && !text.matcher(t).find()) continue;
                total += s.getAmount();
                if (first == null) first = s.getAmount() + " x " + item + " in slot " + e.getKey() + (t.isBlank() ? "" : ": " + clip(t.strip().replace('\n', ' '), 160));
            }
            return total >= count ? first : null;
        }
        String inventoryText() {
            if (inventory.isEmpty()) return "nothing.";
            StringBuilder b = new StringBuilder();
            for (var e : new TreeMap<>(inventory).entrySet()) {
                String t = itemText(e.getValue());
                b.append("\n    slot ").append(e.getKey()).append(": ").append(e.getValue().getAmount()).append(" x ").append(itemName(e.getValue()))
                        .append(t.isBlank() ? "" : " \"" + clip(t.strip().replace('\n', ' '), 200) + "\"");
            }
            return b.toString();
        }
    }
}
