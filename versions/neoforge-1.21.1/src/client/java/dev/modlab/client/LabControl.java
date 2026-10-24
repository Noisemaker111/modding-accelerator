package dev.modlab.client;

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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;


/**
 * NeoForge 1.21.1 build of the warm-client harness. Same socket protocol and same step
 * list as the 26.3 parent, on the port from {@code -Dprismlab.lab.port}, with the
 * 1.21.1 calls: {@code client.screen}, {@code client.setScreen},
 * {@code getMainRenderTarget}, {@code getToasts}, {@code resizeDisplay}.
 *
 * <p>The Fabric tick callback is gone: {@code ClientTickEvent.Post} on the NeoForge game
 * bus calls {@link #clientTick()}. {@code HotUi} recompiles the mod's sources and asks
 * {@code LabAgent} to redefine the loaded bodies, so a screen edit lands without a restart.
 */
public final class LabControl {
	private static final int SHOT_WIDTH = 854;
	private static final int SHOT_HEIGHT = 480;
	/** Ticks between settle samples; the async screenshot copy returns black if back-to-back. */
	private static final int SETTLE_SPACING = 6;
	/** The frame is dithered by a few levels per channel; a real scene change is far larger. */
	private static final int SETTLE_MAX_DELTA = 16;

	private static final LinkedBlockingQueue<Scenario> QUEUE = new LinkedBlockingQueue<>();
	private static volatile boolean ready;
	private static int ticks;
	private static Scenario current;

	private LabControl() {
	}

	public static void startIfRequested() {
		String portText = System.getProperty("prismlab.lab.port");
		if (portText == null || portText.isBlank()) {
			return;
		}
		int port = Integer.parseInt(portText.trim());
		Thread server = new Thread(() -> serve(port), "prismlab-lab-control");
		server.setDaemon(true);
		server.start();
	}

