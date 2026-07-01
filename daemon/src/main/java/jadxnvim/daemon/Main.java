package jadxnvim.daemon;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Entry point for the jadxd daemon.
 *
 * <p>The daemon speaks newline-delimited JSON-RPC over stdin/stdout. Because jadx (and its
 * dependencies) may write to {@code System.out}, we capture the real stdout for the protocol and
 * redirect {@code System.out} to stderr so stray prints can never corrupt the JSON stream.
 */
public final class Main {

	public static void main(String[] argv) throws Exception {
		// Reserve the real stdout for the protocol; send everything else to stderr.
		PrintStream protocolOut = new PrintStream(new FileOutputStream(FileDescriptor.out), true,
				StandardCharsets.UTF_8);
		System.setOut(System.err);

		Session session = new Session();
		Rpc rpc = new Rpc(session, protocolOut);

		boolean preload = argv.length >= 1 && argv[0] != null && !argv[0].isEmpty();
		if (preload) {
			session.setInitialInput(argv[0]);
			try {
				session.loadProject(null);
			} catch (Exception e) {
				System.err.println("[jadxd] preload failed: " + e);
			}
		}

		rpc.loop(System.in);
	}

	private Main() {
	}
}
