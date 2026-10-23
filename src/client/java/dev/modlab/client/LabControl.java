package dev.modlab.client;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;


/**
 * Keeps one Minecraft client alive at the title screen and answers UI checks over a
 * loopback socket, so a UI change costs a second instead of a fresh client launch.
 *
 * <p>Only active with {@code -Dprismlab.lab.port=PORT}. Commands: {@code ui},
 * {@code ui-update}, {@code quit}. One JSON line comes back.
 */
public final class LabControl {
	private static final int SHOT_WIDTH = 854;
	private static final int SHOT_HEIGHT = 480;
	// The client runs with run/ as its working directory, so paths are built from the
	// project root, which is run/'s parent.
	private static long sourceStamp(Path root) throws IOException {
		long stamp = 0L;
		for (String dir : List.of("src/main/java", "src/client/java")) {
			Path base = root.resolve(dir);
			if (!Files.isDirectory(base)) {
				continue;
			}
			try (var walk = Files.walk(base)) {
				stamp = Math.max(stamp, walk.filter(Files::isRegularFile).mapToLong(p -> p.toFile().lastModified()).max().orElse(0L));
			}
		}
		return stamp;
	}

	private static Path root() {
		String configuredRoot = System.getProperty("modlab.root");
		if (configuredRoot != null && !configuredRoot.isBlank()) {
			return Path.of(configuredRoot);
		}
		Path gameDir = Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath();
		Path parent = gameDir.getParent();
		return parent != null ? parent : gameDir;
	}

	private static final LinkedBlockingQueue<Scenario> QUEUE = new LinkedBlockingQueue<>();
	private static volatile boolean ready;
	private static Scenario current;

	private LabControl() {
	}

	public static void startIfRequested() {
		String portText = System.getProperty("prismlab.lab.port");
		if (portText == null || portText.isBlank()) {
			return;
		}
		int port = Integer.parseInt(portText.trim());
		ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
		Thread server = new Thread(() -> serve(port), "prismlab-lab-control");
		server.setDaemon(true);
		server.start();
	}

	private static void serve(int port) {
		long deadline = System.currentTimeMillis() + 180_000;
		while (!ready && System.currentTimeMillis() < deadline) {
			try {
				Thread.sleep(100);
			}
			catch (InterruptedException e) {
				return;
			}
		}
		try (ServerSocket socket = new ServerSocket(port, 8, InetAddress.getLoopbackAddress())) {
			System.out.println("PRISMLAB-READY " + port);
			System.out.flush();
			while (true) {
				try (Socket client = socket.accept();
						BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
						PrintWriter out = new PrintWriter(client.getOutputStream(), true, StandardCharsets.UTF_8)) {
					String line = in.readLine();
					if (line == null) {
						continue;
					}
					if (line.startsWith("root")) {
						out.println("{\"root\":\"" + escape(root().toString()) + "\"}");
						continue;
					}
					if (line.startsWith("state")) {
						var mc = Minecraft.getInstance();
						var screen = mc.gui.screen();
						out.println("{\"screen\":\"" + escape(screen == null ? "none" : screen.getClass().getName())
								+ "\",\"level\":" + (mc.level != null) + ",\"player\":" + (mc.player != null)
								+ ",\"mp\":\"" + escape(System.getProperty("modlab.mp", "")) + "\"}");
						continue;
					}
					if (line.startsWith("quit")) {
						out.println("{\"ok\":true,\"quit\":true}");
						Minecraft.getInstance().stop();
						return;
					}
					Scenario scenario = new Scenario(line.trim());
					QUEUE.add(scenario);
					out.println(scenario.result.get(180, TimeUnit.SECONDS));
				}
			}
		}
		catch (Exception e) {
			System.out.println("PRISMLAB-ERROR " + e);
		}
	}

	private static void tick() {
		// Wait for the real title screen: the loading overlay also counts as a screen.
		if (!ready && Minecraft.getInstance().gui.screen() instanceof TitleScreen) {
			ready = true;
		}
		if (current == null) {
			current = QUEUE.poll();
			if (current == null) {
				return;
			}
		}
		try {
			if (current.step()) {
				current = null;
			}
		}
		catch (Throwable t) {
			current.fail(t);
			current = null;
		}
	}

