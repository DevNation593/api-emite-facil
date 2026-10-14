package com.example.einvoice.support;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;

/**
 * Servidor SMTP simulado, único por JVM: acepta, rechaza o corta según un guion por destinatario y
 * guarda los mensajes que acepta. Sin guion, acepta todo.
 */
public final class SmtpStub {

	private static final SmtpStub INSTANCE = new SmtpStub();

	/** Una respuesta del guion. */
	public record Reply(Kind kind, int code, String text, Duration delay) {

		enum Kind {

			ACCEPT, REJECT_RECIPIENT, FAIL_DATA, DROP

		}

		public static Reply accept() {
			return new Reply(Kind.ACCEPT, 250, "OK", Duration.ZERO);
		}

		/** El servidor rechaza al destinatario (respuesta a RCPT TO). */
		public static Reply rejectRecipient(int code, String text) {
			return new Reply(Kind.REJECT_RECIPIENT, code, text, Duration.ZERO);
		}

		/** El servidor rechaza el mensaje después de recibirlo entero. */
		public static Reply failData(int code, String text) {
			return new Reply(Kind.FAIL_DATA, code, text, Duration.ZERO);
		}

		/** Corta la conexión sin responder al mensaje. */
		public static Reply dropConnection() {
			return new Reply(Kind.DROP, 0, "", Duration.ZERO);
		}

		/** Tarda en responder al mensaje. */
		public static Reply delayed(Duration delay, Reply reply) {
			return new Reply(reply.kind(), reply.code(), reply.text(), delay);
		}

	}

	private static final class Mailbox {

		private final List<Reply> script = new ArrayList<>();

		private final List<byte[]> messages = new ArrayList<>();

		synchronized Reply next() {
			if (script.isEmpty()) {
				return Reply.accept();
			}
			return script.size() > 1 ? script.remove(0) : script.get(0);
		}

		synchronized void set(Reply... replies) {
			script.clear();
			script.addAll(List.of(replies));
		}

		synchronized void store(byte[] raw) {
			messages.add(raw);
		}

		synchronized List<byte[]> raw() {
			return List.copyOf(messages);
		}

	}

	private final ServerSocket server;

	private final Map<String, Mailbox> mailboxes = new ConcurrentHashMap<>();

	private final AtomicInteger connections = new AtomicInteger();

	private SmtpStub() {
		try {
			server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		Thread acceptor = new Thread(this::acceptLoop, "smtp-stub");
		acceptor.setDaemon(true);
		acceptor.start();
	}

	public static SmtpStub instance() {
		return INSTANCE;
	}

	public String host() {
		return "127.0.0.1";
	}

	public int port() {
		return server.getLocalPort();
	}

	/** Cada mensaje a esa dirección consume la siguiente respuesta; la última se repite. */
	public void onRecipient(String email, Reply... replies) {
		mailbox(email).set(replies);
	}

	/** Mensajes aceptados para esa dirección, en orden de llegada. */
	public List<MimeMessage> messages(String email) {
		List<MimeMessage> parsed = new ArrayList<>();
		Session session = Session.getInstance(new Properties());
		for (byte[] raw : mailbox(email).raw()) {
			try {
				parsed.add(new MimeMessage(session, new ByteArrayInputStream(raw)));
			}
			catch (MessagingException ex) {
				throw new IllegalStateException(ex);
			}
		}
		return parsed;
	}

	/** Conexiones recibidas desde que arrancó la JVM. */
	public int connections() {
		return connections.get();
	}

	private Mailbox mailbox(String email) {
		return mailboxes.computeIfAbsent(email.toLowerCase(Locale.ROOT), key -> new Mailbox());
	}

	private void acceptLoop() {
		while (!server.isClosed()) {
			try {
				Socket socket = server.accept();
				connections.incrementAndGet();
				Thread handler = new Thread(() -> serve(socket), "smtp-stub-connection");
				handler.setDaemon(true);
				handler.start();
			}
			catch (IOException ex) {
				return;
			}
		}
	}

	private void serve(Socket socket) {
		try (socket) {
			BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
			OutputStream out = socket.getOutputStream();
			say(out, "220 smtp.stub ESMTP");
			List<String> recipients = new ArrayList<>();
			List<Reply> replies = new ArrayList<>();
			String line;
			while ((line = in.readLine()) != null) {
				String command = line.toUpperCase(Locale.ROOT);
				if (command.startsWith("EHLO") || command.startsWith("HELO")) {
					say(out, "250 smtp.stub");
				}
				else if (command.startsWith("MAIL FROM")) {
					recipients.clear();
					replies.clear();
					say(out, "250 OK");
				}
				else if (command.startsWith("RCPT TO")) {
					String address = line.substring(line.indexOf('<') + 1, line.indexOf('>'));
					Reply reply = mailbox(address).next();
					if (reply.kind() == Reply.Kind.REJECT_RECIPIENT) {
						say(out, reply.code() + " " + reply.text());
					}
					else {
						recipients.add(address);
						replies.add(reply);
						say(out, "250 OK");
					}
				}
				else if (command.equals("DATA")) {
					if (recipients.isEmpty()) {
						say(out, "503 No hay destinatarios");
						continue;
					}
					say(out, "354 Adelante");
					byte[] raw = readData(in);
					Reply reply = replies.get(0);
					if (!reply.delay().isZero()) {
						Thread.sleep(reply.delay().toMillis());
					}
					if (reply.kind() == Reply.Kind.DROP) {
						return;
					}
					if (reply.kind() == Reply.Kind.FAIL_DATA) {
						say(out, reply.code() + " " + reply.text());
					}
					else {
						for (String recipient : recipients) {
							mailbox(recipient).store(raw);
						}
						say(out, "250 OK en cola");
					}
					recipients.clear();
					replies.clear();
				}
				else if (command.startsWith("RSET") || command.startsWith("NOOP")) {
					say(out, "250 OK");
				}
				else if (command.startsWith("QUIT")) {
					say(out, "221 Adiós");
					return;
				}
				else {
					say(out, "500 Orden desconocida");
				}
			}
		}
		catch (IOException ex) {
			// El cliente se fue: no es un fallo del simulador.
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	/** Lee el mensaje hasta la línea con un solo punto, deshaciendo el relleno de puntos. */
	private static byte[] readData(BufferedReader in) throws IOException {
		StringBuilder message = new StringBuilder();
		String line;
		while ((line = in.readLine()) != null && !line.equals(".")) {
			message.append(line.startsWith("..") ? line.substring(1) : line).append("\r\n");
		}
		return message.toString().getBytes(StandardCharsets.ISO_8859_1);
	}

	private static void say(OutputStream out, String reply) throws IOException {
		out.write((reply + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
		out.flush();
	}

}
