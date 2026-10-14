package com.example.einvoice.delivery;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.einvoice.billing.FiscalDocumentRepository;
import com.example.einvoice.delivery.DeliveryRepository.Claim;
import com.example.einvoice.orchestration.RetryPolicy;
import com.example.einvoice.storage.ArtifactKind;
import com.example.einvoice.storage.ArtifactStore;
import com.example.einvoice.webhook.WebhookRecorder;

/**
 * Archiva un comprobante autorizado y lo entrega a su receptor. La entrega tiene su propio estado:
 * pase lo que pase con el correo, el comprobante sigue autorizado.
 *
 * <p>El envío se hace fuera de toda transacción. La entrega se reserva antes de enviar, de modo que
 * dos procesos no envían la misma; si el proceso muere después de que el servidor aceptó el mensaje
 * y antes de anotarlo, el correo se repite al vencer el plazo de envío (entrega al menos una vez).
 */
@Service
@EnableConfigurationProperties(DeliveryProperties.class)
public class DeliveryService {

	private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);

	private static final int MAX_ERROR = 500;

	private final DocumentArchiver archiver;

	private final FiscalDocumentRepository documents;

	private final DeliveryRepository deliveries;

	private final ArtifactStore artifacts;

	private final DeliveryProperties properties;

	private final ObjectProvider<DeliveryMailer> mailer;

	private final WebhookRecorder webhooks;

	private final TransactionTemplate tx;

	public DeliveryService(DocumentArchiver archiver, FiscalDocumentRepository documents,
			DeliveryRepository deliveries, ArtifactStore artifacts, DeliveryProperties properties,
			ObjectProvider<DeliveryMailer> mailer, WebhookRecorder webhooks,
			PlatformTransactionManager transactionManager) {
		this.webhooks = webhooks;
		this.tx = new TransactionTemplate(transactionManager);
		this.archiver = archiver;
		this.documents = documents;
		this.deliveries = deliveries;
		this.artifacts = artifacts;
		this.properties = properties;
		this.mailer = mailer;
		if (properties.email().enabled() && mailer.getIfAvailable() == null) {
			// Sin esto las peticiones de entrega se aceptarían y ningún correo saldría nunca.
			throw new IllegalStateException("El envío de correo está habilitado (einvoice.delivery.email.enabled)"
					+ " pero no hay con qué enviar: revise la configuración del servidor SMTP");
		}
	}

	/** Archiva el comprobante e intenta sus entregas pendientes. Devuelve cuántos correos envió. */
	public int process(UUID tenantId, UUID documentId) {
		if (!archiver.archive(tenantId, documentId) || !properties.email().enabled()) {
			return 0;
		}
		Optional<RideData> found = documents.rideData(tenantId, documentId);
		if (found.isEmpty()) {
			return 0;
		}
		RideData data = found.get();
		if (data.customerEmail() != null && !data.customerEmail().isBlank()) {
			deliveries.ensureAutomatic(tenantId, documentId, data.customerEmail());
		}
		int sent = 0;
		for (UUID deliveryId : deliveries.pending(tenantId, documentId, properties.sendLease())) {
			Optional<Claim> claim = deliveries.claim(deliveryId, properties.sendLease());
			if (claim.isPresent() && send(tenantId, documentId, deliveryId, claim.get(), data)) {
				sent++;
			}
		}
		return sent;
	}

	private boolean send(UUID tenantId, UUID documentId, UUID deliveryId, Claim claim, RideData data) {
		if (claim.attemptCount() > properties.retryMaxAttempts()) {
			deliveries.markFailed(deliveryId, claim.attemptCount(), "Se agotaron los intentos de envío");
			return false;
		}
		if (!DeliveryProperties.validAddress(claim.recipient())) {
			// Ni siquiera se contacta al servidor: una dirección que son varias, o que no lo es.
			deliveries.markFailed(deliveryId, claim.attemptCount(), "Dirección de correo no válida");
			return false;
		}
		DeliveryMailer.Result result;
		try {
			byte[] ride = artifacts.find(tenantId, documentId, ArtifactKind.RIDE_PDF).orElseThrow().content();
			byte[] xml = artifacts.find(tenantId, documentId, ArtifactKind.XML_AUTHORIZED).orElseThrow().content();
			DeliveryEmail email = DeliveryEmailFactory.build(properties.email().from(), data, claim.recipient(), ride,
					xml);
			result = mailer.getObject().send(email);
		}
		catch (RuntimeException ex) {
			// Un fallo imprevisto no puede dejar la entrega "enviando": se anota y se reintenta como
			// cualquier otro. Solo el tipo va a la entrega; el detalle, al log.
			log.error("Fallo inesperado al enviar la entrega {} del documento {}", deliveryId, documentId, ex);
			result = DeliveryMailer.Result.failed(false, "Fallo inesperado: " + ex.getClass().getSimpleName());
		}
		if (result.sent()) {
			// El aviso a los sistemas de la empresa se anota junto con el envío, y una sola vez.
			String messageId = result.messageId();
			try {
				tx.executeWithoutResult(status -> {
					if (deliveries.markSent(deliveryId, messageId)) {
						webhooks.delivered(tenantId, documentId);
					}
				});
			}
			catch (RuntimeException ex) {
				// El correo ya salió: que no se pueda anotar el aviso no puede hacer que salga otra vez.
				log.error("No se pudo anotar el aviso de la entrega {} del documento {}: {}", deliveryId, documentId,
						ex.getClass().getName());
				deliveries.markSent(deliveryId, messageId);
			}
			return true;
		}
		String error = result.error() == null ? "Fallo sin detalle"
				: result.error().length() <= MAX_ERROR ? result.error() : result.error().substring(0, MAX_ERROR);
		if (result.permanent() || claim.attemptCount() >= properties.retryMaxAttempts()) {
			deliveries.markFailed(deliveryId, claim.attemptCount(), error);
			log.warn("La entrega {} del documento {} falló definitivamente tras {} intento(s)", deliveryId, documentId,
					claim.attemptCount());
		}
		else {
			Duration delay = RetryPolicy.delay(claim.attemptCount(), properties.retryBackoff(),
					properties.retryMaxBackoff(), ThreadLocalRandom.current().nextDouble());
			deliveries.markRetry(deliveryId, claim.attemptCount(), delay, error);
		}
		return false;
	}

}
