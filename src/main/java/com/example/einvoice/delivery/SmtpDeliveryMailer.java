package com.example.einvoice.delivery;

import java.io.UnsupportedEncodingException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

/**
 * Envío por SMTP con el servidor configurado en spring.mail.*. Solo existe con el correo habilitado.
 * Un rechazo del servidor con código 5xx es definitivo; todo lo demás (4xx, conexión caída, tiempo
 * de espera) es temporal y se puede reintentar.
 */
@Component
@ConditionalOnBooleanProperty("einvoice.delivery.email.enabled")
public class SmtpDeliveryMailer implements DeliveryMailer {

	private static final int MAX_ERROR = 300;

	private final JavaMailSender sender;

	public SmtpDeliveryMailer(ObjectProvider<JavaMailSender> sender) {
		this.sender = sender.getIfAvailable();
		if (this.sender == null) {
			throw new IllegalStateException("El envío de correo está habilitado (einvoice.delivery.email.enabled)"
					+ " pero no hay servidor SMTP configurado: falta spring.mail.host");
		}
	}

	@Override
	public Result send(DeliveryEmail email) {
		try {
			MimeMessage message = sender.createMimeMessage();
			MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
			helper.setFrom(new InternetAddress(email.from(), email.fromName(), "UTF-8"));
			helper.setTo(new InternetAddress(email.to(), true));
			helper.setSubject(email.subject());
			helper.setText(email.text(), false);
			for (DeliveryEmail.Attachment attachment : email.attachments()) {
				helper.addAttachment(attachment.filename(), new ByteArrayResource(attachment.content()),
						attachment.contentType());
			}
			sender.send(message);
			return Result.sent(message.getMessageID());
		}
		catch (AddressException ex) {
			return Result.failed(true, "Dirección de correo no válida");
		}
		catch (MailException | MessagingException | UnsupportedEncodingException ex) {
			return classify(ex);
		}
	}

	/** Busca en toda la cadena de causas el código de respuesta del servidor SMTP. */
	private static Result classify(Exception failure) {
		Deque<Throwable> pending = new ArrayDeque<>();
		Set<Throwable> seen = new HashSet<>();
		pending.add(failure);
		Integer code = null;
		String serverText = null;
		boolean badAddress = false;
		while (!pending.isEmpty()) {
			Throwable current = pending.poll();
			if (!seen.add(current)) {
				continue;
			}
			Integer found = returnCode(current);
			if (current instanceof AddressException) {
				badAddress = true;
			}
			// Con varios códigos manda el temporal: basta uno para que valga la pena reintentar.
			if (found != null && (code == null || found < code)) {
				code = found;
				serverText = current.getMessage();
			}
			// La cola no admite nulos: solo se añade lo que existe.
			if (current.getCause() != null) {
				pending.add(current.getCause());
			}
			if (current instanceof MessagingException messaging && messaging.getNextException() != null) {
				pending.add(messaging.getNextException());
			}
			if (current instanceof MailSendException mail) {
				for (Exception nested : mail.getMessageExceptions()) {
					if (nested != null) {
						pending.add(nested);
					}
				}
			}
		}
		if (code != null) {
			return Result.failed(code >= 500, bounded("SMTP " + code + ": " + firstLine(serverText)));
		}
		if (badAddress) {
			return Result.failed(true, "Dirección de correo no válida");
		}
		// Solo el tipo del fallo: el mensaje de estas excepciones puede llevar el servidor y el puerto.
		Throwable root = failure;
		while (root.getCause() != null && root.getCause() != root) {
			root = root.getCause();
		}
		return Result.failed(false, "Sin respuesta del servidor de correo: " + root.getClass().getSimpleName());
	}

	/**
	 * Código de respuesta SMTP que llevan las excepciones del proveedor de correo. Se leen por nombre
	 * y reflexión para no depender en compilación de la implementación concreta de Jakarta Mail.
	 */
	private static Integer returnCode(Throwable failure) {
		String type = failure.getClass().getSimpleName();
		if (!type.equals("SMTPAddressFailedException") && !type.equals("SMTPSendFailedException")
				&& !type.equals("SMTPSenderFailedException")) {
			return null;
		}
		try {
			return failure.getClass().getMethod("getReturnCode").invoke(failure) instanceof Integer code ? code : null;
		}
		catch (ReflectiveOperationException ex) {
			return null;
		}
	}

	private static String firstLine(String text) {
		if (text == null) {
			return "";
		}
		int end = text.indexOf('\n');
		return (end < 0 ? text : text.substring(0, end)).trim();
	}

	private static String bounded(String text) {
		return text.length() <= MAX_ERROR ? text : text.substring(0, MAX_ERROR);
	}

}
