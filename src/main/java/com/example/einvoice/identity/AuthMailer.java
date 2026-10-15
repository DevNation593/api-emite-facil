package com.example.einvoice.identity;

import java.net.URI;
import java.time.Duration;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Correos de la cuenta: confirmar el correo, cambiar la contraseña y avisar de que ya hay una cuenta.
 * Salen después de guardar (si la transacción se deshace, no sale nada) y fuera de la petición, con el
 * mismo servidor SMTP y remitente que la entrega de comprobantes. Sin SMTP la aplicación funciona, pero
 * estos correos no llegan: se avisa al arrancar y en cada intento, sin escribir nunca el enlace.
 */
@Component
public class AuthMailer {

	private static final Logger log = LoggerFactory.getLogger(AuthMailer.class);

	/** Nombre visible del remitente; el del producto aún no está decidido. */
	private static final String PRODUCT = "Facturación electrónica";

	/** Lo que se pide enviar; el listener lo despacha tras confirmar la transacción. */
	public record Message(String to, String subject, String text, String kind) {
	}

	private final JavaMailSender sender;

	private final String from;

	private final TaskExecutor executor;

	public AuthMailer(ObjectProvider<JavaMailSender> sender, @Value("${einvoice.delivery.email.from:}") String from,
			@Qualifier("applicationTaskExecutor") TaskExecutor executor) {
		this.sender = sender.getIfAvailable();
		this.from = from == null ? "" : from.strip();
		this.executor = executor;
		if (this.sender == null || this.from.isEmpty()) {
			log.warn("Sin servidor SMTP (spring.mail.host) o sin remitente (DELIVERY_EMAIL_FROM): los correos para"
					+ " confirmar la cuenta y cambiar la contraseña no se enviarán");
		}
	}

	static Message verification(String to, URI link, Duration validFor) {
		return new Message(to, "Confirma tu correo", """
				Hola:

				Para confirmar que este correo es tuyo, abre este enlace:

				%s

				El enlace vale %s. Si no creaste una cuenta de facturación electrónica, ignora este mensaje.
				""".formatted(link, describe(validFor)), "VERIFY_EMAIL");
	}

	static Message passwordReset(String to, URI link, Duration validFor) {
		return new Message(to, "Cambia tu contraseña", """
				Hola:

				Alguien pidió cambiar la contraseña de tu cuenta. Para elegir una nueva, abre este enlace:

				%s

				El enlace vale %s y sirve una sola vez. Si no lo pediste tú, ignora este mensaje: tu contraseña
				sigue siendo la misma.
				""".formatted(link, describe(validFor)), "RESET_PASSWORD");
	}

	/** Respuesta a un registro con un correo que ya tiene cuenta: la página no lo revela, el correo sí. */
	static Message alreadyRegistered(String to, URI resetLink, Duration validFor) {
		return new Message(to, "Ya tienes una cuenta", """
				Hola:

				Alguien intentó crear una cuenta con este correo, pero ya tienes una. Si fuiste tú y no
				recuerdas tu contraseña, elige una nueva con este enlace (vale %s):

				%s

				Si no fuiste tú, ignora este mensaje.
				""".formatted(describe(validFor), resetLink), "ALREADY_REGISTERED");
	}

	@TransactionalEventListener
	void afterCommit(Message message) {
		if (sender == null || from.isEmpty()) {
			log.warn("Correo de cuenta ({}) sin enviar: no hay servidor SMTP o remitente configurado", message.kind());
			return;
		}
		executor.execute(() -> send(message));
	}

	private void send(Message message) {
		try {
			MimeMessage mime = sender.createMimeMessage();
			MimeMessageHelper helper = new MimeMessageHelper(mime, false, "UTF-8");
			helper.setFrom(new InternetAddress(from, PRODUCT, "UTF-8"));
			helper.setTo(new InternetAddress(message.to(), true));
			helper.setSubject(message.subject());
			helper.setText(message.text(), false);
			sender.send(mime);
			log.info("Correo de cuenta enviado: {}", message.kind());
		}
		catch (Exception ex) {
			// Se puede volver a pedir (reenviar la confirmación, olvidé mi contraseña).
			log.warn("No se pudo enviar el correo de cuenta ({}): {}", message.kind(), ex.toString());
		}
	}

	private static String describe(Duration duration) {
		long hours = duration.toHours();
		if (hours >= 1 && duration.toMinutesPart() == 0) {
			return hours == 1 ? "1 hora" : hours + " horas";
		}
		long minutes = duration.toMinutes();
		return minutes == 1 ? "1 minuto" : minutes + " minutos";
	}

}
