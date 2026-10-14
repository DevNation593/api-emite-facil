package com.example.einvoice.orchestration;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.einvoice.billing.FiscalDocumentRepository;
import com.example.einvoice.billing.FiscalDocumentRepository.ProcessingState;
import com.example.einvoice.billing.OutboxWriter;
import com.example.einvoice.sri.AuthorizationResult;
import com.example.einvoice.sri.ReceptionResult;
import com.example.einvoice.sri.SriAttemptLog;
import com.example.einvoice.sri.SriEnvironment;
import com.example.einvoice.sri.SriGateway;
import com.example.einvoice.sri.SriMessage;
import com.example.einvoice.sri.SriProperties;
import com.example.einvoice.storage.ArtifactKind;
import com.example.einvoice.storage.ArtifactStore;
import com.example.einvoice.web.ApiException;
import com.example.einvoice.webhook.WebhookRecorder;

/**
 * Único punto de avance de un comprobante: genera y firma, lo envía a la recepción del SRI y
 * consulta su autorización. Se puede llamar cuantas veces haga falta y desde varios procesos a la vez.
 *
 * <p>Cada paso es una transacción corta y las llamadas al SRI quedan fuera de toda transacción. El
 * paso a SENDING se confirma antes de transmitir: si después no hay una respuesta clara, el envío es
 * de resultado incierto y nunca se repite sin consultar antes la autorización por clave de acceso.
 */
@Service
public class DocumentPipeline {

	private static final Logger log = LoggerFactory.getLogger(DocumentPipeline.class);

	public static final String SENDING = "SENDING";

	public static final String RECEIVED = "RECEIVED";

	public static final String RETURNED = "RETURNED";

	public static final String AUTHORIZED = "AUTHORIZED";

	public static final String NOT_AUTHORIZED = "NOT_AUTHORIZED";

	/** Evento del outbox que pone en marcha el archivo y la entrega de un comprobante autorizado. */
	public static final String DOCUMENT_AUTHORIZED = "DOCUMENT_AUTHORIZED";

	/** Motivo del evento que deja constancia de que se agotaron los reintentos automáticos. */
	public static final String RETRIES_EXHAUSTED = "SRI_RETRIES_EXHAUSTED";

	/** Estados de los que el comprobante no sale por sí solo. */
	private static final Set<String> NEEDS_INTERVENTION = Set.of(DocumentProcessor.XSD_INVALID,
			DocumentProcessor.SIGNATURE_FAILED);

	/** Códigos con los que la recepción dice que ya tiene el comprobante (ficha técnica, sección 11). */
	private static final String ACCESS_KEY_REGISTERED = "43";

	private static final String ACCESS_KEY_IN_PROCESS = "70";

	private final FiscalDocumentRepository documents;

	private final DocumentProcessor processor;

	private final SriGateway sri;

	private final SriProperties properties;

	private final SriAttemptLog attempts;

	private final ArtifactStore artifacts;

	private final OutboxWriter outbox;

	private final WebhookRecorder webhooks;

	/** Plazo tras el cual un comprobante autorizado que nadie archivó vuelve a anunciarse. */
	private final Duration redeliveryLease;

	private final TransactionTemplate tx;

	public DocumentPipeline(FiscalDocumentRepository documents, DocumentProcessor processor, SriGateway sri,
			SriProperties properties, SriAttemptLog attempts, ArtifactStore artifacts, OutboxWriter outbox,
			@Value("${einvoice.worker.redelivery-lease:5m}") Duration redeliveryLease,
			PlatformTransactionManager transactionManager, WebhookRecorder webhooks) {
		this.outbox = outbox;
		this.webhooks = webhooks;
		this.redeliveryLease = redeliveryLease;
		this.documents = documents;
		this.processor = processor;
		this.sri = sri;
		this.properties = properties;
		this.attempts = attempts;
		this.artifacts = artifacts;
		this.tx = new TransactionTemplate(transactionManager);
	}

