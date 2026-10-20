package com.example.einvoice.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.einvoice.billing.DocumentKinds;
import com.example.einvoice.billing.FiscalDocumentRepository;
import com.example.einvoice.support.SmtpStub.Reply;
import org.springframework.transaction.PlatformTransactionManager;
import com.example.einvoice.webhook.WebhookRecorder;

/** Fallos del correo: reintentos con espera creciente, rechazo definitivo y envíos que se quedaron a medias. */
class DeliveryRetryIT extends AbstractDeliveryIT {

	@Autowired
	DocumentKinds kinds;

	@Autowired
	WebhookRecorder webhookRecorder;

	@Autowired
	ObjectProvider<DeliveryMailer> mailers;

	@Autowired
	PlatformTransactionManager transactionManager;

	@Autowired
	DocumentArchiver archiver;

	@Autowired
	FiscalDocumentRepository documents;

	@Autowired
	DeliveryRepository repository;

	@Autowired
	DeliveryProperties properties;

	/** Da por cumplida la espera del reintento, como si hubiera pasado el tiempo. */
	void waitIsOver() {
		jdbc.update("UPDATE deliveries SET retry_after = now() - interval '1 second' WHERE document_id = ?", document);
	}

	@Test
	void temporaryFailureIsRetriedLater() {
		smtp.onRecipient(email, Reply.failData(451, "4.3.0 Intente más tarde"), Reply.accept());

		assertThat(deliveries.process(s.tenant(), document)).isZero();

		Map<String, Object> failed = delivery();
		assertThat(failed).containsEntry("status", "PENDING").containsEntry("attempt_count", 1);
		assertThat((String) failed.get("last_error")).contains("451");
		assertThat(failed.get("sent_at")).isNull();
		assertThat(nextDeliveryAttempt()).isAfter(OffsetDateTime.now());
		assertThat(smtp.messages(email)).isEmpty();

		// Mientras dura la espera no se reintenta, aunque llegue otro evento del mismo comprobante.
		assertThat(deliveries.process(s.tenant(), document)).isZero();
		assertThat(delivery()).containsEntry("status", "PENDING").containsEntry("attempt_count", 1);
		assertThat(smtp.messages(email)).isEmpty();

		// Lo que hará el worker cuando el planificador lo devuelva a la cola, cumplida la espera.
		waitIsOver();
		assertThat(deliveries.process(s.tenant(), document)).isEqualTo(1);

		assertThat(delivery()).containsEntry("status", "SENT")
			.containsEntry("attempt_count", 2)
			.containsEntry("next_attempt_at", null)
			.containsEntry("last_error", null);
		assertThat(smtp.messages(email)).hasSize(1);
	}

	@Test
	void droppedConnectionIsRetried() {
		smtp.onRecipient(email, Reply.dropConnection(), Reply.accept());

		assertThat(deliveries.process(s.tenant(), document)).isZero();
		assertThat(delivery()).containsEntry("status", "PENDING");
		assertThat((String) delivery().get("last_error")).isNotBlank();

		waitIsOver();
		assertThat(deliveries.process(s.tenant(), document)).isEqualTo(1);
		assertThat(smtp.messages(email)).hasSize(1);
	}

	@Test
	void rejectedRecipientFailsForGood() {
		smtp.onRecipient(email, Reply.rejectRecipient(550, "5.1.1 El buzón no existe"));

		assertThat(deliveries.process(s.tenant(), document)).isZero();

		Map<String, Object> row = delivery();
		assertThat(row).containsEntry("status", "FAILED")
			.containsEntry("attempt_count", 1)
			.containsEntry("next_attempt_at", null);
		assertThat((String) row.get("last_error")).contains("550");

		// Un rechazo definitivo no se vuelve a intentar solo.
		assertThat(deliveries.process(s.tenant(), document)).isZero();
		assertThat(delivery()).containsEntry("attempt_count", 1);
		assertThat(smtp.messages(email)).isEmpty();
	}

