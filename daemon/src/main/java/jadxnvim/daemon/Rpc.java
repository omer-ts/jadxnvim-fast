package jadxnvim.daemon;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Newline-delimited JSON-RPC transport.
 *
 * <p>Each line read from the input stream is a request object
 * {@code {"id":N,"method":"...","params":{...}}}. A request with an {@code id} gets exactly one
 * reply ({@code {"id":N,"result":...}} or {@code {"id":N,"error":{"message":...}}}). Requests
 * without an {@code id} are notifications and produce no reply. The session may also push its own
 * notifications (e.g. streamed search hits) via the emitter.
 */
public final class Rpc {

	private final Session session;
	private final PrintStream out;
	private final Gson gson = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

	public Rpc(Session session, PrintStream out) {
		this.session = session;
		this.out = out;
		this.session.setEmitter(this::notify);
	}

	public void loop(InputStream in) throws IOException {
		BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
		String line;
		while ((line = reader.readLine()) != null) {
			String trimmed = line.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			handleLine(trimmed);
		}
	}

	private void handleLine(String line) {
		JsonElement id = JsonNull.INSTANCE;
		try {
			JsonObject req = JsonParser.parseString(line).getAsJsonObject();
			if (req.has("id") && !req.get("id").isJsonNull()) {
				id = req.get("id");
			}
			String method = req.get("method").getAsString();
			JsonObject params = req.has("params") && req.get("params").isJsonObject()
					? req.getAsJsonObject("params")
					: new JsonObject();

			Object result = session.dispatch(method, params);
			if (!id.isJsonNull()) {
				sendResult(id, result);
			}
		} catch (Throwable t) {
			String msg = t.getMessage() != null ? t.getMessage() : t.toString();
			if (!id.isJsonNull()) {
				sendError(id, msg);
			} else {
				System.err.println("[jadxd] error handling notification: " + msg);
			}
		}
	}

	private void sendResult(JsonElement id, Object result) {
		JsonObject o = new JsonObject();
		o.add("id", id);
		o.add("result", gson.toJsonTree(result));
		send(o);
	}

	private void sendError(JsonElement id, String message) {
		JsonObject o = new JsonObject();
		o.add("id", id);
		JsonObject err = new JsonObject();
		err.addProperty("message", message);
		o.add("error", err);
		send(o);
	}

	/** Push an unsolicited notification to the client. */
	public void notify(String method, Object params) {
		JsonObject o = new JsonObject();
		o.addProperty("method", method);
		o.add("params", gson.toJsonTree(params));
		send(o);
	}

	private synchronized void send(JsonObject obj) {
		out.println(gson.toJson(obj));
		out.flush();
	}
}
