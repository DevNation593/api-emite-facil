package com.example.einvoice.webhook;

import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.example.einvoice.certificate.SecretCipher;
import com.example.einvoice.certificate.SecretCipherException;
import com.example.einvoice.orchestration.RetryPolicy;
import com.example.einvoice.webhook.WebhookRepository.Claim;
import com.example.einvoice.webhook.WebhookRepository.DocumentFacts;
import com.example.einvoice.webhook.WebhookRepository.Target;

import tools.jackson.databind.json.JsonMapper;

/**
 * Envía los avisos pendientes de un comprobante. Cada aviso se reserva antes de enviarse, de modo
 * que dos procesos no envían el mismo, y el envío se hace fuera de toda transacción. Si el proceso
 * muere después de que el receptor lo aceptó y antes de anotarlo, el aviso se repite al vencer el
 * plazo de envío, con el mismo identificador: entrega al menos una vez.
 *
 * <p>Ni la dirección ni el secreto de una suscripción se escriben en el registro ni en el error que
 * se guarda con el aviso.
 */
@Service
public class WebhookDeliveryService {

	private static final Logger log = LoggerFactory.getLogger(WebhookDeliveryService.class);

	private final WebhookRepository repository;

	private final WebhookSender sender;

	private final WebhookUrlPolicy urls;

	private final SecretCipher cipher;

	private final WebhookProperties properties;

	private final JsonMapper json;

	public WebhookDeliveryService(WebhookRepository repository, WebhookSender sender, WebhookUrlPolicy urls,
			SecretCipher cipher, WebhookProperties properties, JsonMapper json) {
		this.repository = repository;
		this.sender = sender;
		this.urls = urls;
		this.cipher = cipher;
		this.properties = properties;
		this.json = json;
	}

	/** Intenta los avisos pendientes del comprobante y devuelve cuántos se entregaron. */
	public int process(UUID tenantId, UUID documentId) {
		if (!properties.enabled()) {
			return 0;
		}
		int sent = 0;
		for (UUID deliveryId : repository.pending(tenantId, documentId, properties.sendLease())) {
			Optional<Claim> claim = repository.claim(deliveryId, properties.sendLease(),
					properties.maxConcurrentPerTenant(), properties.busyDelay());
			if (claim.isPresent() && attempt(tenantId, documentId, deliveryId, claim.get())) {
				sent++;
			}
		}
		return sent;
	}

	/**
	 * Intenta los avisos de prueba pendientes de la suscripción y devuelve cuántos se entregaron. Cada
	 * uno se intenta una vez y su resultado no cuenta para la suscripción: es para probar ahora, no
	 * para insistir.
	 */
	public int processTests(UUID tenantId, UUID subscriptionId) {
		if (!properties.enabled()) {
			return 0;
		}
		int sent = 0;
		for (UUID deliveryId : repository.pendingTests(tenantId, subscriptionId, properties.sendLease())) {
			Optional<Claim> claim = repository.claim(deliveryId, properties.sendLease(),
					properties.maxConcurrentPerTenant(), properties.busyDelay());
			if (claim.isPresent() && attemptTest(tenantId, deliveryId, claim.get())) {
				sent++;
			}
		}
		return sent;
	}

