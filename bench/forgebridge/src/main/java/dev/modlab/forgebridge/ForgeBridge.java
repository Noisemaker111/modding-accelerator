package dev.modlab.forgebridge;

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
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * The Forge 1.20.1 twin of {@code bench/bridge}: the lab control socket in a mod
 * that can be dropped into a client run that belongs to someone else's mod,
 * without touching that mod's sources.
 *
 * <p>Same wire protocol as {@code dev.modlab.client.LabControl}: one line in, one
 * JSON line out, a new connection per command.
 * <ul>
 * <li>{@code root} -&gt; the directory the session is for</li>
 * <li>{@code ping} -&gt; {@code {"ok":true,"mod":"modlabbridge",...}}</li>
 * <li>{@code screenshot [name]} -&gt; writes a PNG and answers with its path</li>
 * <li>{@code quit} -&gt; stops the client</li>
 * </ul>
 *
 * <p>Properties: {@code -Dmodlabbridge.port} (default 27699),
 * {@code -Dmodlabbridge.shots} (where PNGs go), {@code -Dmodlab.root} (what
 * {@code root} answers), {@code -Dmodlabbridge.size=WxH} (optional, resize the
 * window before the shot).
 *
 * <p>All Minecraft work happens on the client thread: the socket thread only
 * parks a request in {@link #pending}, and {@link #onClientTick} advances it.
 */
@Mod(ForgeBridge.MOD_ID)
@Mod.EventBusSubscriber(modid = ForgeBridge.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ForgeBridge {
	public static final String MOD_ID = "modlabbridge";
	public static final int DEFAULT_PORT = 27699;

	private static volatile boolean ready;
	private static int ticks;
	/** Set by the socket thread, advanced by the client thread, answered there. */
	private static volatile Capture pending;

	public ForgeBridge() {
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
	public static void onClientTick(TickEvent.ClientTickEvent event) {
		if (event.phase != TickEvent.Phase.END) {
			return;
		}
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
			return Path.of(configured).toAbsolutePath();
		}
		return Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath().normalize();
	}

	/** Where captures go: {@code -Dmodlabbridge.shots}, else {@code <gameDir>/screenshots/modlabbridge}. */
	private static Path shotDir() {
		String configured = System.getProperty("modlabbridge.shots");
		if (configured != null && !configured.isBlank()) {
			return Path.of(configured).toAbsolutePath();
		}
		return Minecraft.getInstance().gameDirectory.toPath().resolve("screenshots/modlabbridge").toAbsolutePath();
	}

	private static void serve(int port) {
		// The socket only appears once a real screen is up: a client that is still
		// loading resources has no frame worth capturing.
		long deadline = System.currentTimeMillis() + 300_000;
		while (!ready && System.currentTimeMillis() < deadline) {
			try {
				Thread.sleep(100);
			}
			catch (InterruptedException e) {
				return;
			}
		}
		// Both families: a caller may ask for 127.0.0.1 while the JVM prefers ::1
		// (Forge launches with -Djava.net.preferIPv6Addresses=system).
		boolean bound = bind(port, "::1");
		bound = bind(port, "127.0.0.1") || bound;
		if (!bound) {
			System.out.println("MODLABBRIDGE-ERROR could not bind loopback port " + port);
			return;
		}
		System.out.println("MODLABBRIDGE-READY " + port);
		System.out.flush();
	}

	/** Listens on one loopback address; the accept loop runs on its own daemon thread. */
	private static boolean bind(int port, String address) {
		try {
			InetAddress loopback = InetAddress.getByName(address);
			ServerSocket socket = new ServerSocket(port, 8, loopback);
			Thread thread = new Thread(() -> accept(socket), "modlabbridge-" + address);
			thread.setDaemon(true);
			thread.start();
			return true;
		}
		catch (Exception e) {
			System.out.println("MODLABBRIDGE-WARN " + address + ": " + e.getMessage());
			return false;
		}
	}

	private static void accept(ServerSocket socket) {
		try (socket) {
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
			System.out.println("MODLABBRIDGE-WARN accept ended: " + e);
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
			if (line.startsWith("screenshot")) {
				String name = line.length() > "screenshot".length() ? line.substring("screenshot".length()).trim() : "";
				return capture(name.isEmpty() ? "shot" : name);
			}
			return "{\"ok\":false,\"mod\":\"" + MOD_ID + "\",\"error\":\"unknown command " + escape(line) + "\"}";
		}
		catch (Throwable t) {
			Throwable cause = t.getCause() != null ? t.getCause() : t;
			return "{\"ok\":false,\"mod\":\"" + MOD_ID + "\",\"error\":\""
					+ escape(cause.getClass().getSimpleName() + ": " + cause.getMessage()) + "\"}";
		}
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

	/** Optional {@code -Dmodlabbridge.size=854x480}; null means "keep the window as it is". */
	private static int[] wantedSize() {
		String text = System.getProperty("modlabbridge.size");
		if (text == null || text.isBlank()) {
			return null;
		}
		String[] parts = text.trim().toLowerCase().split("x");
		if (parts.length != 2) {
			System.out.println("MODLABBRIDGE-WARN bad modlabbridge.size=" + text);
			return null;
		}
		try {
			return new int[] { Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()) };
		}
		catch (NumberFormatException e) {
			System.out.println("MODLABBRIDGE-WARN bad modlabbridge.size=" + text);
			return null;
		}
	}

	/**
	 * One capture, advanced one client tick at a time on the client thread. A
	 * resize happens on the first tick and the frame is read back a few ticks
	 * later: a resize made in the same frame as the readback comes back black.
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
				int[] size = wantedSize();
				if (size != null) {
					client.options.guiScale().set(2);
					client.getWindow().setWindowed(size[0], size[1]);
					client.resizeDisplay();
					return;
				}
			}
			if (this.ticks < 2 || this.ticks % 2 != 0) {
				return;
			}
			if (this.attempts >= 8) {
				this.fail(new IllegalStateException("frame read back black after " + this.attempts + " attempts"));
				return;
			}
			this.attempts++;
			Path dir = shotDir();
			Files.createDirectories(dir);
			NativeImage image = Screenshot.takeScreenshot(client.getMainRenderTarget());
			try {
				if (isBlack(image.getPixelsRGBA())) {
					if (this.attempts < 8) {
						return;
					}
					this.fail(new IllegalStateException("frame read back black after " + this.attempts + " attempts"));
					return;
				}
				Path file = dir.resolve(this.name + ".png").toAbsolutePath();
				image.writeToFile(file);
				this.result.complete("{\"ok\":true,\"mod\":\"" + MOD_ID + "\",\"file\":\"" + escape(file.toString())
						+ "\",\"width\":" + image.getWidth() + ",\"height\":" + image.getHeight()
						+ ",\"bytes\":" + Files.size(file) + ",\"attempts\":" + this.attempts + "}");
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
}