	/** Avanza el documento todo lo que se pueda ahora y devuelve el estado en que queda. */
	public String advance(UUID tenantId, UUID documentId) {
		String state = documents.processingState(tenantId, documentId).orElseThrow(ApiException::notFound).state();
		if (AUTHORIZED.equals(state)) {
			announceUntilArchived(tenantId, documentId);
			return state;
		}
		if (DocumentProcessor.pendingStates().contains(state)) {
			state = processor.generateAndSign(tenantId, documentId);
			if (NEEDS_INTERVENTION.contains(state)) {
				unschedule(tenantId, documentId);
			}
		}
		if (SENDING.equals(state)) {
			state = resolveUncertainSend(tenantId, documentId);
		}
		if (DocumentProcessor.SIGNED.equals(state)) {
			state = send(tenantId, documentId);
			// La ficha (7.4) pide esperar tras RECIBIDA: la consulta llegará con el intento programado.
			if (RECEIVED.equals(state) && !properties.authorizationDelay().isZero()) {
				return state;
			}
		}
		if (RECEIVED.equals(state)) {
			state = authorize(tenantId, documentId);
		}
		return state;
	}

	/**
	 * Un comprobante sin XML válido o sin firma no se arregla reintentando: espera una corrección y
	 * un reintento manual, así que no debe quedar programado (volvería a la cola una y otra vez).
	 */
	private void unschedule(UUID tenantId, UUID documentId) {
		tx.executeWithoutResult(status -> {
			// Solo si sigue en ese estado: otro proceso pudo haberlo avanzado y programado ya.
			if (NEEDS_INTERVENTION.contains(lock(tenantId, documentId).state())) {
				documents.schedule(tenantId, documentId, null, 0);
			}
		});
	}

	/** Lo que hace falta para llamar al SRI una vez confirmado el paso previo; attemptId nulo: no llamar. */
	private record Call(UUID attemptId, String state, SriEnvironment environment, String accessKey, byte[] xml,
			long version) {

		static Call none(String state) {
			return new Call(null, state, null, null, null, 0);
		}

	}

	private String send(UUID tenantId, UUID documentId) {
		Call call = tx.execute(status -> {
			ProcessingState locked = lock(tenantId, documentId);
			if (!DocumentProcessor.SIGNED.equals(locked.state())) {
				return Call.none(locked.state());
			}
			SriEnvironment environment = SriEnvironment.fromCode(locked.environment());
			boolean issuerAllowed = environment != SriEnvironment.PRODUCTION
					|| documents.issuerInProduction(tenantId, documentId);
			if (!properties.enabled(environment) || !issuerAllowed) {
				// La transmisión en este ambiente no está habilitada, o el emisor no ha sido activado en
				// producción: el comprobante espera firmado.
				documents.schedule(tenantId, documentId, null, 0);
				return Call.none(locked.state());
			}
			byte[] xml = artifacts.find(tenantId, documentId, ArtifactKind.XML_SIGNED)
				.orElseThrow(() -> new IllegalStateException("Documento firmado sin su XML: " + documentId))
				.content();
			UUID attemptId = attempts.start(tenantId, documentId, SriAttemptLog.RECEPTION, environment);
			documents.transition(tenantId, documentId, DocumentProcessor.SIGNED, SENDING, null);
			// Si este proceso muere antes de anotar el resultado, el documento se retoma al vencer el plazo.
			documents.schedule(tenantId, documentId, properties.sendLease(), locked.attemptCount());
			return new Call(attemptId, SENDING, environment, locked.accessKey(), xml, 0);
		});
		if (call.attemptId() == null) {
			return call.state();
		}
		ReceptionResult result = sri.receive(call.environment(), call.xml());
		return tx.execute(status -> applyReception(tenantId, documentId, call.attemptId(), result));
	}

	private String applyReception(UUID tenantId, UUID documentId, UUID attemptId, ReceptionResult result) {
		ProcessingState locked = lock(tenantId, documentId);
		boolean alreadyThere = result.outcome() == ReceptionResult.Outcome.RETURNED
				&& (result.hasMessage(ACCESS_KEY_REGISTERED) || result.hasMessage(ACCESS_KEY_IN_PROCESS));
		String outcome = switch (result.outcome()) {
			case RECEIVED -> "RECEIVED";
			case RETURNED -> alreadyThere ? "ALREADY_REGISTERED" : "RETURNED";
			case FAILED -> "UNCERTAIN";
		};
		// Si el intento ya no está abierto, otro proceso lo dio por abandonado y se hizo cargo.
		if (!attempts.complete(attemptId, outcome, result) || !SENDING.equals(locked.state())) {
			return locked.state();
		}
		switch (outcome) {
			case "RECEIVED", "ALREADY_REGISTERED" -> {
				documents.transition(tenantId, documentId, SENDING, RECEIVED,
						alreadyThere ? "ALREADY_REGISTERED: " + identifiers(result) : null);
				webhooks.stateReached(tenantId, documentId, RECEIVED);
				// La ficha (7.4) pide esperar un tiempo antes de consultar la autorización.
				documents.schedule(tenantId, documentId, properties.authorizationDelay(), 0);
				return RECEIVED;
			}
			case "RETURNED" -> {
				documents.transition(tenantId, documentId, SENDING, RETURNED, identifiers(result));
				webhooks.stateReached(tenantId, documentId, RETURNED);
				documents.schedule(tenantId, documentId, null, 0);
				return RETURNED;
			}
			default -> {
				inconclusive(tenantId, documentId, SENDING, locked.attemptCount());
				return SENDING;
			}
		}
	}