	private boolean attemptTest(UUID tenantId, UUID deliveryId, Claim claim) {
		int attempt = claim.attemptCount();
		Optional<Target> found = repository.target(tenantId, claim.subscriptionId());
		if (found.isEmpty()) {
			return false;
		}
		Target target = found.get();
		if (!"ACTIVE".equals(target.status())) {
			repository.markFailed(deliveryId, attempt, null, "La suscripción está desactivada");
			return false;
		}
		URI url;
		InetAddress address;
		try {
			url = urls.parse(target.url());
			address = urls.vet(url);
		}
		catch (WebhookUrlException ex) {
			repository.markFailed(deliveryId, attempt, null, ex.getMessage());
			return false;
		}
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("eventId", claim.eventId().toString());
		event.put("eventType", claim.eventType());
		event.put("occurredAt", timestamp(claim.occurredAt().toInstant()));
		event.put("tenantId", tenantId.toString());
		event.put("subscriptionId", claim.subscriptionId().toString());
		byte[] body = json.writeValueAsBytes(event);
		WebhookSender.Result result;
		try {
			result = sender.send(url, address, headers(tenantId, claim, target, body), body);
		}
		catch (SecretCipherException | IllegalArgumentException ex) {
			log.error("No se pudo firmar el aviso de prueba {} de la suscripción {}: {}", deliveryId,
					claim.subscriptionId(), ex.getClass().getSimpleName());
			repository.markFailed(deliveryId, attempt, null, "No se pudo firmar el aviso");
			return false;
		}
		catch (RuntimeException ex) {
			// Como en los demás avisos: solo el tipo, que el mensaje podría llevar la dirección.
			log.error("Fallo inesperado al enviar el aviso de prueba {} de la suscripción {}: {}", deliveryId,
					claim.subscriptionId(), ex.getClass().getName());
			repository.markFailed(deliveryId, attempt, null, "Fallo inesperado: " + ex.getClass().getSimpleName());
			return false;
		}
		if (result.delivered()) {
			repository.markSent(deliveryId, result.status());
			return true;
		}
		Integer status = result.status() == 0 ? null : result.status();
		repository.markFailed(deliveryId, attempt, status,
				result.error() != null ? result.error() : "El receptor respondió " + result.status());
		return false;
	}

	private boolean attempt(UUID tenantId, UUID documentId, UUID deliveryId, Claim claim) {
		int attempt = claim.attemptCount();
		if (attempt > properties.retryMaxAttempts()) {
			repository.markFailed(deliveryId, attempt, null, "Se agotaron los intentos de envío");
			return false;
		}
		Optional<Target> found = repository.target(tenantId, claim.subscriptionId());
		Optional<DocumentFacts> document = repository.document(tenantId, documentId);
		if (found.isEmpty() || document.isEmpty()) {
			// La suscripción se borró mientras tanto, y el aviso con ella.
			return false;
		}
		Target target = found.get();
		if (!"ACTIVE".equals(target.status())) {
			repository.markFailed(deliveryId, attempt, null, "La suscripción está desactivada");
			return false;
		}
		URI url;
		InetAddress address;
		try {
			url = urls.parse(target.url());
			address = urls.vet(url);
		}
		catch (WebhookUrlException ex) {
			// La dirección se comprueba en cada envío: pudo dejar de ser pública después de registrarla.
			// No se conecta, pero se reintenta: una resolución de nombres averiada un rato no cuesta avisos.
			return failed(deliveryId, claim, null, ex.getMessage());
		}
		byte[] body = body(tenantId, documentId, claim, document.get());
		Map<String, String> headers;
		try {
			headers = headers(tenantId, claim, target, body);
		}
		catch (SecretCipherException | IllegalArgumentException ex) {
			log.error("No se pudo firmar el aviso {} de la suscripción {}: {}", deliveryId, claim.subscriptionId(),
					ex.getClass().getSimpleName());
			return ourFault(deliveryId, claim, "No se pudo firmar el aviso");
		}
		WebhookSender.Result result;
		try {
			result = sender.send(url, address, headers, body);
		}
		catch (RuntimeException ex) {
			// Un fallo imprevisto no puede dejar el aviso "enviando". Solo el tipo va al aviso y al
			// registro: el mensaje de la excepción podría llevar la dirección.
			log.error("Fallo inesperado al enviar el aviso {} de la suscripción {}: {}", deliveryId,
					claim.subscriptionId(), ex.getClass().getName());
			return ourFault(deliveryId, claim, "Fallo inesperado: " + ex.getClass().getSimpleName());
		}
		if (result.delivered()) {
			repository.markSent(deliveryId, result.status());
			repository.recordSuccess(claim.subscriptionId());
			return true;
		}
		Integer status = result.status() == 0 ? null : result.status();
		String error = result.error() != null ? result.error() : "El receptor respondió " + result.status();
		return failed(deliveryId, claim, status, error);
	}

