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
		// Standalone CLI subcommands (index/stats/search/xref) run the v2 index engine directly and
		// exit, without entering the JSON-RPC daemon loop.
		if (Cli.run(argv)) {
			return;
		}

		// Reserve the real stdout for the protocol; send everything else to stderr.
		PrintStream protocolOut = new PrintStream(new FileOutputStream(FileDescriptor.out), true,
				StandardCharsets.UTF_8);
		System.setOut(System.err);

		Session session = new Session();
		Rpc rpc = new Rpc(session, protocolOut);

		String input = null;
		boolean export = true;
		boolean temp = false;
		boolean noUsage = false;
		boolean lean = false;
		boolean keepModel = true;
		boolean showInconsistentCode = true;
		boolean v2 = false;
		String rgPath = null;
		for (int i = 0; i < argv.length; i++) {
			String a = argv[i];
			if ("--no-export".equals(a)) {
				export = false;
			} else if ("--temp".equals(a)) {
				temp = true;
			} else if ("--no-usage".equals(a)) {
				noUsage = true;
			} else if ("--lean".equals(a)) {
				lean = true;
			} else if ("--drop-model".equals(a)) {
				keepModel = false; // RAM-constrained: drop the model after export (slow first edit)
			} else if ("--no-inconsistent-code".equals(a)) {
				showInconsistentCode = false;
			} else if ("--v2".equals(a) || "--fast".equals(a)) {
				v2 = true;
			} else if ("--rg".equals(a) && i + 1 < argv.length) {
				rgPath = argv[++i];
			} else if (a != null && a.startsWith("--rg=")) {
				rgPath = a.substring("--rg=".length());
			} else if (input == null && a != null && !a.isEmpty()) {
				input = a;
			}
		}

		if (rgPath != null && !rgPath.isEmpty()) {
			session.setRgPath(rgPath);
		}
		if (input != null) {
			session.setInitialInput(input);
			session.setExport(export);
			session.setTemp(temp);
			session.setNoUsage(noUsage);
			session.setLean(lean);
			session.setKeepModel(keepModel);
			session.setShowInconsistentCode(showInconsistentCode);
			session.setV2(v2);
			try {
				session.loadProject(null);
			} catch (Exception e) {
				System.err.println("[jadxd] preload failed: " + e);
				rpc.notify("loadError", java.util.Map.of("message", String.valueOf(e.getMessage())));
			}
		}

		rpc.loop(System.in);
		// stdin closed (client gone) — exit promptly rather than lingering on any non-daemon thread.
		System.exit(0);
	}

	private Main() {
	}
}
