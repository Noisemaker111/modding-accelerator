package dev.modlab.bridge;

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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * The bridge: the lab control socket in a mod that can be added to a client run
 * that belongs to someone else, without touching that mod's sources.
 *
 * <p>Same wire protocol as {@code dev.modlab.client.LabControl}, kept small on
 * purpose: one line in, one JSON line out, new connection per command.
 * <ul>
 * <li>{@code root} -&gt; the directory the session is for</li>
 * <li>{@code ping} -&gt; {@code {"ok":true,"mod":"modlabbridge",...}}</li>
 * <li>{@code ui <class>} -&gt; opens that screen on the render thread</li>
 * <li>{@code screenshot} -&gt; writes a PNG and answers with its path</li>
 * <li>{@code quit} -&gt; stops the client</li>
 * </ul>
 *
 * <p>Port: {@code -Dmodlabbridge.port}, else {@code -Dprismlab.lab.port}, else
 * {@link #DEFAULT_PORT}. The separate property exists so the bridge can sit on a
 * different port from the host mod's own control mod in the same client.
 */
@Mod(value = LabBridge.MOD_ID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = LabBridge.MOD_ID, value = Dist.CLIENT)
public final class LabBridge {
	public static final String MOD_ID = "modlabbridge";
	public static final int DEFAULT_PORT = 27399;

	/** 854x480 at GUI scale 2 is the lab's fixed capture size. */
	private static final int SHOT_WIDTH = 854;
	private static final int SHOT_HEIGHT = 480;

	private static volatile boolean ready;
	private static int ticks;
	/** Set by the socket thread, advanced by the render thread, answered on the render thread. */
	private static volatile Capture pending;

	public LabBridge() {
		int port = port();
		if (port <= 0) {
			System.out.println("MODLABBRIDGE-OFF no port configured");
			return;
		}
		Thread server = new Thread(() -> serve(port), "modlabbridge-control");
		server.setDaemon(true);
		server.start();
		System.out.println("MODLABBRIDGE-START port=" + port);
	}

	@SubscribeEvent
	public static void onClientTick(ClientTickEvent.Post event) {
		ticks++;
		if (!ready && Minecraft.getInstance().screen != null
				&& (ticks > 40 || Minecraft.getInstance().screen instanceof TitleScreen)) {
			ready = true;
		}
		Capture capture = pending;
		if (capture != null) {
			try {
				capture.step(Minecraft.getInstance());
			}
			catch (Throwable t) {
				capture.fail(t);
			}
		}
	}

	/** {@code -Dmodlabbridge.port}, then the lab's own property, then the default. */
	private static int port() {
		for (String key : List.of("modlabbridge.port", "prismlab.lab.port")) {
			String text = System.getProperty(key);
			if (text != null && !text.isBlank()) {
				try {
					return Integer.parseInt(text.trim());
				}
				catch (NumberFormatException e) {
					System.out.println("MODLABBRIDGE-WARN bad " + key + "=" + text);
				}
			}
		}
		return DEFAULT_PORT;
	}

	/** Where {@code root} points: {@code -Dmodlab.root}, else the game directory. */
	private static Path root() {
		String configured = System.getProperty("modlab.root");
		if (configured != null && !configured.isBlank()) {
			return Path.of(configured);
		}
		return Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath();
	}

	/** Where captures go: {@code -Dmodlabbridge.shots}, else {@code <gameDir>/screenshots/modlabbridge}. */
	private static Path shotDir() {
		String configured = System.getProperty("modlabbridge.shots");
		if (configured != null && !configured.isBlank()) {
			return Path.of(configured);
		}
		return Minecraft.getInstance().gameDirectory.toPath().resolve("screenshots/modlabbridge");
	}

	private static void serve(int port) {
		// The socket only appears once a real screen is up: a client that is still
		// compiling shaders has no main render target to capture.
		long deadline = System.currentTimeMillis() + 300_000;
		while (!ready && System.currentTimeMillis() < deadline) {
			try {
				Thread.sleep(100);
			}
			catch (InterruptedException e) {
				return;
			}
		}
		try (ServerSocket socket = new ServerSocket(port, 8, InetAddress.getLoopbackAddress())) {
			System.out.println("MODLABBRIDGE-READY " + port);
			System.out.flush();
			while (true) {
				try (Socket client = socket.accept();
						BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
						PrintWriter out = new PrintWriter(client.getOutputStream(), true, StandardCharsets.UTF_8)) {
					String line = in.readLine();
					if (line == null) {
						continue;
					}
					out.println(handle(line.trim()));
				}
				catch (Exception e) {
					System.out.println("MODLABBRIDGE-WARN " + e);
				}
			}
		}
		catch (Exception e) {
			System.out.println("MODLABBRIDGE-ERROR " + e);
		}
	}

	private static String handle(String line) {
		try {
			if (line.isEmpty() || line.equals("ping")) {
				return "{\"ok\":true,\"pong\":true,\"mod\":\"" + MOD_ID + "\",\"port\":" + port()
						+ ",\"root\":\"" + escape(root().toString()) + "\"}";
			}
			if (line.startsWith("root")) {
				return "{\"root\":\"" + escape(root().toString()) + "\",\"mod\":\"" + MOD_ID + "\"}";
			}
			if (line.startsWith("quit")) {
				Minecraft.getInstance().stop();
				return "{\"ok\":true,\"quit\":true,\"mod\":\"" + MOD_ID + "\"}";
			}
			if (line.startsWith("ui")) {
				return openScreen(line.length() > 2 ? line.substring(2).trim() : "");
			}
			if (line.startsWith("screenshot")) {
				String name = line.length() > "screenshot".length() ? line.substring("screenshot".length()).trim() : "";
				return capture(name.isEmpty() ? "shot" : name);
			}
			return "{\"ok\":false,\"error\":\"unknown command " + escape(line) + "\"}";
		}
		catch (Throwable t) {
			Throwable cause = t.getCause() != null ? t.getCause() : t;
			return "{\"ok\":false,\"error\":\"" + escape(cause.getClass().getSimpleName() + ": " + cause.getMessage()) + "\"}";
		}
	}

	/** Runs a body on the render thread and waits for it, so screenshots see the current frame. */
	private static <T> T onRenderThread(ThrowingSupplier<T> body, long seconds) throws Exception {
		CompletableFuture<T> future = new CompletableFuture<>();
		Minecraft.getInstance().execute(() -> {
			try {
				future.complete(body.get());
			}
			catch (Throwable t) {
				future.completeExceptionally(t);
			}
		});
		return future.get(seconds, TimeUnit.SECONDS);
	}

	private static String openScreen(String className) throws Exception {
		if (className.isEmpty()) {
			return "{\"ok\":false,\"error\":\"ui needs a class name\"}";
		}
		Object screen = onRenderThread(() -> {
			Class<?> type = loadScreenClass(className);
			Screen made = (Screen) type.getDeclaredConstructor().newInstance();
			Minecraft.getInstance().setScreen(made);
			return made;
		}, 30);
		return "{\"ok\":true,\"mod\":\"" + MOD_ID + "\",\"screen\":\"" + escape(screen.getClass().getName()) + "\"}";
	}

	/**
	 * A class from the host mod is not on this mod's loader, so try the game's
	 * loader as well as our own before giving up.
	 */
	private static Class<?> loadScreenClass(String name) throws ClassNotFoundException {
		ClassNotFoundException failure = null;
		for (ClassLoader loader : List.of(Minecraft.class.getClassLoader(), LabBridge.class.getClassLoader())) {
			try {
				return Class.forName(name, true, loader);
			}
			catch (ClassNotFoundException e) {
				failure = e;
			}
		}
		throw failure;
	}

	private static String capture(String name) throws Exception {
		Capture capture = new Capture(name);
		pending = capture;
		try {
			return capture.result.get(60, TimeUnit.SECONDS);
		}
		finally {
			pending = null;
		}
	}

	/** Samples the frame sparsely: a target that was just resized or cleared is all zeros. */
	private static boolean isBlack(int[] pixels) {
		if (pixels == null || pixels.length == 0) {
			return true;
		}
		for (int i = 0; i < pixels.length; i += 977) {
			if ((pixels[i] & 0xFFFFFF) != 0) {
				return false;
			}
		}
		return true;
	}

	/**
	 * One capture, advanced one client tick at a time on the render thread. The
	 * resize happens on tick 1 and the frame is read back a few ticks later: a
	 * resize made in the same frame as the readback comes back black.
	 */
	private static final class Capture {
		private final String name;
		private final CompletableFuture<String> result = new CompletableFuture<>();
		private int ticks;
		private int attempts;

		Capture(String name) {
			this.name = name;
		}

		void step(Minecraft client) throws IOException {
			this.ticks++;
			if (this.ticks == 1) {
				client.options.guiScale().set(2);
				client.getWindow().setWindowed(SHOT_WIDTH, SHOT_HEIGHT);
				client.resizeDisplay();
				return;
			}
			if (this.ticks < 4 || this.ticks % 4 != 0) {
				return;
			}
			this.attempts++;
			Path dir = shotDir();
			Files.createDirectories(dir);
			NativeImage image = Screenshot.takeScreenshot(client.getMainRenderTarget());
			try {
				if (isBlack(image.getPixelsRGBA())) {
					if (this.attempts < 4) {
						return;
					}
					this.fail(new IllegalStateException("frame read back black after " + this.attempts + " attempts"));
					return;
				}
				Path file = dir.resolve(this.name + ".png").toAbsolutePath();
				image.writeToFile(file);
				this.result.complete("{\"ok\":true,\"mod\":\"" + MOD_ID + "\",\"file\":\"" + escape(file.toString())
						+ "\",\"width\":" + image.getWidth() + ",\"height\":" + image.getHeight()
						+ ",\"attempts\":" + this.attempts + "}");
			}
			finally {
				image.close();
			}
		}

		void fail(Throwable t) {
			this.result.complete("{\"ok\":false,\"mod\":\"" + MOD_ID + "\",\"error\":\""
					+ escape(t.getClass().getSimpleName() + ": " + t.getMessage()) + "\"}");
		}
	}

	private static String escape(String text) {
		StringBuilder out = new StringBuilder();
		for (char c : text.toCharArray()) {
			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n', '\r' -> out.append(' ');
				default -> out.append(c);
			}
		}
		return out.toString();
	}

	@FunctionalInterface
	private interface ThrowingSupplier<T> {
		T get() throws Exception;
	}
}
