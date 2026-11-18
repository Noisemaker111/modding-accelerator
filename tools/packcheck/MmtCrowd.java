package mmt.packprobe;

import com.google.gson.*;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

/**
 * Stand-in players for pack-run --server: players the server treats as joined (PlayerList's own join, so mods see them
 * log in), each in their own spot, so a server carries what several real players make it carry: the chunks around each
 * one loaded and ticking, mobs spawning near them, their entity and chunk updates built for them every tick. Their
 * connection is a channel that drops what is sent: no network encoding, which a real server does off its tick thread.
 *
 * Player i of N starts mmt.crowd.spacing blocks from spawn, at angle 360*i/N, at height mmt.crowd.height, in creative
 * and flying. The first mmt.crowd.moving of them fly straight outwards at mmt.crowd.speed blocks a second (wall time, as
 * a player's client moves however slow the server is), loading new land; the rest stay. Once each player's land has
 * loaded, every line of mmt.crowd.commands runs for each player (placeholders {player} {i} {x} {y} {z}), as an operator.
 * The server runs until the file mmt.crowd.stop appears or mmt.crowd.hours pass, then saves and stops.
 */
public final class MmtCrowd {

    private MmtCrowd() {}

    public static void run(MinecraftServer server, JsonObject result) throws Exception {
        int count = Integer.getInteger("mmt.crowd.players", 4);
        int moving = Math.min(count, Integer.getInteger("mmt.crowd.moving", 0));
        double speed = Double.parseDouble(System.getProperty("mmt.crowd.speed", "10"));
        int spacing = Integer.getInteger("mmt.crowd.spacing", 512);
        double height = Double.parseDouble(System.getProperty("mmt.crowd.height", "100"));
        Path stop = Paths.get(System.getProperty("mmt.crowd.stop", "mmt-crowd.stop"));
        long deadline = System.nanoTime() + (long) (Double.parseDouble(System.getProperty("mmt.crowd.hours", "3")) * 3600e9);
        ServerLevel level = server.overworld();
        List<ServerPlayer> players = new ArrayList<>();
        double[][] start = new double[count][];
        double[][] direction = new double[count][];
        JsonArray placed = new JsonArray();
        onServer(server, () -> {
            BlockPos spawn = MmtPackGame.spawn(level);
            for (int i = 0; i < count; i++) {
                String name = "mmt" + (i + 1);
                double angle = 2 * Math.PI * i / count;
                direction[i] = new double[] { Math.cos(angle), Math.sin(angle) };
                start[i] = new double[] { Math.floor(spawn.getX() + spacing * direction[i][0]) + 0.5, height, Math.floor(spawn.getZ() + spacing * direction[i][1]) + 0.5 };
                com.mojang.authlib.GameProfile profile = new com.mojang.authlib.GameProfile(
                    UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)), name);
                try {
                    ServerPlayer player = MmtPackProbe.player(level, profile);
                    join(server, sink(), player);
                    player.setGameMode(GameType.CREATIVE);
                    player.getAbilities().mayfly = true;
                    player.getAbilities().flying = true;
                    place(level, player, start[i][0], start[i][1], start[i][2]);
                    players.add(player);
                    JsonObject one = new JsonObject();
                    one.addProperty("name", name);
                    one.addProperty("moving", i < moving);
                    one.addProperty("x", start[i][0]);
                    one.addProperty("z", start[i][2]);
                    placed.add(one);
                } catch (Exception failed) {
                    throw new IllegalStateException("could not add stand-in player " + name + ": " + MmtPackProbe.stack(failed), failed);
                }
            }
        });
        result.add("players", placed);
        // Each player's own land, loaded around them (the chunk and its neighbours), before their commands run.
        long since = System.nanoTime();
        int[] waiting = { count };
        while (waiting[0] > 0) {
            if (System.nanoTime() - since > TimeUnit.MINUTES.toNanos(10)) throw new IllegalStateException("the players' land did not load in 10 minutes");
            onServer(server, () -> {
                waiting[0] = 0;
                for (ServerPlayer player : players) {
                    player.doTick();
                    int cx = (int) Math.floor(player.getX()) >> 4, cz = (int) Math.floor(player.getZ()) >> 4;
                    for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) if (!level.hasChunk(cx + dx, cz + dz)) { waiting[0]++; dx = 3; break; }
                }
            });
            Thread.sleep(250);
        }
        result.addProperty("landSeconds", (System.nanoTime() - since) / 1e9);
        String commands = System.getProperty("mmt.crowd.commands", "");
        if (!commands.isEmpty()) {
            JsonArray ran = new JsonArray();
            List<String> lines = Files.readAllLines(Paths.get(commands), StandardCharsets.UTF_8);
            for (int i = 0; i < players.size(); i++) {
                ServerPlayer player = players.get(i);
                int index = i;
                for (String line : lines) {
                    String text = line.strip();
                    if (text.isEmpty() || text.startsWith("#")) continue;
                    // "N* command": the command N times, {n} counting from 0, {n%K} and {n/K} for rows and columns.
                    int times = 1;
                    java.util.regex.Matcher repeat = java.util.regex.Pattern.compile("^([0-9]+)\\*\\s+(.*)$").matcher(text);
                    if (repeat.matches()) {
                        times = Integer.parseInt(repeat.group(1));
                        text = repeat.group(2);
                    }
                    String base = text.replace("{player}", player.getGameProfile().getName()).replace("{i}", String.valueOf(index + 1))
                        .replace("{x}", String.valueOf((int) Math.floor(start[index][0]))).replace("{y}", String.valueOf((int) Math.floor(start[index][1])))
                        .replace("{z}", String.valueOf((int) Math.floor(start[index][2])));
                    int worked = 0;
                    String last = "";
                    for (int n = 0; n < times; n++) {
                        String command = counted(base, n);
                        Object[] answer = new Object[1];
                        onServer(server, () -> answer[0] = command(server, command));
                        last = String.valueOf(answer[0]);
                        if (last.matches("[1-9][0-9]*")) worked++;
                    }
                    JsonObject one = new JsonObject();
                    one.addProperty("command", times == 1 ? base : times + "* " + base);
                    one.addProperty("result", times == 1 ? last : String.valueOf(worked));
                    ran.add(one);
                }
            }
            result.add("commands", ran);
        }
        write(result);
        System.out.println("MMT-CROWD-READY " + players.size() + " players");
        long began = System.nanoTime();
        int last = -1;
        String[] firstError = { null };
        while (server.isRunning()) {
            if (Files.exists(stop) || System.nanoTime() > deadline) {
                System.out.println("MMT-CROWD-STOP " + (Files.exists(stop) ? "asked" : "time limit"));
                server.halt(false);
                return;
            }
            int tick = server.getTickCount();
            if (tick == last) {
                Thread.sleep(2);
                continue;
            }
            last = tick;
            double seconds = (System.nanoTime() - began) / 1e9;
            onServer(server, () -> {
                for (int i = 0; i < players.size(); i++) {
                    ServerPlayer player = players.get(i);
                    try {
                        if (i < moving) place(level, player, start[i][0] + direction[i][0] * speed * seconds, start[i][1], start[i][2] + direction[i][1] * speed * seconds);
                        else player.setDeltaMovement(Vec3.ZERO);
                        player.doTick();
                    } catch (RuntimeException failed) {
                        if (firstError[0] == null) {
                            firstError[0] = MmtPackProbe.stack(failed);
                            System.out.println("MMT-CROWD-ERROR " + firstError[0]);
                        }
                    }
                }
            });
        }
    }

    /** {n}, {n%K} and {n/K} filled in for repetition n. */
    static String counted(String text, int n) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{n(?:([%/])([0-9]+))?\\}").matcher(text);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            int value = m.group(1) == null ? n : m.group(1).equals("%") ? n % Integer.parseInt(m.group(2)) : n / Integer.parseInt(m.group(2));
            m.appendReplacement(out, String.valueOf(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Puts a player at a spot and tells the chunk system, as a client's move packet does, so the land around them loads. */
    static void place(ServerLevel level, ServerPlayer player, double x, double y, double z) {
        MmtPackGame.moveTo(player, x, y, z, 0, 0);
        player.setDeltaMovement(Vec3.ZERO);
        level.getChunkSource().move(player);
    }

    /** A connection whose channel drops whatever is written to it, so nothing queues up for a client that is not there. */
    static Connection sink() throws ReflectiveOperationException {
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
                ReferenceCountUtil.release(message);
                promise.setSuccess();
            }
        });
        for (Field field : Connection.class.getDeclaredFields()) {
            if (field.getType() == Channel.class) {
                field.setAccessible(true);
                field.set(connection, channel);
            } else if (field.getType() == SocketAddress.class) {
                field.setAccessible(true);
                field.set(connection, channel.remoteAddress());
            }
        }
        return connection;
    }

    /** PlayerList's own join: (Connection, ServerPlayer), and from 1.20.2 a login cookie made from the profile. */
    static void join(MinecraftServer server, Connection connection, ServerPlayer player) throws ReflectiveOperationException {
        Object list = server.getPlayerList();
        for (Method method : list.getClass().getMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (types.length < 2 || types[0] != Connection.class || types[1] != ServerPlayer.class || method.getReturnType() != void.class) continue;
            if (types.length == 2) {
                method.invoke(list, connection, player);
                return;
            }
            if (types.length != 3) continue;
            for (Method made : types[2].getMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(made.getModifiers()) || made.getReturnType() != types[2] || made.getParameterCount() < 1 ||
                    made.getParameterTypes()[0] != com.mojang.authlib.GameProfile.class) continue;
                Object[] arguments = new Object[made.getParameterCount()];
                arguments[0] = player.getGameProfile();
                for (int a = 1; a < arguments.length; a++) arguments[a] = made.getParameterTypes()[a] == boolean.class ? Boolean.FALSE : null;
                method.invoke(list, connection, player, made.invoke(null, arguments));
                return;
            }
        }
        throw new IllegalStateException("PlayerList has no join method this probe knows");
    }

    /** One command as an operator with no chat output: performPrefixedCommand from 1.19, performCommand before. */
    static Object command(MinecraftServer server, String command) {
        CommandSourceStack source = server.createCommandSourceStack().withPermission(4).withSuppressedOutput();
        Object commands = server.getCommands();
        for (Method method : commands.getClass().getMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (types.length == 2 && types[0] == CommandSourceStack.class && types[1] == String.class) try {
                return method.invoke(commands, source, command);
            } catch (ReflectiveOperationException failed) {
                return "failed: " + failed.getCause();
            }
        }
        return "no command method";
    }

    static void onServer(MinecraftServer server, Runnable work) throws Exception {
        CompletableFuture<Void> done = CompletableFuture.runAsync(work, server);
        while (true) {
            try {
                done.get(1, TimeUnit.MINUTES);
                return;
            } catch (java.util.concurrent.TimeoutException busy) {
                if (!server.isRunning()) return;
            } catch (java.util.concurrent.ExecutionException failed) {
                throw failed.getCause() instanceof Exception ? (Exception) failed.getCause() : failed;
            }
        }
    }

    static void write(JsonObject result) throws java.io.IOException {
        Path out = Paths.get(System.getProperty("mmt.probe.out", "mmt-probe.json"));
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.write(out, new GsonBuilder().setPrettyPrinting().create().toJson(result).getBytes(StandardCharsets.UTF_8));
    }
}