	@Test
	void retriesAreExhausted() {
		smtp.onRecipient(email, Reply.failData(451, "4.3.0 Intente más tarde"));
		deliveries.process(s.tenant(), document);
		int max = properties.retryMaxAttempts();
		jdbc.update("UPDATE deliveries SET attempt_count = ? WHERE document_id = ?", max - 1, document);
		waitIsOver();

		assertThat(deliveries.process(s.tenant(), document)).isZero();

		assertThat(delivery()).containsEntry("status", "FAILED")
			.containsEntry("attempt_count", max)
			.containsEntry("next_attempt_at", null);
	}

	@Test
	void attemptsOverTheCapFailWithoutSending() {
		smtp.onRecipient(email, Reply.failData(451, "4.3.0 Intente más tarde"), Reply.accept());
		deliveries.process(s.tenant(), document);
		// Una entrega que ya superó el máximo (por ejemplo, tras fallos que nadie pudo anotar).
		jdbc.update("UPDATE deliveries SET attempt_count = ? WHERE document_id = ?", properties.retryMaxAttempts(),
				document);
		waitIsOver();

		assertThat(deliveries.process(s.tenant(), document)).isZero();

		assertThat(delivery()).containsEntry("status", "FAILED").containsEntry("next_attempt_at", null);
		assertThat(smtp.messages(email)).isEmpty();
	}

	@Test
	void aFailureToAnnounceTheDeliveryDoesNotUnmarkASentEmail() {
		// El correo ya salió: si anotar el aviso falla, no puede volver a salir por eso.
		WebhookRecorder failing = new WebhookRecorder(null, null) {
			@Override
			public void delivered(UUID tenantId, UUID documentId) {
				throw new IllegalStateException("no se pudo anotar el aviso");
			}
		};
		DeliveryService service = new DeliveryService(archiver, documents, repository, artifacts, properties, mailers, failing,
				transactionManager, kinds);

		assertThat(service.process(s.tenant(), document)).isEqualTo(1);

		assertThat(delivery()).containsEntry("status", "SENT");
		assertThat(service.process(s.tenant(), document)).isZero();
		assertThat(smtp.messages(email)).hasSize(1);
	}

	@Test
	void unexpectedMailerFailureIsRecordedNotLeftSending() {
		DeliveryMailer broken = message -> {
			throw new IllegalStateException("fallo inesperado con datos internos smtp.interno:25");
		};
		ObjectProvider<DeliveryMailer> provider = new ObjectProvider<>() {
			@Override
			public DeliveryMailer getObject() {
				return broken;
			}

			@Override
			public DeliveryMailer getIfAvailable() {
				return broken;
			}
		};
		DeliveryService service = new DeliveryService(archiver, documents, repository, artifacts, properties, provider, webhookRecorder, transactionManager, kinds);

		assertThat(service.process(s.tenant(), document)).isZero();

		// Queda anotado como un fallo reintentable, no "enviando" para siempre.
		Map<String, Object> row = delivery();
		assertThat(row).containsEntry("status", "PENDING").containsEntry("attempt_count", 1);
		assertThat((String) row.get("last_error")).contains("IllegalStateException").doesNotContain("smtp.interno");
		assertThat(nextDeliveryAttempt()).isAfter(OffsetDateTime.now());
	}

	@Test
	void invalidFrozenAddressFailsWithoutContactingTheServer() {
		String group = "grupo:a-" + UUID.randomUUID() + "@x.test,b@y.test;";
		jdbc.update("UPDATE fiscal_documents SET customer_email = ? WHERE id = ?", group, document);

		assertThat(deliveries.process(s.tenant(), document)).isZero();

		Map<String, Object> row = delivery();
		assertThat(row).containsEntry("status", "FAILED").containsEntry("next_attempt_at", null);
		assertThat((String) row.get("last_error")).contains("no válida");
		assertThat(smtp.messages("b@y.test")).isEmpty();
	}