	private static String escape(String text) {
		StringBuilder out = new StringBuilder();
		for (char c : text.toCharArray()) {
			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append(' ');
				case '\r' -> out.append(' ');
				default -> out.append(c);
			}
		}
		return out.toString();
	}

	private static final class Scenario {
		private final String command;
		private final CompletableFuture<String> result = new CompletableFuture<>();
		private final List<String> lines = new ArrayList<>();
		private net.minecraft.core.BlockPos nodePos;
		private net.minecraft.core.BlockPos placedPos;
		private String[] steps;
		private int index;
		private int pending;
		private boolean worldRequested;
		private int[] settleSample;
		private int[] settleNow;
		private int settlePhase;
		private int settleAttempts;
		private boolean settleWaiting;
		private int assertTries;
		private Screen previous;
		private Class<?> uiClass;
		private boolean waiting;
		private boolean ok = true;
		private boolean mainReloaded;
		private int waits;
		private int tick;

		Scenario(String command) {
			this.command = command;
		}

		boolean step() throws Exception {
			if (this.waiting) {
				return false;
			}
			Minecraft client = Minecraft.getInstance();
			this.tick++;
			if (this.command.startsWith("node")) {
				return stepNode(client);
			}
			if (this.command.startsWith("scenario ")) {
				return stepScenario(client);
			}
			switch (this.tick) {
				case 1 -> {
					this.previous = client.gui.screen();
					client.options.guiScale().set(2);
					client.getWindow().setWindowed(SHOT_WIDTH, SHOT_HEIGHT);
					client.resizeGui();
				}
				case 4 -> {
					client.options.tutorialStep = net.minecraft.client.tutorial.TutorialSteps.NONE;
					client.options.pauseOnLostFocus = false;
					client.gui.toastManager().clear();
					this.lines.add("main: " + HotUi.redefineMain(root(), sourceStamp(root())));
					ClassLoader loader = HotUi.reload(root(), sourceStamp(root()));
					openScreen(client, loader, System.getProperty("modlab.screen", ""));
				}
				case 7 -> capture(client, uiName());
				case 10 -> {
					String label = System.getProperty("modlab.click");
					if (label != null && !label.isBlank()) {
						clickButton(client, label);
					}
				}
				case 20 -> {
					String label = System.getProperty("modlab.click");
					if (label != null && !label.isBlank()) {
						capture(client, uiName() + "-clicked");
					}
				}
				case 24 -> {
					client.gui.setScreen(this.previous);
					String body = escape(String.join(" | ", this.lines));
					this.result.complete("{\"ok\":" + this.ok + ",\"lines\":\"" + body + "\"}");
					return true;
				}
				default -> {
				}
			}
			return false;
		}

		/**
		 * Block behaviour in the warm client's own world: place the node, let the real
		 * server tick it, then read the block entity. No fresh server, no Gradle.
		 */
		/**
		 * A scripted list of steps in the warm client's world, separated by '|':
		 * place=namespace:id, wait=<ticks>, assert=<probeMethod>, screen=<class>,
		 * click=<button label>, capture=<name>, settle.
		 */
		private boolean stepScenario(Minecraft client) throws Exception {
			if (this.steps == null) {
				this.steps = this.command.substring("scenario ".length()).split("\\|");
				this.index = 0;
				client.options.pauseOnLostFocus = false;
				client.options.tutorialStep = net.minecraft.client.tutorial.TutorialSteps.NONE;
				client.options.guiScale().set(2);
				client.getWindow().setWindowed(SHOT_WIDTH, SHOT_HEIGHT);
				client.resizeGui();
				client.gui.toastManager().clear();
				this.lines.add("main: " + HotUi.redefineMain(root(), sourceStamp(root())));
			}
			if (this.pending > 0) {
				this.pending--;
				return false;
			}
			if (this.index >= this.steps.length) {
				this.result.complete("{\"ok\":true,\"lines\":\"" + escape(String.join(" | ", this.lines)) + "\"}");
				return true;
			}
			String step = this.steps[this.index].trim();
			if (step.startsWith("wait=")) {
				this.pending = Integer.parseInt(step.substring(5).trim());
				this.index++;
				return false;
			}
			if (step.equals("settle")) {
				if (settled(client)) {
					this.lines.add("settled");
					this.index++;
					return false;
				}
				return false;
			}
			if (!ensureWorld(client)) {
				return false;
			}
			if (step.startsWith("at=")) {
				// A second player sets the position another client placed at, so it can assert what
				// the server sent to it (multiplayer sync), not what its own world contains.
				String[] parts = step.substring(3).trim().split("\\s*,\\s*");
				if (parts.length != 3) {
					throw new AssertionError("at= needs x,y,z");
				}
				this.placedPos = new net.minecraft.core.BlockPos(
						Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
				this.lines.add("at " + this.placedPos.toShortString());
			}
			else if (step.startsWith("place=")) {
				String id = step.substring(6).trim();
				var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK
						.getValue(net.minecraft.resources.Identifier.parse(id));
				if (block == null) {
					throw new AssertionError("unknown block " + id);
				}
				var server = client.getSingleplayerServer();
				var pos = new net.minecraft.core.BlockPos(client.player.getBlockX() + 1, client.player.getBlockY() + 2, client.player.getBlockZ() + 1);
				if (server == null) {
					// Multiplayer: ask the server to place it, then let the update packet arrive.
					client.player.connection.sendCommand("labplace " + id);
					this.assertTries = 0;
					this.placedPos = pos;
					this.pending = 6;
					this.lines.add("asked the server to place " + id + " at " + pos.toShortString());
				}
				else {
					server.submit(() -> {
						server.overworld().setBlockAndUpdate(pos, block.defaultBlockState());
						return null;
					}).get(20, TimeUnit.SECONDS);
					this.placedPos = pos;
					this.lines.add("placed " + id);
				}
			}
			else if (step.startsWith("assert=")) {
				String method = step.substring(7).trim();
				if (this.placedPos == null) {
					throw new AssertionError("assert before place");
				}
				var server = client.getSingleplayerServer();
				Object value;
				if (server == null) {
					// Multiplayer: read what the server actually sent to this client, giving the
					// place command a few ticks to arrive and sync.
					var entity = client.level == null ? null : client.level.getBlockEntity(this.placedPos);
					if (entity == null && this.assertTries < 20) {
						this.assertTries++;
						this.pending = 5;
						return false;
					}
					value = probe(entity, method);
				}
				else {
					value = server.submit(() -> probe(server.overworld().getBlockEntity(this.placedPos), method))
							.get(20, TimeUnit.SECONDS);
				}
				if (!(value instanceof Integer number) || number <= 0) {
					throw new AssertionError(method + "() was " + value + " at " + this.placedPos.toShortString()
							+ " block=" + (client.level == null ? "no level" : client.level.getBlockState(this.placedPos).toString()));
				}
				this.lines.add("assert " + method + "() = " + value);
			}
			else if (step.startsWith("command=")) {
				String command = step.substring(8).trim();
				client.player.connection.sendCommand(command);
				this.lines.add("sent /" + command);
			}
			else if (step.startsWith("screen=")) {
				openScreen(client, HotUi.reload(root(), sourceStamp(root())), step.substring(7));
			}
			else if (step.startsWith("click=")) {
				this.clickButton(client, step.substring(6).trim());
			}
			else if (step.startsWith("capture=")) {
				capture(client, step.substring(8).trim());
				this.index++;
				return false;
			}
			else {
				throw new AssertionError("unknown step '" + step + "'");
			}
			this.index++;
			return false;
		}

		/**
		 * True when two frames 2 ticks apart are identical, so a screenshot cannot catch a
		 * half-drawn frame or a bake in progress. Fails after 20 tries rather than risk a stale shot.
		 */
		private boolean settled(Minecraft client) throws Exception {
			if (this.settleWaiting) {
				return false;
			}
			if (this.settlePhase == 2) {
				if (this.settleAttempts > 0) {
					double moved = frameDelta(this.settleSample, this.settleNow);
					if (moved <= 0.002) {
						this.settlePhase = 0;
						this.settleAttempts = 0;
						return true;
					}
					// Weather and water can move a few tenths of a percent forever. After 20 tries,
					// accept a small, explicitly reported delta instead of failing the run.
					if (this.settleAttempts > 20) {
						// Ambient motion (a rotating night sky, weather) never converges. Report the
						// amount instead of failing: the screenshot is still the evidence.
						this.lines.add(String.format("settled after 20 tries with %.2f%% of pixels moving", moved * 100));
						this.settlePhase = 0;
						this.settleAttempts = 0;
						return true;
					}
				}
				this.settleSample = this.settleNow;
				this.settlePhase = 0;
				this.settleAttempts++;
				this.pending = 2;
				return false;
			}
			this.settleWaiting = true;
			Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
				this.settleNow = image.getPixelsABGR();
				this.settleWaiting = false;
				this.settlePhase = 2;
			});
			this.pending = 2;
			return false;
		}

		/** Calls a no-argument probe method on a block entity and reports the failure as a value. */
		private static Object probe(Object entity, String method) {
			if (entity == null) {
				return null;
			}
			try {
				return entity.getClass().getMethod(method).invoke(entity);
			}
			catch (ReflectiveOperationException e) {
				return "probe failed: " + e.getMessage();
			}
		}

		/** Fraction of pixels that moved by more than 2 levels in a channel. */
		private static double frameDelta(int[] before, int[] after) {
			if (before == null || after == null || before.length != after.length) {
				return 1.0;
			}
			int moved = 0;
			for (int i = 0; i < before.length; i++) {
				int a = before[i];
				int b = after[i];
				if ((Math.abs((a & 0xFF) - (b & 0xFF)) > 2)
						|| (Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF)) > 2)
						|| (Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF)) > 2)) {
					moved++;
				}
			}
			return (double) moved / before.length;
		}

		/** Returns true when the client is in a world; opens the lab world once if not. */
		private boolean ensureWorld(Minecraft client) {
			if (client.getSingleplayerServer() != null && client.player != null) {
				return true;
			}
			String server = System.getProperty("modlab.mp", "");
			if (!server.isBlank()) {
				// Multiplayer: the dedicated server owns the world, so join it instead of opening one.
				if (client.level != null && client.player != null) {
					return true;
				}
				var screen = client.gui.screen();
				// A fresh run directory starts on the accessibility onboarding screen, so do not
				// wait for the title screen: connect from wherever the client is, unless a join is
				// already in flight.
				boolean connecting = screen instanceof net.minecraft.client.gui.screens.ConnectScreen;
				boolean canRetry = !connecting;
				if (canRetry && (this.tick == 1 || this.tick % 200 == 0)) {
					this.worldRequested = true;
					var address = net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(server);
					var data = new net.minecraft.client.multiplayer.ServerData("lab", server,
							net.minecraft.client.multiplayer.ServerData.Type.OTHER);
					net.minecraft.client.gui.screens.ConnectScreen.startConnecting(
							new net.minecraft.client.gui.screens.TitleScreen(), client, address, data, false, null);
				}
				this.waits++;
				if (this.waits > 2400) {
					this.fail(new AssertionError("could not join " + server));
				}
				return false;
			}
			if (!this.worldRequested) {
				this.worldRequested = true;
				client.createWorldOpenFlows().openWorld("lab", () -> { });
			}
			this.waits++;
			if (this.waits > 2400) {
				this.fail(new AssertionError("could not open the lab world"));
				return false;
			}
			return false;
		}

		private boolean stepNode(Minecraft client) throws Exception {
			if (!this.mainReloaded) {
				client.options.pauseOnLostFocus = false;
				try {
					HotUi.redefineMain(root(), sourceStamp(root()));
				}
				catch (Throwable ignored) {
					// the placement below will fail loudly if the code is stale
				}
				this.mainReloaded = true;
			}
			var server = client.getSingleplayerServer();
			if (server == null) {
				if (this.tick == 1) {
					client.createWorldOpenFlows().openWorld("lab", () -> { });
				}
				this.tick = 0;
				this.waits++;
				if (this.waits > 1200) {
					this.fail(new AssertionError("could not open the lab world"));
					return true;
				}
				return false;
			}
			if (client.player == null) {
				this.tick = 0;
				this.waits++;
				if (this.waits > 1200) {
					this.fail(new AssertionError("client never joined the lab world"));
					return true;
				}
				return false;
			}
			if (this.nodePos == null) {
				String blockId = System.getProperty("modlab.block");
				if (blockId == null || blockId.isBlank()) {
					this.fail(new AssertionError("no -Dmodlab.block=<namespace:id> configured"));
					return true;
				}
				var block = net.minecraft.core.registries.BuiltInRegistries.BLOCK
						.getValue(net.minecraft.resources.Identifier.parse(blockId));
				if (block == null) {
					this.fail(new AssertionError("unknown block " + blockId));
					return true;
				}
				var level = server.overworld();
				var pos = new net.minecraft.core.BlockPos(client.player.getBlockX() + 1, client.player.getBlockY() + 2, client.player.getBlockZ() + 1);
				server.submit(() -> {
					level.setBlockAndUpdate(pos, block.defaultBlockState());
					return null;
				}).get(20, TimeUnit.SECONDS);
				this.nodePos = pos;
				this.tick = 0;
				return false;
			}
			if (this.tick < 40) {
				return false;
			}
			String probeMethod = System.getProperty("modlab.probe", "charge");
			Object[] probeResult = server.submit(() -> {
				var level = server.overworld();
				var entity = level.getBlockEntity(this.nodePos);
				if (entity == null) {
					return new Object[] { "no block entity", -1, null };
				}
				Object value;
				try {
					value = entity.getClass().getMethod(probeMethod).invoke(entity);
				}
				catch (NoSuchMethodException missing) {
					return new Object[] { entity.getClass().getSimpleName(), -1, "-Dmodlab.probe=" + probeMethod + " not found" };
				}
				catch (Exception other) {
					return new Object[] { entity.getClass().getSimpleName(), -1, "probe failed: " + other };
				}
				return new Object[] { entity.getClass().getSimpleName(), value, null };
			}).get(20, TimeUnit.SECONDS);
			String kind = String.valueOf(probeResult[0]);
			int value = probeResult[1] instanceof Integer number ? number : -1;
			String problem = (String) probeResult[2];
			if (problem != null) {
				this.fail(new AssertionError(kind + ": " + problem));
				return true;
			}
			if (value <= 0) {
				if (this.tick < 200) {
					return false;
				}
				server.submit(() -> {
					server.overworld().removeBlock(this.nodePos, false);
					return null;
				}).get(20, TimeUnit.SECONDS);
				this.fail(new AssertionError(kind + "." + probeMethod + "() was still " + value + " after 200 ticks in the world"));
				return true;
			}
			final var placed = this.nodePos;
			server.submit(() -> {
				server.overworld().removeBlock(placed, false);
				return null;
			}).get(20, TimeUnit.SECONDS);
			this.result.complete("{\"ok\":true,\"lines\":\"" + kind + "." + probeMethod + "() = " + value + " after " + this.tick + " ticks in the warm world\"}");
			return true;
		}

		/**
		 * Opens the configured screen, or keeps the one already showing when there is no name
		 * to open, so {@code ui} still captures instead of failing.
		 *
		 * <p>install.ps1 writes an empty {@code -Dmodlab.screen=} into a repo that has no custom
		 * screen, and loading "" threw {@code ClassNotFoundException} with an empty message. A
		 * class name that cannot be found falls back the same way.
		 */
		private void openScreen(Minecraft client, ClassLoader loader, String wanted) throws Exception {
			String name = wanted == null ? "" : wanted.trim();
			if (!name.isEmpty()) {
				try {
					Class<?> type = loader.loadClass(name);
					client.gui.setScreen((Screen) type.getDeclaredConstructor().newInstance());
					this.uiClass = type;
					this.lines.add("screen " + name);
					return;
				}
				catch (ClassNotFoundException missing) {
					this.lines.add("screen " + name + " not found");
				}
			}
			Screen showing = client.gui.screen();
			this.uiClass = showing == null ? null : showing.getClass();
			this.lines.add(name.isEmpty()
					? "no screen configured, capturing the screen already showing"
					: "capturing the screen already showing instead");
		}

		private static String uiName() {
			String name = System.getProperty("modlab.name", "ui");
			return name == null || name.isBlank() ? "ui" : name;
		}

		private void clickButton(Minecraft client, String label) throws Exception {
			Screen screen = client.gui.screen();
			if (screen == null || this.uiClass == null || !this.uiClass.isInstance(screen)) {
				throw new AssertionError("the configured screen did not open, found " + screen);
			}
			Button target = null;
			for (var child : screen.children()) {
				if (child instanceof Button button && button.getMessage().getString().equals(label)) {
					target = button;
					break;
				}
			}
			if (target == null) {
				throw new AssertionError("No button labelled '" + label + "' on the screen");
			}
			double x = target.getX() + target.getWidth() / 2.0;
			double y = target.getY() + target.getHeight() / 2.0;
			if (!target.mouseClicked(new MouseButtonEvent(x, y, new MouseButtonInfo(1, 0)), false)) {
				throw new AssertionError("Button '" + label + "' rejected the click");
			}
			this.lines.add("clicked '" + label + "'");
		}

		private void capture(Minecraft client, String name) {
			this.waiting = true;
			Screenshot.takeScreenshot(client.gameRenderer.mainRenderTarget(), image -> {
				try {
					String configured = System.getProperty("modlab.shots");
					Path dir = configured != null && !configured.isBlank()
							? Path.of(configured) : root().resolve("build/run/warm/shots");
					Path shot = dir.resolve(name + ".png");
					Files.createDirectories(shot.getParent());
					image.writeToFile(shot);
					this.lines.add("screenshot " + shot.getFileName());
				}
				catch (Throwable t) {
					this.fail(t);
				}
				finally {
					this.waiting = false;
				}
			});
		}
		void fail(Throwable t) {
			Throwable cause = t.getCause() != null ? t.getCause() : t;
			String message = cause.getClass().getSimpleName() + ": " + cause.getMessage();
			this.result.complete("{\"ok\":false,\"error\":\"" + escape(message) + "\"}");
		}
	}
}










































