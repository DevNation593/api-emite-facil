package com.example.einvoice.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Receptor de webhooks simulado: un servidor HTTP local, único por JVM. Cada prueba usa una ruta
 * propia; sin guion, responde 200 a todo.
 */
public final class WebhookReceiverStub {

	private static final WebhookReceiverStub INSTANCE = new WebhookReceiverStub();

	/** Una respuesta del guion. */
	public record Reply(int status, Duration delay) {

		public static Reply status(int status) {
			return new Reply(status, Duration.ZERO);
		}

		public static Reply delayed(Duration delay, int status) {
			return new Reply(status, delay);
		}

	}

	/** Lo que llegó en una petición; los nombres de las cabeceras, en minúsculas. */
	public record Captured(String method, String target, Map<String, String> headers, byte[] body) {

		public String header(String name) {
			return headers.get(name.toLowerCase(Locale.ROOT));
		}

		public String text() {
			return new String(body, StandardCharsets.UTF_8);
		}

	}

	private static final class Script {

		private final List<Reply> replies = new ArrayList<>();

		private final List<Captured> requests = new ArrayList<>();

		synchronized Reply next(Captured request) {
			requests.add(request);
			if (replies.isEmpty()) {
				return Reply.status(200);
			}
			return replies.size() > 1 ? replies.remove(0) : replies.get(0);
		}

		synchronized void set(Reply... script) {
			replies.clear();
			replies.addAll(List.of(script));
		}

		synchronized List<Captured> requests() {
			return List.copyOf(requests);
		}

	}

	private final HttpServer server;

	private final Map<String, Script> scripts = new ConcurrentHashMap<>();

	private WebhookReceiverStub() {
		try {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		server.createContext("/hook/", this::handle);
		server.setExecutor(Executors.newCachedThreadPool(runnable -> {
			Thread thread = new Thread(runnable, "webhook-stub");
			thread.setDaemon(true);
			return thread;
		}));
		server.start();
	}

	public static WebhookReceiverStub instance() {
		return INSTANCE;
	}

	/** Dirección del receptor con ese nombre, con una ficha en la consulta como suele llevar. */
	public String url(String name) {
		return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook/" + name + "?ficha=ficha-" + name;
	}

	/** Cada petición consume la siguiente respuesta; la última se repite. */
	public void on(String name, Reply... replies) {
		scripts.computeIfAbsent(name, key -> new Script()).set(replies);
	}

	public List<Captured> received(String name) {
		Script script = scripts.get(name);
		return script == null ? List.of() : script.requests();
	}

	private void handle(HttpExchange exchange) throws IOException {
		String name = exchange.getRequestURI().getPath().substring("/hook/".length());
		Map<String, String> headers = new HashMap<>();
		exchange.getRequestHeaders().forEach((key, values) -> headers.put(key.toLowerCase(Locale.ROOT), values.get(0)));
		Captured request = new Captured(exchange.getRequestMethod(), exchange.getRequestURI().toString(), headers,
				exchange.getRequestBody().readAllBytes());
		Reply reply = scripts.computeIfAbsent(name, key -> new Script()).next(request);
		try {
			Thread.sleep(reply.delay().toMillis());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		exchange.sendResponseHeaders(reply.status(), -1);
		exchange.close();
	}

}
