package com.example.einvoice.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.einvoice.billing.DocumentKinds;
import com.example.einvoice.billing.FiscalDocumentRepository;
import com.example.einvoice.storage.ArtifactKind;
import com.example.einvoice.support.BillingScenario;
import com.example.einvoice.support.SmtpStub.Reply;
import com.example.einvoice.support.TestCertificates;
import com.example.einvoice.web.ApiException;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.transaction.PlatformTransactionManager;
import com.example.einvoice.webhook.WebhookRecorder;

/** Entrega por correo del comprobante autorizado, contra un servidor SMTP simulado. */
class DeliveryIT extends AbstractDeliveryIT {

	@Autowired
	DocumentKinds kinds;

	@Autowired
	WebhookRecorder webhookRecorder;

	@Autowired
	PlatformTransactionManager transactionManager;

	@Autowired
	DocumentArchiver archiver;

	@Autowired
	FiscalDocumentRepository documents;

	@Autowired
	DeliveryRepository repository;

	@Test
	void authorizedInvoiceIsEmailedWithRideAndXml() throws Exception {
		assertThat(deliveries.process(s.tenant(), document)).isEqualTo(1);

		List<MimeMessage> received = smtp.messages(email);
		assertThat(received).hasSize(1);
		MimeMessage message = received.get(0);
		InternetAddress from = (InternetAddress) message.getFrom()[0];
		assertThat(from.getAddress()).isEqualTo(SENDER);
		assertThat(from.getPersonal()).isEqualTo("Andina");
		assertThat(message.getAllRecipients()).hasSize(1);
		assertThat(((InternetAddress) message.getAllRecipients()[0]).getAddress()).isEqualTo(email);
		assertThat(message.getSubject()).startsWith("Factura 001-001-").endsWith(" de Andina");
		assertThat(body(message)).contains(key, "23.00", "María Pérez");
		List<Map.Entry<String, byte[]>> attached = attachments(message);
		assertThat(attached).hasSize(2);
		assertThat(attached.get(0).getKey()).startsWith("factura-001-001-").endsWith(".pdf");
		assertThat(attached.get(0).getValue()).isEqualTo(artifact(ArtifactKind.RIDE_PDF));
		assertThat(attached.get(1).getKey()).isEqualTo(key + ".xml");
		assertThat(attached.get(1).getValue()).isEqualTo(artifact(ArtifactKind.XML_AUTHORIZED));

		Map<String, Object> row = delivery();
		assertThat(row).containsEntry("status", "SENT")
			.containsEntry("channel", "EMAIL")
			.containsEntry("recipient", email)
			.containsEntry("attempt_count", 1)
			.containsEntry("requested_by", null)
			.containsEntry("next_attempt_at", null)
			.containsEntry("last_error", null);
		assertThat(row.get("sent_at")).isNotNull();
		assertThat((String) row.get("provider_message_id")).isNotBlank();
		assertThat(row.get("tenant_id")).isEqualTo(s.tenant());
		assertThat(jdbc.queryForObject("SELECT state FROM fiscal_documents WHERE id = ?", String.class, document))
			.isEqualTo("AUTHORIZED");
	}

	@Test
	void processingAgainSendsNothing() {
		deliveries.process(s.tenant(), document);

		assertThat(deliveries.process(s.tenant(), document)).isZero();

		assertThat(smtp.messages(email)).hasSize(1);
		assertThat(deliveryCount()).isEqualTo(1);
	}