	/**
	 * Un documento en SENDING sin envío en curso es un envío de resultado incierto. Se pregunta al
	 * SRI por la clave de acceso: si la conoce, se da por recibido; solo si no la conoce se reenvía.
	 */
	private String resolveUncertainSend(UUID tenantId, UUID documentId) {
		Call call = tx.execute(status -> {
			ProcessingState locked = lock(tenantId, documentId);
			if (!SENDING.equals(locked.state()) || attempts.sendInFlight(documentId, properties.sendLease())) {
				return Call.none(locked.state());
			}
			attempts.abandonSends(documentId);
			SriEnvironment environment = SriEnvironment.fromCode(locked.environment());
			UUID attemptId = attempts.start(tenantId, documentId, SriAttemptLog.AUTHORIZATION, environment);
			return new Call(attemptId, SENDING, environment, locked.accessKey(), null, locked.version());
		});
		if (call.attemptId() == null) {
			return call.state();
		}
		AuthorizationResult result = sri.authorize(call.environment(), call.accessKey());
		return tx.execute(status -> {
			ProcessingState locked = lock(tenantId, documentId);
			String outcome = switch (result.outcome()) {
				// Solo cuenta una respuesta sobre esta clave de acceso. Sobre todo el "no encontrado":
				// es lo único que permite reenviar.
				case AUTHORIZED, NOT_AUTHORIZED, PROCESSING, NOT_FOUND ->
					call.accessKey().equals(result.consultedAccessKey()) ? result.outcome().name() : "MISMATCH";
				case FAILED -> "UNCERTAIN";
			};
			attempts.complete(call.attemptId(), outcome, result, true);
			// Otro proceso movió el documento mientras se consultaba: su decisión manda.
			if (!SENDING.equals(locked.state()) || locked.version() != call.version()) {
				return locked.state();
			}
			switch (outcome) {
				case "AUTHORIZED", "NOT_AUTHORIZED", "PROCESSING" -> {
					documents.transition(tenantId, documentId, SENDING, RECEIVED, "FOUND_AFTER_UNCERTAIN_SEND");
					webhooks.stateReached(tenantId, documentId, RECEIVED);
					documents.schedule(tenantId, documentId, Duration.ZERO, 0);
					return RECEIVED;
				}
				case "NOT_FOUND" -> {
					documents.transition(tenantId, documentId, SENDING, DocumentProcessor.SIGNED,
							"RESEND_AFTER_NOT_FOUND");
					return DocumentProcessor.SIGNED;
				}
				default -> {
					inconclusive(tenantId, documentId, SENDING, locked.attemptCount());
					return SENDING;
				}
			}
		});
	}

	/**
	 * Anuncia que el comprobante está autorizado, para que se archive y se entregue. Queda programado
	 * como red de seguridad: si el anuncio se pierde, se repite al vencer el plazo.
	 */
	private void announceAuthorized(UUID tenantId, UUID documentId) {
		outbox.write(tenantId, documentId, DOCUMENT_AUTHORIZED, Map.of("documentId", documentId.toString()));
		documents.schedule(tenantId, documentId, redeliveryLease, 0);
	}

	/** Un comprobante autorizado solo necesita atención mientras no esté archivado. */
	private void announceUntilArchived(UUID tenantId, UUID documentId) {
		tx.executeWithoutResult(status -> {
			if (!AUTHORIZED.equals(lock(tenantId, documentId).state())) {
				return;
			}
			if (artifacts.exists(tenantId, documentId, ArtifactKind.RIDE_PDF)) {
				documents.schedule(tenantId, documentId, null, 0);
			}
			else {
				announceAuthorized(tenantId, documentId);
			}
		});
	}