	/**
	 * Newest source file under the node and the parent trees it inherits from, so an edit to
	 * either one recompiles. The node's own files shadow parent files of the same path.
	 */
	private static long sourceStamp(Path root) throws IOException {
		long stamp = 0L;
		List<Path> bases = new ArrayList<>();
		for (String dir : List.of("src/main/java", "src/client/java")) {
			bases.add(root.resolve(dir));
			Path parent = root.resolve("../..").normalize();
			bases.add(parent.resolve(dir));
		}
		for (Path base : bases) {
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

	/** Where captures go: {@code -Dmodlab.shots}, else build/run/warm/shots under the project root. */
	private static Path shotDir() {
		String configured = System.getProperty("modlab.shots");
		if (configured != null && !configured.isBlank()) {
			return Path.of(configured);
		}
		return root().resolve("build/run/warm/shots");
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

	public static void clientTick() {
		ticks++;
		// 1.21.11 opens the accessibility-onboarding screen on a fresh run directory, so
		// "a real screen is up" is the signal, not specifically the title screen.
		if (!ready && Minecraft.getInstance().screen != null && (ticks > 40 || Minecraft.getInstance().screen instanceof TitleScreen)) {
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
		private BlockPos placedPos;
		private String[] steps;
		private int index;
		private int pending;
		private boolean worldRequested;
		private int[] settleSample;
		private int settleAttempts;
		private boolean frozen;
		private int settleDiff;
		private Screen previous;
		private Class<?> uiClass;
		private boolean waiting;
		private boolean ok = true;
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
					this.previous = client.screen;
					client.options.guiScale().set(2);
					client.getWindow().setWindowed(SHOT_WIDTH, SHOT_HEIGHT);
					client.resizeDisplay();
				}
				case 4 -> {
					client.options.tutorialStep = net.minecraft.client.tutorial.TutorialSteps.NONE;
					client.options.pauseOnLostFocus = false;
					client.getToasts().clear();
					this.lines.add("main: " + HotUi.redefineMain(root(), sourceStamp(root())));
					this.uiClass = Class.forName(System.getProperty("modlab.screen", ""), true, LabControl.class.getClassLoader());
					client.setScreen((Screen) this.uiClass.getDeclaredConstructor().newInstance());
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
					client.setScreen(this.previous);
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
				client.resizeDisplay();
				client.getToasts().clear();
			}
			if (this.pending > 0) {
				this.pending--;
				return false;
			}
			if (this.index >= this.steps.length) {
				if (this.frozen) {
					setFrozen(client, false);
				}
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
					this.lines.add("settled (max channel delta " + this.settleDiff + ")");
					this.index++;
					return false;
				}
				return false;
			}
			if (!ensureWorld(client)) {
				return false;
			}
			if (step.startsWith("place=")) {
				String id = step.substring(6).trim();
				var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id));
				if (block == null) {
					throw new AssertionError("unknown block " + id);
				}
				var server = client.getSingleplayerServer();
				// Level view first, so the block always lands three blocks ahead at the same
				// height instead of creeping a little lower on every run.
				client.player.setXRot(0.0f);
				var pos = spotInFrontOfPlayer(client);
				var previous = this.placedPos;
				server.submit(() -> {
					// Clear first: an old node at this spot would keep its charge and make
					// "charge after 40 ticks" meaningless. Also drop the node the last step
					// placed, so repeated runs do not pile blocks up in front of the camera.
					if (previous != null) {
						server.overworld().removeBlock(previous, false);
					}
					server.overworld().removeBlock(pos, false);
					server.overworld().setBlockAndUpdate(pos, block.defaultBlockState());
					return null;
				}).get(20, TimeUnit.SECONDS);
				this.placedPos = pos;
				aimAt(client, pos);
				this.lines.add("placed " + id + " at " + pos.toShortString());
			}
			else if (step.startsWith("assert=")) {
				String method = step.substring(7).trim();
				var server = client.getSingleplayerServer();
				if (this.placedPos == null) {
					throw new AssertionError("assert before place");
				}
				Object value = server.submit(() -> {
					var entity = server.overworld().getBlockEntity(this.placedPos);
					if (entity == null) {
						return null;
					}
					try {
						return entity.getClass().getMethod(method).invoke(entity);
					}
					catch (ReflectiveOperationException e) {
						return "probe failed: " + e.getMessage();
					}
				}).get(20, TimeUnit.SECONDS);
				if (!(value instanceof Integer number) || number <= 0) {
					throw new AssertionError(method + "() was " + value);
				}
				this.lines.add("assert " + method + "() = " + value);
			}
			else if (step.startsWith("screen=")) {
				String name = step.substring(7).trim();
				Class<?> type = Class.forName(name, true, LabControl.class.getClassLoader());
				this.uiClass = type;
				client.setScreen((Screen) type.getDeclaredConstructor().newInstance());
				this.lines.add("screen " + name);
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
		 * True when two frames {@link #SETTLE_SPACING} ticks apart are the same picture, so a
		 * screenshot cannot catch a half-drawn frame. Fails after 20 tries rather than save a
		 * stale or black shot.
		 *
		 * <p>1.21.1's {@code Screenshot.takeScreenshot} is synchronous (it returns the image),
		 * so there is no readback race to wait out here.
		 */
		private boolean settled(Minecraft client) throws Exception {
			// A live world never holds still (clouds, sun, entity animation), so the frame
			// comparison only means something with the level frozen; the integrated server's
			// tick rate manager does that.
			if (!this.frozen) {
				setFrozen(client, true);
				client.getToasts().clear();
			}
			int[] after = null;
			try {
				after = pixels(client);
			}
			catch (Throwable t) {
				this.settleAttempts++;
			}
			boolean black = isBlack(after);
			int delta = -1;
			if (this.settleAttempts > 0 && !black && this.settleSample != null) {
				delta = maxChannelDelta(this.settleSample, after);
				if (delta <= SETTLE_MAX_DELTA) {
					this.settleAttempts = 0;
					this.settleDiff = delta;
					return true;
				}
				if (this.settleAttempts <= 3) {
					dump(this.settleSample, "settle-frame-a");
					dump(after, "settle-frame-b");
				}
			}
			// A black frame is the readback not being ready yet, not a scene change.
			if (!black) {
				this.settleSample = after;
			}
			this.settleAttempts++;
			if (this.settleAttempts > 20) {
				setFrozen(client, false);
				throw new AssertionError("frame never settled after 20 tries (last max channel delta " + delta + ")");
			}
			this.pending = SETTLE_SPACING;
			return false;
		}

		/** One synchronous frame grab, RGBA per pixel. */
		private static int[] pixels(Minecraft client) throws Exception {
			NativeImage image = Screenshot.takeScreenshot(client.getMainRenderTarget());
			try {
				return image.getPixelsRGBA();
			}
			finally {
				image.close();
			}
		}

		/** Debug aid: writes the two frames that did not match to .modaccel/settle-frames. */
		private static void dump(int[] argb, String name) {
			try {
				Path dir = root().resolve(".modaccel/settle-frames");
				Files.createDirectories(dir);
				java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(SHOT_WIDTH, SHOT_HEIGHT, java.awt.image.BufferedImage.TYPE_INT_ARGB);
				image.setRGB(0, 0, SHOT_WIDTH, SHOT_HEIGHT, argb, 0, SHOT_WIDTH);
				javax.imageio.ImageIO.write(image, "png", dir.resolve(name + ".png").toFile());
			}
			catch (Throwable ignored) {
			}
		}

		/** Largest per-channel difference between two frames; 1.21.11 dithers the output by a few levels. */
		private static int maxChannelDelta(int[] a, int[] b) {
			int max = 0;
			int count = Math.min(a.length, b.length);
			for (int i = 0; i < count; i++) {
				int p = a[i];
				int q = b[i];
				if (p == q) {
					continue;
				}
				int d = Math.abs((p & 0xFF) - (q & 0xFF));
				d = Math.max(d, Math.abs(((p >> 8) & 0xFF) - ((q >> 8) & 0xFF)));
				d = Math.max(d, Math.abs(((p >> 16) & 0xFF) - ((q >> 16) & 0xFF)));
				if (d > max) {
					max = d;
					if (max >= 255) {
						break;
					}
				}
			}
			return max;
		}

		private static boolean isBlack(int[] pixels) {
			if (pixels == null || pixels.length == 0) {
				return true;
			}
			// Sample the frame sparsely: a cleared target is all zeros.
			for (int i = 0; i < pixels.length; i += 977) {
				if ((pixels[i] & 0xFFFFFF) != 0) {
					return false;
				}
			}
			return true;
		}

		private void setFrozen(Minecraft client, boolean frozen) {
			var server = client.getSingleplayerServer();
			if (server != null) {
				server.tickRateManager().setFrozen(frozen);
			}
			this.frozen = frozen;
		}

		/**
		 * True when the client is in a world. The first time round it creates the "lab"
		 * world through vanilla's own world-creation path if the save is missing.
		 */
		private boolean ensureWorld(Minecraft client) {
			if (client.getSingleplayerServer() != null && client.player != null) {
				return true;
			}
			Path save = client.gameDirectory.toPath().resolve("saves/lab/level.dat");
			if (!this.worldRequested) {
				this.worldRequested = true;
				if (Files.isRegularFile(save)) {
					client.createWorldOpenFlows().openWorld("lab", () -> System.out.println("PRISMLAB-ERROR could not open the lab world"));
				}
				else {
					createLabWorld(client);
				}
			}
			this.waits++;
			if (this.waits > 2400) {
				this.fail(new AssertionError("could not open the lab world"));
				// keep waiting: the caller checks the result future
				return false;
			}
			return false;
		}

		/** Vanilla's "create new world" path, without a GUI. */
		private void createLabWorld(Minecraft client) {
			LevelSettings settings = new LevelSettings("lab", GameType.CREATIVE, false, Difficulty.NORMAL, true,
					new GameRules(), WorldDataConfiguration.DEFAULT);
			WorldOpenFlows flows = client.createWorldOpenFlows();
			flows.createFreshLevel("lab", settings, WorldOptions.defaultWithRandomSeed(),
					WorldPresets::createNormalWorldDimensions, client.screen);
			this.lines.add("created world lab");
		}

		private boolean stepNode(Minecraft client) throws Exception {
			var server = client.getSingleplayerServer();
			if (server == null || client.player == null) {
				if (!ensureWorld(client)) {
					return false;
				}
				return false;
			}
			if (this.placedPos == null) {
				String blockId = System.getProperty("modlab.block");
				if (blockId == null || blockId.isBlank()) {
					this.fail(new AssertionError("no -Dmodlab.block=<namespace:id> configured"));
					return true;
				}
				var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(blockId));
				if (block == null) {
					this.fail(new AssertionError("unknown block " + blockId));
					return true;
				}
				var pos = spotInFrontOfPlayer(client);
				server.submit(() -> {
					server.overworld().removeBlock(pos, false);
					server.overworld().setBlockAndUpdate(pos, block.defaultBlockState());
					return null;
				}).get(20, TimeUnit.SECONDS);
				this.placedPos = pos;
				this.tick = 0;
				return false;
			}
			if (this.tick < 40) {
				return false;
			}
			String probeMethod = System.getProperty("modlab.probe", "charge");
			Object[] probeResult = server.submit(() -> {
				var entity = server.overworld().getBlockEntity(this.placedPos);
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
					server.overworld().removeBlock(this.placedPos, false);
					return null;
				}).get(20, TimeUnit.SECONDS);
				this.fail(new AssertionError(kind + "." + probeMethod + "() was still " + value + " after 200 ticks in the world"));
				return true;
			}
			final var placed = this.placedPos;
			server.submit(() -> {
				server.overworld().removeBlock(placed, false);
				return null;
			}).get(20, TimeUnit.SECONDS);
			this.result.complete("{\"ok\":true,\"lines\":\"" + kind + "." + probeMethod + "() = " + value + " after " + this.tick + " ticks in the warm world\"}");
			return true;
		}

		/** Three blocks along the player's line of sight, so the screenshot actually shows the block. */
		private static BlockPos spotInFrontOfPlayer(Minecraft client) {
			var view = client.player.getViewVector(1.0f);
			return BlockPos.containing(
					client.player.getX() + view.x * 3.0,
					client.player.getEyeY() - 0.8 + view.y * 3.0,
					client.player.getZ() + view.z * 3.0);
		}

		/**
		 * Points the real client camera at the block that was just placed, so the capture
		 * shows the demo block rather than whatever the player spawned facing.
		 */
		private static void aimAt(Minecraft client, BlockPos pos) {
			var eye = client.player.getEyePosition(1.0f);
			double dx = pos.getX() + 0.5 - eye.x;
			double dy = pos.getY() + 0.5 - eye.y;
			double dz = pos.getZ() + 0.5 - eye.z;
			double flat = Math.sqrt(dx * dx + dz * dz);
			client.player.setYRot((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
			client.player.setXRot((float) (-Math.toDegrees(Math.atan2(dy, flat))));
		}

		private static String uiName() {
			String name = System.getProperty("modlab.name", "ui");
			return name == null || name.isBlank() ? "ui" : name;
		}

		private void clickButton(Minecraft client, String label) throws Exception {
			Screen screen = client.screen;
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
			// 1.21.11 counts the primary mouse button as 0 (the 26.3 parent needs 1).
			if (!target.mouseClicked(x, y, 0)) {
				throw new AssertionError("Button '" + label + "' rejected the click");
			}
			this.lines.add("clicked '" + label + "'");
		}

		private void capture(Minecraft client, String name) {
			try {
				NativeImage image = Screenshot.takeScreenshot(client.getMainRenderTarget());
				try {
					if (isBlack(image.getPixelsRGBA())) {
						throw new IllegalStateException("screenshot " + name + " came back black (readback not ready)");
					}
					Path shot = shotDir().resolve(name + ".png");
					Files.createDirectories(shot.getParent());
					image.writeToFile(shot);
					this.lines.add("screenshot " + shot.getFileName() + " " + image.getWidth() + "x" + image.getHeight());
				}
				finally {
					image.close();
				}
			}
			catch (Throwable t) {
				this.fail(t);
			}
		}

		void fail(Throwable t) {
			Throwable cause = t.getCause() != null ? t.getCause() : t;
			String message = cause.getClass().getSimpleName() + ": " + cause.getMessage();
			if (this.frozen) {
				setFrozen(Minecraft.getInstance(), false);
			}
			this.ok = false;
			this.result.complete("{\"ok\":false,\"error\":\"" + escape(message) + "\"}");
		}
	}
}