	@Test
	void abandonedSendIsTakenOverAfterTheLease() {
		smtp.onRecipient(email, Reply.failData(451, "4.3.0 Intente más tarde"), Reply.accept());
		deliveries.process(s.tenant(), document);

		// Un envío en curso reciente no se toca: otro proceso lo está haciendo.
		jdbc.update("UPDATE deliveries SET status = 'SENDING', sending_since = now() WHERE document_id = ?", document);
		assertThat(deliveries.process(s.tenant(), document)).isZero();
		assertThat(delivery()).containsEntry("status", "SENDING").containsEntry("attempt_count", 1);
		assertThat(smtp.messages(email)).isEmpty();

		// Uno que lleva diez minutos "en curso" murió a medias: se retoma.
		jdbc.update("UPDATE deliveries SET sending_since = now() - interval '10 minutes' WHERE document_id = ?",
				document);
		assertThat(deliveries.process(s.tenant(), document)).isEqualTo(1);
		assertThat(delivery()).containsEntry("status", "SENT");
		assertThat(smtp.messages(email)).hasSize(1);
	}

	@Test
	void supersededAttemptCannotOverwriteTheResult() {
		smtp.onRecipient(email, Reply.failData(451, "4.3.0 Intente más tarde"));
		deliveries.process(s.tenant(), document);
		waitIsOver();
		UUID id = (UUID) delivery().get("id");
		Duration lease = properties.sendLease();
		// El proceso A reserva la entrega (intento 2) y tarda tanto que B la toma (intento 3).
		int attemptA = repository.claim(id, lease).orElseThrow().attemptCount();
		jdbc.update("UPDATE deliveries SET sending_since = now() - interval '1 hour' WHERE id = ?", id);
		int attemptB = repository.claim(id, lease).orElseThrow().attemptCount();
		assertThat(attemptB).isEqualTo(attemptA + 1);

		// A falla tarde: no puede devolverla a pendiente mientras B la tiene.
		repository.markRetry(id, attemptA, Duration.ofSeconds(1), "fallo de A");
		assertThat(delivery()).containsEntry("status", "SENDING").doesNotContainEntry("last_error", "fallo de A");
		repository.markFailed(id, attemptA, "fallo de A");
		assertThat(delivery()).containsEntry("status", "SENDING");

		// Un envío aceptado es un hecho: se anota aunque el intento ya no sea el vigente…
		repository.markSent(id, "<mensaje-de-a@plataforma.test>");
		assertThat(delivery()).containsEntry("status", "SENT");

		// …y un fallo posterior del otro intento no lo deshace.
		repository.markFailed(id, attemptB, "fallo de B");
		repository.markRetry(id, attemptB, Duration.ofSeconds(1), "fallo de B");
		assertThat(delivery()).containsEntry("status", "SENT")
			.containsEntry("provider_message_id", "<mensaje-de-a@plataforma.test>")
			.containsEntry("next_attempt_at", null);
	}

	@Test
	void deliveryFailureNeverChangesTheFiscalState() {
		archiver.archive(s.tenant(), document);
		String before = fiscalSnapshot();
		smtp.onRecipient(email, Reply.rejectRecipient(550, "5.1.1 El buzón no existe"));

		deliveries.process(s.tenant(), document);

		assertThat(delivery()).containsEntry("status", "FAILED");
		assertThat(fiscalSnapshot()).isEqualTo(before);
	}

	private String fiscalSnapshot() {
		return jdbc.queryForObject("""
				SELECT d.state || '|' || d.version || '|' || d.authorization_number || '|'
				 || (SELECT count(*) FROM document_artifacts a WHERE a.document_id = d.id) || '|'
				 || (SELECT count(*) FROM state_events e WHERE e.document_id = d.id)
				FROM fiscal_documents d WHERE d.id = ?
				""", String.class, document);
	}

}
