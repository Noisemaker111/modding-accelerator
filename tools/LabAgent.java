import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Loaded with -javaagent, this holds the Instrumentation handle and answers redefinition
 * requests over loopback so the mod's own class bodies can be swapped without a restart.
 * Fabric's class loader does not expose this jar to the game, so the agent talks over a
 * socket instead of being called directly.
 */
public final class LabAgent {
	private LabAgent() {
	}

	public static void premain(String args, Instrumentation instrumentation) {
		startServer(instrumentation);
	}

	public static void agentmain(String args, Instrumentation instrumentation) {
		startServer(instrumentation);
	}

	private static void startServer(Instrumentation instrumentation) {
		String portText = System.getProperty("prismlab.agent.port");
		if (portText == null || portText.isBlank()) {
			return;
		}
		int port = Integer.parseInt(portText.trim());
		Thread thread = new Thread(() -> serve(port, instrumentation), "prismlab-agent");
		thread.setDaemon(true);
		thread.start();
	}

	private static void serve(int port, Instrumentation instrumentation) {
		try (ServerSocket server = new ServerSocket(port, 4, InetAddress.getLoopbackAddress())) {			while (true) {
				try (Socket socket = server.accept();
						BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
						PrintWriter out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8)) {
					String line = in.readLine();
					if (line == null) {
						continue;
					}
					String[] parts = line.split(" ", 3);
					if (parts.length < 3 || !parts[0].equals("redefine")) {
						out.println("error: bad request");
						continue;
					}
					Class<?> target = null;
					for (Class<?> type : instrumentation.getAllLoadedClasses()) {
						if (type.getName().equals(parts[1])) {
							target = type;
							break;
						}
					}
					if (target == null) {
						out.println("skip");
						continue;
					}
					try {
						byte[] bytes = Base64.getDecoder().decode(parts[2]);
						instrumentation.redefineClasses(new ClassDefinition(target, bytes));
						out.println("ok");
					}
					catch (Throwable t) {
						out.println("error: " + t.getClass().getSimpleName() + ": " + t.getMessage());
					}
				}
			}
		}
		catch (Exception e) {
			// the game going away is not an error worth reporting
		}
	}
}


