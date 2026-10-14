package com.example.einvoice.delivery;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

import com.example.einvoice.orchestration.DocumentPipeline;
import com.example.einvoice.storage.ArtifactKind;
import com.example.einvoice.storage.ArtifactStore;
import com.example.einvoice.support.AbstractIntegrationTest;
import com.example.einvoice.support.BillingScenario;
import com.example.einvoice.support.SmtpStub;
import com.example.einvoice.support.TestCertificates;

import jakarta.mail.BodyPart;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;

/**
 * Base de las pruebas de entrega: una factura autorizada de un emisor con certificado, cuyo cliente
 * tiene un correo único para que el guion del SMTP simulado no se mezcle con el de otras pruebas.
 */
abstract class AbstractDeliveryIT extends AbstractIntegrationTest {

	/** Remitente configurado en las pruebas (AbstractIntegrationTest). */
	static final String SENDER = "facturas@plataforma.test";

	@Autowired
	DocumentPipeline pipeline;

	@Autowired
	DeliveryService deliveries;

	@Autowired
	ArtifactStore artifacts;

	final SmtpStub smtp = SmtpStub.instance();

	BillingScenario s;

	String email;

	UUID document;

	String key;

	@BeforeEach
	void authorizedInvoiceOfACustomerWithEmail() throws Exception {
		s = newBillingScenario();
		uploadCertificate(s.owner(), s.taxpayer(), TestCertificates.valid());
		email = "c-" + UUID.randomUUID() + "@cliente.test";
		jdbc.update("UPDATE customers SET email = ? WHERE id = ?", email, s.customer());
		document = issueInvoice(s.owner(), s.invoiceJson("2", "23.00"));
		key = jdbc.queryForObject("SELECT access_key FROM fiscal_documents WHERE id = ?", String.class, document);
		pipeline.advance(s.tenant(), document);
	}

	/** La única entrega del comprobante, como mapa de columnas. */
	Map<String, Object> delivery() {
		return jdbc.queryForMap("SELECT * FROM deliveries WHERE document_id = ?", document);
	}

	long deliveryCount() {
		return jdbc.queryForObject("SELECT count(*) FROM deliveries WHERE document_id = ?", Long.class, document);
	}

	OffsetDateTime nextDeliveryAttempt() {
		return jdbc.queryForObject("SELECT next_attempt_at FROM deliveries WHERE document_id = ?",
				OffsetDateTime.class, document);
	}

	byte[] artifact(ArtifactKind kind) {
		return artifacts.find(s.tenant(), document, kind).orElseThrow().content();
	}

	/** Adjuntos de un mensaje: nombre de archivo y contenido. */
	static List<Map.Entry<String, byte[]>> attachments(MimeMessage message) throws MessagingException, IOException {
		List<Map.Entry<String, byte[]>> found = new ArrayList<>();
		collect(message, found);
		return found;
	}

	private static void collect(Part part, List<Map.Entry<String, byte[]>> into) throws MessagingException, IOException {
		if (part.getContent() instanceof Multipart multipart) {
			for (int i = 0; i < multipart.getCount(); i++) {
				BodyPart child = multipart.getBodyPart(i);
				collect(child, into);
			}
		}
		else if (part.getFileName() != null) {
			into.add(Map.entry(part.getFileName(), part.getInputStream().readAllBytes()));
		}
	}

	/** Texto del cuerpo del mensaje (la parte sin nombre de archivo). */
	static String body(Part part) throws MessagingException, IOException {
		if (part.getContent() instanceof Multipart multipart) {
			for (int i = 0; i < multipart.getCount(); i++) {
				String text = body(multipart.getBodyPart(i));
				if (text != null) {
					return text;
				}
			}
			return null;
		}
		return part.getFileName() == null && part.getContent() instanceof String text ? text : null;
	}

}