	@Test
	void concurrentProcessingSendsOneEmail() throws Exception {
		smtp.onRecipient(email, Reply.delayed(Duration.ofMillis(500), Reply.accept()));
		int threads = 4;
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		int sent = 0;
		try {
			List<Future<Integer>> futures = new ArrayList<>();
			for (int t = 0; t < threads; t++) {
				futures.add(pool.submit(() -> {
					start.await();
					return deliveries.process(s.tenant(), document);
				}));
			}
			start.countDown();
			for (Future<Integer> future : futures) {
				sent += future.get();
			}
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(sent).isEqualTo(1);
		assertThat(smtp.messages(email)).hasSize(1);
		assertThat(deliveryCount()).isEqualTo(1);
		assertThat(delivery()).containsEntry("status", "SENT").containsEntry("attempt_count", 1);
	}

	@Test
	void customerWithoutEmailIsArchivedButNotEmailed() throws Exception {
		BillingScenario other = newBillingScenario();
		uploadCertificate(other.owner(), other.taxpayer(), TestCertificates.valid());
		jdbc.update("UPDATE customers SET email = NULL WHERE id = ?", other.customer());
		UUID invoice = issueInvoice(other.owner(), other.invoiceJson("1", "11.50"));
		pipeline.advance(other.tenant(), invoice);

		assertThat(deliveries.process(other.tenant(), invoice)).isZero();

		assertThat(jdbc.queryForObject("SELECT count(*) FROM deliveries WHERE document_id = ?", Long.class, invoice))
			.isZero();
		assertThat(artifacts.find(other.tenant(), invoice, ArtifactKind.RIDE_PDF)).isPresent();
		assertThat(artifacts.find(other.tenant(), invoice, ArtifactKind.XML_AUTHORIZED)).isPresent();
	}

	@Test
	void emailDisabledArchivesWithoutCreatingDeliveries() {
		DeliveryProperties disabled = new DeliveryProperties(new DeliveryProperties.Email(false, null),
				Duration.ofMinutes(1), Duration.ofHours(6), 8, Duration.ofMinutes(2), 10);
		// Con el correo desactivado no existe ningún remitente: pedirlo es un error.
		ObjectProvider<DeliveryMailer> noMailer = new ObjectProvider<>() {
			@Override
			public DeliveryMailer getObject() {
				throw new AssertionError("Con el correo desactivado no se debe intentar ningún envío");
			}
		};
		DeliveryService offline = new DeliveryService(archiver, documents, repository, artifacts, disabled, noMailer, webhookRecorder, transactionManager, kinds);

		assertThat(offline.process(s.tenant(), document)).isZero();

		assertThat(deliveryCount()).isZero();
		assertThat(smtp.messages(email)).isEmpty();
		assertThat(artifacts.find(s.tenant(), document, ArtifactKind.RIDE_PDF)).isPresent();
	}

	@Test
	void enabledWithoutAMailerFailsAtStartup() {
		DeliveryProperties enabled = new DeliveryProperties(new DeliveryProperties.Email(true, SENDER),
				Duration.ofMinutes(1), Duration.ofHours(6), 8, Duration.ofMinutes(2), 10);
		ObjectProvider<DeliveryMailer> none = new ObjectProvider<>() {
			@Override
			public DeliveryMailer getIfAvailable() {
				return null;
			}
		};

		// Sin remitente real, cada envío fallaría en silencio: mejor no arrancar.
		assertThatThrownBy(() -> new DeliveryService(archiver, documents, repository, artifacts, enabled, none, webhookRecorder, transactionManager, kinds))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("einvoice.delivery.email.enabled");
	}

	@Test
	void unauthorizedDocumentIsNeitherArchivedNorEmailed() throws Exception {
		UUID reserved = issueInvoice(s.owner(), s.invoiceJson("1", "11.50"));

		assertThat(deliveries.process(s.tenant(), reserved)).isZero();

		assertThat(jdbc.queryForObject("SELECT count(*) FROM deliveries WHERE document_id = ?", Long.class, reserved))
			.isZero();
		assertThat(artifacts.find(s.tenant(), reserved, ArtifactKind.RIDE_PDF)).isEmpty();
		assertThat(smtp.messages(email)).isEmpty();
	}

	@Test
	void foreignDocumentIsNotFound() throws Exception {
		UUID otherTenant = createTenant(newUserToken());

		assertThatThrownBy(() -> deliveries.process(otherTenant, document)).isInstanceOf(ApiException.class);

		assertThat(deliveryCount()).isZero();
		assertThat(smtp.messages(email)).isEmpty();
	}

}
