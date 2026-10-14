package com.example.einvoice.webhook;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Entrega un aviso por HTTP. No usa el cliente HTTP del JDK a propósito: se conecta a la dirección IP
 * que ya se comprobó (no a lo que el nombre resuelva en ese instante), no sigue redirecciones y de la
 * respuesta lee solo la línea de estado, con un plazo que abarca el intento entero. Lo que responda
 * un receptor no interesa y no se guarda.
 */
@Component
public class WebhookSender {

	/** Resultado de un intento: el código HTTP, o cero y el motivo si no hubo respuesta. */
	public record Result(int status, String error) {

		public boolean delivered() {
			return status >= 200 && status < 300;
		}

	}

	private static final int MAX_STATUS_LINE = 512;

	private static final Pattern STATUS_LINE = Pattern.compile("HTTP/1\\.[01] ([1-5][0-9]{2})(?: .*)?");

	private static final Pattern IP_LITERAL = Pattern.compile("[0-9.]+|\\[.*]");

	/** Cierra los intentos que agotan su plazo. */
	private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(runnable -> {
		Thread thread = new Thread(runnable, "webhook-deadline");
		thread.setDaemon(true);
		return thread;
	});

	private final Duration connectTimeout;

	private final Duration totalTimeout;

	private final SSLSocketFactory tls;

	@Autowired
	public WebhookSender(WebhookProperties properties) {
		this(properties.connectTimeout(), properties.totalTimeout(), (SSLSocketFactory) SSLSocketFactory.getDefault());
	}

	public WebhookSender(Duration connectTimeout, Duration totalTimeout, SSLSocketFactory tls) {
		this.connectTimeout = connectTimeout;
		this.totalTimeout = totalTimeout;
		this.tls = tls;
	}

	/**
	 * Envía el aviso a la dirección dada, que debe ser la que devolvió {@link WebhookUrlPolicy#vet}
	 * para esa URL. Nunca lanza por un fallo de red: lo devuelve.
	 */
	public Result send(URI url, InetAddress address, Map<String, String> headers, byte[] body) {
		boolean https = "https".equalsIgnoreCase(url.getScheme());
		int port = url.getPort() != -1 ? url.getPort() : https ? 443 : 80;
		AtomicBoolean expired = new AtomicBoolean();
		Socket plain = new Socket();
		// El plazo es de todo el intento: una respuesta que llega a cuentagotas no lo alarga.
		ScheduledFuture<?> deadline = WATCHDOG.schedule(() -> {
			expired.set(true);
			closeQuietly(plain);
		}, totalTimeout.toMillis(), TimeUnit.MILLISECONDS);
		try {
			plain.connect(new InetSocketAddress(address, port),
					(int) Math.min(connectTimeout.toMillis(), totalTimeout.toMillis()));
			plain.setSoTimeout((int) totalTimeout.toMillis());
			Socket socket = https ? secure(plain, url.getHost(), port) : plain;
			OutputStream out = socket.getOutputStream();
			out.write(head(url, port, https, headers, body.length).getBytes(StandardCharsets.US_ASCII));
			out.write(body);
			out.flush();
			Matcher status = STATUS_LINE.matcher(statusLine(socket.getInputStream()));
			if (!status.matches()) {
				return new Result(0, "El receptor respondió algo que no es HTTP");
			}
			return new Result(Integer.parseInt(status.group(1)), null);
		}
		catch (SocketTimeoutException ex) {
			return new Result(0, "El receptor no respondió a tiempo");
		}
		catch (SSLException ex) {
			return new Result(0, expired.get() ? "El receptor no respondió a tiempo"
					: "No se pudo establecer una conexión segura con el receptor (certificado o TLS)");
		}
		catch (IOException ex) {
			return new Result(0, expired.get() ? "El receptor no respondió a tiempo" : "No se pudo conectar con el receptor");
		}
		finally {
			deadline.cancel(false);
			closeQuietly(plain);
		}
	}

	/** TLS sobre la conexión ya abierta, comprobando que el certificado es del nombre de la URL. */
	private Socket secure(Socket plain, String host, int port) throws IOException {
		String name = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
		SSLSocket socket = (SSLSocket) tls.createSocket(plain, name, port, true);
		SSLParameters parameters = socket.getSSLParameters();
		parameters.setEndpointIdentificationAlgorithm("HTTPS");
		if (!IP_LITERAL.matcher(host).matches()) {
			parameters.setServerNames(List.of(new SNIHostName(name)));
		}
		socket.setSSLParameters(parameters);
		socket.startHandshake();
		return socket;
	}

	private static String head(URI url, int port, boolean https, Map<String, String> headers, int length) {
		String path = url.getRawPath() == null || url.getRawPath().isEmpty() ? "/" : url.getRawPath();
		String target = url.getRawQuery() == null ? path : path + "?" + url.getRawQuery();
		boolean defaultPort = port == (https ? 443 : 80);
		StringBuilder head = new StringBuilder(512);
		head.append("POST ").append(target).append(" HTTP/1.1\r\n");
		head.append("Host: ").append(url.getHost()).append(defaultPort ? "" : ":" + port).append("\r\n");
		head.append("User-Agent: einvoice-webhooks/1\r\n");
		head.append("Accept: */*\r\n");
		head.append("Connection: close\r\n");
		head.append("Content-Length: ").append(length).append("\r\n");
		headers.forEach((name, value) -> head.append(name).append(": ").append(value).append("\r\n"));
		return head.append("\r\n").toString();
	}

	/** La primera línea de la respuesta, sin leer más allá de un tope. */
	private static String statusLine(InputStream in) throws IOException {
		StringBuilder line = new StringBuilder(64);
		int read;
		while (line.length() < MAX_STATUS_LINE && (read = in.read()) != -1) {
			if (read == '\n') {
				break;
			}
			if (read != '\r') {
				line.append((char) read);
			}
		}
		return line.toString();
	}

	private static void closeQuietly(Socket socket) {
		try {
			socket.close();
		}
		catch (IOException ex) {
			// Ya estaba cerrado o roto: no hay nada más que hacer con él.
		}
	}

}