	/** Un fallo del receptor (o de su dirección): cuenta en la suscripción. Devuelve siempre false. */
	private boolean failed(UUID deliveryId, Claim claim, Integer status, String error) {
		boolean disabled = repository.recordFailure(claim.subscriptionId(), properties.disableAfterFailures(),
				properties.disableAfterFailingFor());
		if (disabled) {
			log.warn("La suscripción {} se desactivó: {} intentos fallidos seguidos durante más de {}",
					claim.subscriptionId(), properties.disableAfterFailures(), properties.disableAfterFailingFor());
		}
		retryOrGiveUp(deliveryId, claim, status, error, disabled);
		return false;
	}

	/** Un fallo nuestro (no se pudo firmar, algo imprevisto): se reintenta sin cargárselo al receptor. */
	private boolean ourFault(UUID deliveryId, Claim claim, String error) {
		retryOrGiveUp(deliveryId, claim, null, error, false);
		return false;
	}

	private void retryOrGiveUp(UUID deliveryId, Claim claim, Integer status, String error, boolean giveUp) {
		if (giveUp || claim.attemptCount() >= properties.retryMaxAttempts()) {
			repository.markFailed(deliveryId, claim.attemptCount(), status, error);
		}
		else {
			Duration delay = RetryPolicy.delay(claim.attemptCount(), properties.retryBackoff(),
					properties.retryMaxBackoff(), ThreadLocalRandom.current().nextDouble());
			repository.markRetry(deliveryId, claim.attemptCount(), delay, status, error);
		}
	}

	/** El cuerpo del aviso: identificadores y estado. Es el mismo en cada reintento. */
	private byte[] body(UUID tenantId, UUID documentId, Claim claim, DocumentFacts facts) {
		boolean authorized = "AUTHORIZED".equals(claim.documentState());
		Map<String, Object> document = new LinkedHashMap<>();
		document.put("id", documentId.toString());
		document.put("documentType", facts.documentType());
		document.put("number", "%s-%s-%09d".formatted(facts.establishmentCode(), facts.emissionPointCode(), facts.sequence()));
		document.put("accessKey", facts.accessKey());
		// El estado que se anuncia, no el de ahora: el comprobante pudo avanzar antes de que el aviso saliera.
		document.put("state", claim.documentState());
		document.put("authorizationNumber", authorized ? facts.authorizationNumber() : null);
		document.put("authorizedAt", authorized && facts.authorizedAt() != null ? timestamp(facts.authorizedAt().toInstant()) : null);
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("eventId", claim.eventId().toString());
		event.put("eventType", claim.eventType());
		event.put("occurredAt", timestamp(claim.occurredAt().toInstant()));
		event.put("tenantId", tenantId.toString());
		event.put("document", document);
		return json.writeValueAsBytes(event);
	}

	private Map<String, String> headers(UUID tenantId, Claim claim, Target target, byte[] body) {
		String id = claim.eventId().toString();
		long now = Instant.now().getEpochSecond();
		byte[] context = WebhookSubscriptionService.secretContext(tenantId, claim.subscriptionId());
		String signature = WebhookSigner.sign(key(target.secret(), context), id, now, body);
		if (target.previousSecret() != null) {
			// Recién rotado: firma también el anterior, para que el receptor cambie sin perder avisos.
			signature += " " + WebhookSigner.sign(key(target.previousSecret(), context), id, now, body);
		}
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("Content-Type", "application/json");
		headers.put("webhook-id", id);
		headers.put("webhook-timestamp", Long.toString(now));
		headers.put("webhook-signature", signature);
		return headers;
	}

	private byte[] key(byte[] encrypted, byte[] context) {
		return WebhookSigner.key(new String(cipher.decrypt(encrypted, context), StandardCharsets.UTF_8));
	}

	private static String timestamp(Instant instant) {
		return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(instant.atOffset(ZoneOffset.UTC).withNano(0));
	}

}
