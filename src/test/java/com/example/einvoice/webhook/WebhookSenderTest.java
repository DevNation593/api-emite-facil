package com.example.einvoice.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.example.einvoice.webhook.WebhookSender.Result;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

class WebhookSenderTest {

	static final InetAddress LOCAL = InetAddress.getLoopbackAddress();

	static final Map<String, String> HEADERS = Map.of("content-type", "application/json", "webhook-id", "evento-1",
			"webhook-timestamp", "1790000000", "webhook-signature", "v1,firma");

	static final byte[] BODY = "{\"eventType\":\"document.authorized\",\"nota\":\"año\"}".getBytes(StandardCharsets.UTF_8);

	final List<AutoCloseable> open = new ArrayList<>();

	final WebhookSender sender = new WebhookSender(Duration.ofSeconds(2), Duration.ofSeconds(3),
			(SSLSocketFactory) SSLSocketFactory.getDefault());

	@AfterEach
	void closeServers() throws Exception {
		for (AutoCloseable server : open) {
			server.close();
		}
	}

	/** Lo que el servidor vio de una petición. */
	record Seen(String method, String target, String host, Map<String, List<String>> headers, byte[] body) {
	}

	HttpServer server(List<Seen> seen, Consumer<HttpExchange> reply) throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress(LOCAL, 0), 0);
		server.createContext("/", exchange -> {
			seen.add(new Seen(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
					exchange.getRequestHeaders().getFirst("Host"), Map.copyOf(exchange.getRequestHeaders()),
					exchange.getRequestBody().readAllBytes()));
			reply.accept(exchange);
		});
		server.start();
		open.add(() -> server.stop(0));
		return server;
	}

	static Consumer<HttpExchange> status(int status) {
		return exchange -> {
			try {
				exchange.sendResponseHeaders(status, -1);
				exchange.close();
			}
			catch (IOException ex) {
				throw new IllegalStateException(ex);
			}
		};
	}

	/** Un servidor que acepta la conexión y hace con ella lo que diga la prueba. */
	int rawServer(Consumer<Socket> behaviour) throws IOException {
		ServerSocket server = new ServerSocket(0, 10, LOCAL);
		open.add(server);
		Thread thread = new Thread(() -> {
			while (!server.isClosed()) {
				try {
					Socket socket = server.accept();
					Thread handler = new Thread(() -> behaviour.accept(socket), "raw-handler");
					handler.setDaemon(true);
					handler.start();
				}
				catch (IOException ex) {
					return;
				}
			}
		}, "raw-server");
		thread.setDaemon(true);
		thread.start();
		return server.getLocalPort();
	}

	@Test
	void postsTheBodyAndHeadersAsGiven() throws Exception {
		List<Seen> seen = new CopyOnWriteArrayList<>();
		int port = server(seen, status(204)).getAddress().getPort();

		Result result = sender.send(URI.create("http://127.0.0.1:" + port + "/avisos/erp?ficha=abc%20d"), LOCAL, HEADERS, BODY);

		assertThat(result.delivered()).isTrue();
		assertThat(result.status()).isEqualTo(204);
		assertThat(seen).hasSize(1);
		Seen request = seen.get(0);
		assertThat(request.method()).isEqualTo("POST");
		assertThat(request.target()).isEqualTo("/avisos/erp?ficha=abc%20d");
		assertThat(request.host()).isEqualTo("127.0.0.1:" + port);
		assertThat(request.body()).isEqualTo(BODY);
		assertThat(request.headers().get("Webhook-id")).containsExactly("evento-1");
		assertThat(request.headers().get("Webhook-timestamp")).containsExactly("1790000000");
		assertThat(request.headers().get("Webhook-signature")).containsExactly("v1,firma");
		assertThat(request.headers().get("Content-type")).containsExactly("application/json");
		assertThat(request.headers().get("Content-length")).containsExactly(String.valueOf(BODY.length));
	}

	@Test
	void anAddressWithoutPathAsksForTheRoot() throws Exception {
		List<Seen> seen = new CopyOnWriteArrayList<>();
		int port = server(seen, status(200)).getAddress().getPort();

		assertThat(sender.send(URI.create("http://127.0.0.1:" + port), LOCAL, HEADERS, BODY).delivered()).isTrue();
		assertThat(seen.get(0).target()).isEqualTo("/");
	}

	@Test
	void onlyA2xxAnswerCountsAsDelivered() throws Exception {
		for (int status : new int[] { 200, 201, 202, 204 }) {
			int port = server(new CopyOnWriteArrayList<>(), status(status)).getAddress().getPort();
			assertThat(sender.send(URI.create("http://127.0.0.1:" + port + "/x"), LOCAL, HEADERS, BODY).delivered()).as("" + status)
				.isTrue();
		}
		for (int status : new int[] { 301, 400, 401, 404, 410, 429, 500, 503 }) {
			int port = server(new CopyOnWriteArrayList<>(), status(status)).getAddress().getPort();
			Result result = sender.send(URI.create("http://127.0.0.1:" + port + "/x"), LOCAL, HEADERS, BODY);
			assertThat(result.delivered()).as("" + status).isFalse();
			assertThat(result.status()).isEqualTo(status);
		}
	}

	@Test
	void aRedirectionIsNotFollowed() throws Exception {
		List<Seen> elsewhere = new CopyOnWriteArrayList<>();
		int inner = server(elsewhere, status(200)).getAddress().getPort();
		int port = server(new CopyOnWriteArrayList<>(), exchange -> {
			exchange.getResponseHeaders().add("Location", "http://127.0.0.1:" + inner + "/interno");
			status(302).accept(exchange);
		}).getAddress().getPort();

		Result result = sender.send(URI.create("http://127.0.0.1:" + port + "/x"), LOCAL, HEADERS, BODY);

		assertThat(result.delivered()).isFalse();
		assertThat(result.status()).isEqualTo(302);
		assertThat(elsewhere).isEmpty();
	}

	@Test
	void connectsToTheAddressItIsGivenWhateverTheNameSays() throws Exception {
		List<Seen> seen = new CopyOnWriteArrayList<>();
		int port = server(seen, status(200)).getAddress().getPort();

		// El nombre no existe: si se volviera a resolver, no llegaría.
		Result result = sender.send(URI.create("http://receptor.no-existe.invalid:" + port + "/x"), LOCAL, HEADERS, BODY);

		assertThat(result.delivered()).isTrue();
		assertThat(seen.get(0).host()).isEqualTo("receptor.no-existe.invalid:" + port);
	}

	@Test
	void aReceiverThatNeverAnswersIsGivenUpOnInTime() throws Exception {
		int port = rawServer(socket -> {
			// Acepta y calla.
		});
		WebhookSender impatient = new WebhookSender(Duration.ofSeconds(1), Duration.ofMillis(600),
				(SSLSocketFactory) SSLSocketFactory.getDefault());

		Result result = assertTimeoutPreemptively(Duration.ofSeconds(3),
				() -> impatient.send(URI.create("http://127.0.0.1:" + port + "/x"), LOCAL, HEADERS, BODY));

		assertThat(result.delivered()).isFalse();
		assertThat(result.status()).isZero();
		assertThat(result.error()).contains("a tiempo");
	}

	@Test
	void aReceiverThatDripsItsAnswerDoesNotStretchTheDeadline() throws Exception {
		int port = rawServer(socket -> {
			try (socket) {
				OutputStream out = socket.getOutputStream();
				for (byte letter : "HTTP/1.1 200 OK tarde\r\n\r\n".getBytes(StandardCharsets.US_ASCII)) {
					out.write(letter);
					out.flush();
					Thread.sleep(200);
				}
			}
			catch (IOException | InterruptedException ex) {
				// El emisor cerró: es lo esperado.
			}
		});
		WebhookSender impatient = new WebhookSender(Duration.ofSeconds(1), Duration.ofMillis(800),
				(SSLSocketFactory) SSLSocketFactory.getDefault());

		Result result = assertTimeoutPreemptively(Duration.ofSeconds(3),
				() -> impatient.send(URI.create("http://127.0.0.1:" + port + "/x"), LOCAL, HEADERS, BODY));

		assertThat(result.delivered()).isFalse();
		assertThat(result.error()).contains("a tiempo");
	}

	@Test
	void anEndlessAnswerIsNotRead() throws Exception {
		AtomicInteger written = new AtomicInteger();
		int endlessLine = rawServer(socket -> flood(socket, "", written));
		int endlessBody = rawServer(socket -> flood(socket, "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\n", written));

		Result noStatus = assertTimeoutPreemptively(Duration.ofSeconds(4),
				() -> sender.send(URI.create("http://127.0.0.1:" + endlessLine + "/x"), LOCAL, HEADERS, BODY));
		Result withStatus = assertTimeoutPreemptively(Duration.ofSeconds(4),
				() -> sender.send(URI.create("http://127.0.0.1:" + endlessBody + "/x"), LOCAL, HEADERS, BODY));

		// Sin línea de estado no hay respuesta; con ella basta, y el resto no interesa.
		assertThat(noStatus.delivered()).isFalse();
		assertThat(noStatus.status()).isZero();
		// Se dejó de leer por el tope, no porque se agotara el plazo.
		assertThat(noStatus.error()).contains("HTTP");
		assertThat(withStatus.delivered()).isTrue();
	}

	static void flood(Socket socket, String head, AtomicInteger written) {
		try (socket) {
			OutputStream out = socket.getOutputStream();
			out.write(head.getBytes(StandardCharsets.US_ASCII));
			byte[] chunk = new byte[8192];
			java.util.Arrays.fill(chunk, (byte) 'x');
			while (true) {
				out.write(chunk);
				written.addAndGet(chunk.length);
			}
		}
		catch (IOException ex) {
			// El emisor cerró: es lo esperado.
		}
	}

	@Test
	void aDroppedConnectionOrAClosedPortIsReportedNotThrown() throws Exception {
		int dropping = rawServer(socket -> {
			try {
				socket.close();
			}
			catch (IOException ex) {
				throw new IllegalStateException(ex);
			}
		});
		int closed;
		try (ServerSocket free = new ServerSocket(0, 1, LOCAL)) {
			closed = free.getLocalPort();
		}

		Result dropped = sender.send(URI.create("http://127.0.0.1:" + dropping + "/x"), LOCAL, HEADERS, BODY);
		Result refused = sender.send(URI.create("http://127.0.0.1:" + closed + "/x"), LOCAL, HEADERS, BODY);

		assertThat(dropped.delivered()).isFalse();
		assertThat(dropped.error()).isNotBlank();
		assertThat(refused.delivered()).isFalse();
		assertThat(refused.error()).isNotBlank().doesNotContain("127.0.0.1");
	}

	@Test
	void overTlsTheCertificateMustBeTrustedAndMatchTheName(@TempDir Path dir) throws Exception {
		Path keystore = dir.resolve("servidor.p12");
		ProcessBuilder keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
				"-genkeypair", "-alias", "servidor", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=localhost", "-ext",
				"SAN=dns:localhost", "-validity", "2", "-storetype", "PKCS12", "-keystore", keystore.toString(), "-storepass",
				"cambiar", "-keypass", "cambiar")
			.redirectErrorStream(true)
			.redirectOutput(dir.resolve("keytool.log").toFile());
		keytool.environment().remove("JAVA_TOOL_OPTIONS");
		assertThat(keytool.start().waitFor()).as(Files.exists(dir.resolve("keytool.log")) ? "keytool" : "").isZero();
		KeyStore keys = KeyStore.getInstance("PKCS12");
		try (var in = Files.newInputStream(keystore)) {
			keys.load(in, "cambiar".toCharArray());
		}
		KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		keyManagers.init(keys, "cambiar".toCharArray());
		SSLContext serverContext = SSLContext.getInstance("TLS");
		serverContext.init(keyManagers.getKeyManagers(), null, null);
		List<Seen> seen = new CopyOnWriteArrayList<>();
		HttpsServer server = HttpsServer.create(new InetSocketAddress(LOCAL, 0), 0);
		server.setHttpsConfigurator(new HttpsConfigurator(serverContext));
		server.createContext("/", exchange -> {
			seen.add(new Seen(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
					exchange.getRequestHeaders().getFirst("Host"), Map.of(), exchange.getRequestBody().readAllBytes()));
			status(200).accept(exchange);
		});
		server.start();
		open.add(() -> server.stop(0));
		int port = server.getAddress().getPort();
		KeyStore trusted = KeyStore.getInstance(KeyStore.getDefaultType());
		trusted.load(null, null);
		trusted.setCertificateEntry("servidor", keys.getCertificate("servidor"));
		TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		trustManagers.init(trusted);
		SSLContext clientContext = SSLContext.getInstance("TLS");
		clientContext.init(null, trustManagers.getTrustManagers(), null);
		WebhookSender trusting = new WebhookSender(Duration.ofSeconds(2), Duration.ofSeconds(5), clientContext.getSocketFactory());

		// Certificado que nadie avala: con la confianza por defecto no se entrega.
		Result untrusted = sender.send(URI.create("https://localhost:" + port + "/x"), LOCAL, HEADERS, BODY);
		// Avalado, pero para otro nombre.
		Result otherName = trusting.send(URI.create("https://otro-nombre.test:" + port + "/x"), LOCAL, HEADERS, BODY);
		assertThat(seen).isEmpty();
		Result good = trusting.send(URI.create("https://localhost:" + port + "/x"), LOCAL, HEADERS, BODY);

		assertThat(untrusted.delivered()).isFalse();
		assertThat(untrusted.error()).contains("segura");
		assertThat(otherName.delivered()).isFalse();
		assertThat(otherName.error()).contains("segura");
		assertThat(good.delivered()).as(String.valueOf(good.error())).isTrue();
		assertThat(seen).hasSize(1);
		assertThat(seen.get(0).body()).isEqualTo(BODY);
	}

}