	/** Consulta la autorización de un comprobante recibido y, si hay resultado definitivo, lo cierra. */
	private String authorize(UUID tenantId, UUID documentId) {
		ProcessingState seen = documents.processingState(tenantId, documentId).orElseThrow(ApiException::notFound);
		if (!RECEIVED.equals(seen.state())) {
			return seen.state();
		}
		SriEnvironment environment = SriEnvironment.fromCode(seen.environment());
		UUID attemptId = attempts.start(tenantId, documentId, SriAttemptLog.AUTHORIZATION, environment);
		AuthorizationResult result = sri.authorize(environment, seen.accessKey());
		return tx.execute(status -> {
			ProcessingState locked = lock(tenantId, documentId);
			boolean sameKey = locked.accessKey().equals(result.consultedAccessKey());
			String outcome = switch (result.outcome()) {
				// Solo vale una autorización de esta clave de acceso y de este ambiente.
				case AUTHORIZED ->
					sameKey && environment.matchesLabel(result.environmentLabel()) ? "AUTHORIZED" : "MISMATCH";
				case NOT_AUTHORIZED -> sameKey ? "NOT_AUTHORIZED" : "MISMATCH";
				case PROCESSING -> "PROCESSING";
				case NOT_FOUND -> "NOT_FOUND";
				case FAILED -> "UNCERTAIN";
			};
			boolean applies = RECEIVED.equals(locked.state());
			// La respuesta que autoriza se conserva como artefacto; guardarla también aquí la duplicaría.
			attempts.complete(attemptId, outcome, result, !(applies && outcome.equals("AUTHORIZED")));
			if (!applies) {
				return locked.state();
			}
			switch (outcome) {
				case "AUTHORIZED" -> {
					artifacts.save(tenantId, documentId, ArtifactKind.SRI_AUTHORIZATION, result.rawResponse());
					documents.markAuthorized(tenantId, documentId, result.authorizationNumber(),
							result.authorizedAt());
					documents.transition(tenantId, documentId, RECEIVED, AUTHORIZED, null);
					webhooks.stateReached(tenantId, documentId, AUTHORIZED);
					announceAuthorized(tenantId, documentId);
					return AUTHORIZED;
				}
				case "NOT_AUTHORIZED" -> {
					documents.transition(tenantId, documentId, RECEIVED, NOT_AUTHORIZED,
							identifiers(result.messages(), result.sriStatus()));
					webhooks.stateReached(tenantId, documentId, NOT_AUTHORIZED);
					documents.schedule(tenantId, documentId, null, 0);
					return NOT_AUTHORIZED;
				}
				default -> {
					inconclusive(tenantId, documentId, RECEIVED, locked.attemptCount());
					return RECEIVED;
				}
			}
		});
	}

	/**
	 * Anota un intento sin resultado definitivo y programa el siguiente con una espera creciente.
	 * Agotados los intentos, el documento deja de reintentarse solo y queda constancia en su historial.
	 */
	private void inconclusive(UUID tenantId, UUID documentId, String state, int previousAttempts) {
		int count = previousAttempts + 1;
		if (count >= properties.retryMaxAttempts()) {
			documents.schedule(tenantId, documentId, null, count);
			documents.recordStateChange(tenantId, documentId, state, state, RETRIES_EXHAUSTED);
			log.warn("El documento {} agotó sus {} intentos ante el SRI en estado {}", documentId, count, state);
			return;
		}
		Duration delay = RetryPolicy.delay(count, properties.retryBackoff(), properties.retryMaxBackoff(),
				ThreadLocalRandom.current().nextDouble());
		documents.schedule(tenantId, documentId, delay, count);
	}

	private ProcessingState lock(UUID tenantId, UUID documentId) {
		return documents.lockForProcessing(tenantId, documentId).orElseThrow(ApiException::notFound);
	}

	private static String identifiers(ReceptionResult result) {
		return identifiers(result.messages(), result.sriStatus());
	}

	/** Identificadores de los mensajes del SRI, como motivo legible de un rechazo. */
	private static String identifiers(List<SriMessage> messages, String sriStatus) {
		String joined = messages.stream()
			.map(SriMessage::identifier)
			.filter(identifier -> identifier != null)
			.collect(Collectors.joining(", "));
		return joined.isEmpty() ? sriStatus : joined;
	}

}
