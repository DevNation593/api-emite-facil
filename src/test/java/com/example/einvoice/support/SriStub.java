package com.example.einvoice.support;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * SRI simulado: un servidor HTTP local, único por JVM, que responde a la recepción y a la
 * autorización según un guion por clave de acceso. Sin guion, recibe y autoriza todo.
 */
public final class SriStub {

	private static final SriStub INSTANCE = new SriStub();

	private static final Pattern ACCESS_KEY_IN_INVOICE = Pattern.compile("<claveAcceso>([0-9]{49})</claveAcceso>");

	private static final Pattern BASE64_XML = Pattern.compile("<xml>([^<]*)</xml>");

	private static final Pattern CONSULTED_KEY = Pattern
		.compile("<claveAccesoComprobante>\\s*([^<\\s]*)\\s*</claveAccesoComprobante>");

	/** Una respuesta del guion. */
	public record Reply(int status, byte[] body, Duration delay, boolean drop) {

		public static Reply xml(String body) {
			return new Reply(200, body.getBytes(StandardCharsets.UTF_8), Duration.ZERO, false);
		}

		public static Reply status(int status, String body) {
			return new Reply(status, body.getBytes(StandardCharsets.UTF_8), Duration.ZERO, false);
		}

		public static Reply bytes(int size) {
			byte[] body = new byte[size];
			java.util.Arrays.fill(body, (byte) 'x');
			return new Reply(200, body, Duration.ZERO, false);
		}

		public static Reply delayed(Duration delay, Reply reply) {
			return new Reply(reply.status(), reply.body(), delay, reply.drop());
		}

		/** Corta la conexión sin responder. */
		public static Reply dropConnection() {
			return new Reply(0, new byte[0], Duration.ZERO, true);
		}

	}

	/** Lo que llegó en una petición. */
	public record Captured(String contentType, String soapAction, byte[] body) {
	}

	private static final class Script {

		private final List<Reply> replies = new ArrayList<>();

		private final List<Captured> requests = new ArrayList<>();

		private byte[] lastXml;

		synchronized Reply next(Captured request, byte[] xml) {
			requests.add(request);
			lastXml = xml;
			if (replies.isEmpty()) {
				return null;
			}
			return replies.size() > 1 ? replies.remove(0) : replies.get(0);
		}

		synchronized void set(Reply... script) {
			replies.clear();
			replies.addAll(List.of(script));
		}

		synchronized int calls() {
			return requests.size();
		}

		synchronized Captured last() {
			return requests.isEmpty() ? null : requests.get(requests.size() - 1);
		}

		synchronized byte[] lastXml() {
			return lastXml;
		}

	}

	private final HttpServer server;

	private final Map<String, Script> reception = new ConcurrentHashMap<>();

	private final Map<String, Script> authorization = new ConcurrentHashMap<>();

	private SriStub() {
		try {
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		server.createContext("/recepcion", this::handleReception);
		server.createContext("/autorizacion", this::handleAuthorization);
		server.setExecutor(Executors.newCachedThreadPool(runnable -> {
			Thread thread = new Thread(runnable, "sri-stub");
			thread.setDaemon(true);
			return thread;
		}));
		server.start();
	}

	public static SriStub instance() {
		return INSTANCE;
	}

	public String receptionUrl() {
		return "http://127.0.0.1:" + server.getAddress().getPort() + "/recepcion";
	}

	public String authorizationUrl() {
		return "http://127.0.0.1:" + server.getAddress().getPort() + "/autorizacion";
	}

	/** Cada petición consume la siguiente respuesta; la última se repite. */
	public void onReception(String accessKey, Reply... replies) {
		script(reception, accessKey).set(replies);
	}

	public void onAuthorization(String accessKey, Reply... replies) {
		script(authorization, accessKey).set(replies);
	}

	public int receptionCalls(String accessKey) {
		return script(reception, accessKey).calls();
	}

	public int authorizationCalls(String accessKey) {
		return script(authorization, accessKey).calls();
	}

	/** XML del comprobante que llegó en la última recepción de esa clave, ya decodificado. */
	public byte[] lastReceptionXml(String accessKey) {
		return script(reception, accessKey).lastXml();
	}

	public Captured lastReception(String accessKey) {
		return script(reception, accessKey).last();
	}

	public Captured lastAuthorization(String accessKey) {
		return script(authorization, accessKey).last();
	}

	private static Script script(Map<String, Script> scripts, String accessKey) {
		return scripts.computeIfAbsent(accessKey, key -> new Script());
	}

	private void handleReception(HttpExchange exchange) throws IOException {
		Captured request = capture(exchange);
		String soap = new String(request.body(), StandardCharsets.UTF_8);
		Matcher encoded = BASE64_XML.matcher(soap);
		byte[] xml = encoded.find() ? Base64.getMimeDecoder().decode(encoded.group(1)) : new byte[0];
		Matcher key = ACCESS_KEY_IN_INVOICE.matcher(new String(xml, StandardCharsets.UTF_8));
		String accessKey = key.find() ? key.group(1) : "sin-clave";
		Reply reply = script(reception, accessKey).next(request, xml);
		respond(exchange, reply != null ? reply : Reply.xml(SriSoapSamples.received()));
	}

	private void handleAuthorization(HttpExchange exchange) throws IOException {
		Captured request = capture(exchange);
		Matcher key = CONSULTED_KEY.matcher(new String(request.body(), StandardCharsets.UTF_8));
		String accessKey = key.find() ? key.group(1) : "sin-clave";
		Reply reply = script(authorization, accessKey).next(request, null);
		respond(exchange, reply != null ? reply : Reply.xml(SriSoapSamples.authorized(accessKey)));
	}

	private static Captured capture(HttpExchange exchange) throws IOException {
		byte[] body = exchange.getRequestBody().readAllBytes();
		return new Captured(exchange.getRequestHeaders().getFirst("Content-Type"),
				exchange.getRequestHeaders().getFirst("SOAPAction"), body);
	}

	private static void respond(HttpExchange exchange, Reply reply) throws IOException {
		try {
			if (!reply.delay().isZero()) {
				Thread.sleep(reply.delay().toMillis());
			}
			if (reply.drop()) {
				return;
			}
			exchange.getResponseHeaders().add("Content-Type", "text/xml; charset=UTF-8");
			exchange.sendResponseHeaders(reply.status(), reply.body().length == 0 ? -1 : reply.body().length);
			if (reply.body().length > 0) {
				try (OutputStream out = exchange.getResponseBody()) {
					out.write(reply.body());
				}
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		catch (IOException ex) {
			// El cliente se fue antes (por ejemplo, por su timeout): no es un fallo del simulador.
		}
		finally {
			exchange.close();
		}
	}

}
