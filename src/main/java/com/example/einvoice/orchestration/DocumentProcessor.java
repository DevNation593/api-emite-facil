package com.example.einvoice.orchestration;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.einvoice.billing.DocumentEnvelope;
import com.example.einvoice.billing.DocumentKind;
import com.example.einvoice.billing.DocumentKinds;
import com.example.einvoice.billing.DocumentXmlCheck;
import com.example.einvoice.billing.FiscalDocumentRepository;
import com.example.einvoice.billing.FiscalDocumentRepository.ProcessingState;
import com.example.einvoice.certificate.CertificateRejectedException;
import com.example.einvoice.certificate.CertificateService;
import com.example.einvoice.certificate.SecretCipherException;
import com.example.einvoice.certificate.SigningMaterial;
import com.example.einvoice.signature.SignatureVerification;
import com.example.einvoice.signature.XadesVerifier;
import com.example.einvoice.signature.XmlSigner;
import com.example.einvoice.storage.ArtifactKind;
import com.example.einvoice.storage.ArtifactStore;
import com.example.einvoice.web.ApiException;
import com.example.einvoice.xml.DocumentSchema;
import com.example.einvoice.xml.DocumentType;

import tools.jackson.core.JacksonException;

/**
 * Lleva un comprobante de RESERVED a SIGNED: genera el XML con la pieza de su tipo, lo valida, lo
 * firma y guarda ambos XML como artefactos. Es reentrante: se puede llamar de nuevo sobre el mismo
 * documento sin repetir trabajo ni duplicar artefactos, y reintenta la firma si antes no fue posible.
 */
@Service
public class DocumentProcessor {

	private static final Logger log = LoggerFactory.getLogger(DocumentProcessor.class);

	public static final String RESERVED = "RESERVED";

	public static final String GENERATED = "GENERATED";

	public static final String SIGNED = "SIGNED";

	public static final String XSD_INVALID = "XSD_INVALID";

	public static final String SIGNATURE_FAILED = "SIGNATURE_FAILED";

	private static final int MAX_REASON_LENGTH = 500;

	private final FiscalDocumentRepository documents;

	private final DocumentKinds kinds;

	private final DocumentXmlCheck xmlCheck;

	private final CertificateService certificates;

	private final XmlSigner signer;

	private final XadesVerifier verifier;

	private final ArtifactStore artifacts;

	public DocumentProcessor(FiscalDocumentRepository documents, DocumentKinds kinds, DocumentXmlCheck xmlCheck,
			CertificateService certificates, XmlSigner signer, XadesVerifier verifier, ArtifactStore artifacts) {
		this.documents = documents;
		this.kinds = kinds;
		this.xmlCheck = xmlCheck;
		this.certificates = certificates;
		this.signer = signer;
		this.verifier = verifier;
		this.artifacts = artifacts;
	}

	/** Devuelve el estado en que queda el documento. */
	@Transactional
	public String generateAndSign(UUID tenantId, UUID documentId) {
		ProcessingState locked = documents.lockForProcessing(tenantId, documentId).orElseThrow(ApiException::notFound);
		String state = locked.state();
		if (RESERVED.equals(state)) {
			state = generate(tenantId, documentId);
		}
		if (GENERATED.equals(state) || SIGNATURE_FAILED.equals(state)) {
			state = sign(tenantId, documentId, locked.taxpayerId(), state);
		}
		return state;
	}

	private String generate(UUID tenantId, UUID documentId) {
		DocumentEnvelope envelope = documents.envelope(tenantId, documentId).orElseThrow(ApiException::notFound);
		Optional<DocumentKind<?>> found = kinds.find(envelope.type());
		if (found.isEmpty()) {
			return fail(tenantId, documentId, RESERVED, XSD_INVALID, "DOCUMENT_TYPE_NOT_SUPPORTED");
		}
		DocumentKind<?> kind = found.get();
		byte[] xml;
		try {
			xml = build(kind, tenantId, documentId, envelope);
		}
		catch (UnreadableContentException ex) {
			log.error("El contenido del documento {} no se pudo leer", documentId, ex.getCause());
			return fail(tenantId, documentId, RESERVED, XSD_INVALID, "CONTENT_UNREADABLE");
		}
		catch (IllegalArgumentException ex) {
			return fail(tenantId, documentId, RESERVED, XSD_INVALID, ex.getMessage());
		}
		Optional<String> problem = xmlCheck.problem(xml, kind.schema());
		if (problem.isPresent()) {
			return fail(tenantId, documentId, RESERVED, XSD_INVALID, problem.get());
		}
		artifacts.save(tenantId, documentId, ArtifactKind.XML_UNSIGNED, xml);
		documents.setSchemaVersion(tenantId, documentId, kind.schema().version());
		documents.transition(tenantId, documentId, RESERVED, GENERATED, null);
		return GENERATED;
	}

	private <C> byte[] build(DocumentKind<C> kind, UUID tenantId, UUID documentId, DocumentEnvelope envelope) {
		C content;
		try {
			content = kind.loadContent(tenantId, documentId);
		}
		catch (JacksonException | IllegalStateException ex) {
			throw new UnreadableContentException(ex);
		}
		return kind.buildXml(envelope, content);
	}

	/** El contenido propio del comprobante no existe o no encaja en el tipo. */
	private static final class UnreadableContentException extends RuntimeException {

		UnreadableContentException(Throwable cause) {
			super(cause);
		}

	}

	private String sign(UUID tenantId, UUID documentId, UUID taxpayerId, String from) {
		byte[] unsigned = artifacts.find(tenantId, documentId, ArtifactKind.XML_UNSIGNED)
			.orElseThrow(() -> new IllegalStateException("Documento generado sin su XML: " + documentId))
			.content();
		Instant now = Instant.now();
		// Ningún fallo de esta parte debe deshacer la generación ya hecha ni quedar sin registro: el
		// documento pasa a SIGNATURE_FAILED con su motivo y se puede reintentar.
		byte[] signed;
		try {
			SigningMaterial material = certificates.signingMaterial(tenantId, taxpayerId, now);
			signed = signer.sign(unsigned, material, now);
		}
		catch (CertificateRejectedException ex) {
			return fail(tenantId, documentId, from, SIGNATURE_FAILED, ex.errorCode());
		}
		catch (SecretCipherException ex) {
			log.error("El certificado guardado del emisor {} no se pudo descifrar", taxpayerId, ex);
			return fail(tenantId, documentId, from, SIGNATURE_FAILED, "CERTIFICATE_UNDECRYPTABLE");
		}
		catch (IllegalArgumentException | IllegalStateException ex) {
			log.error("No se pudo firmar el documento {}", documentId, ex);
			return fail(tenantId, documentId, from, SIGNATURE_FAILED, "SIGNING_ERROR");
		}
		// Nada se da por firmado sin comprobarlo: la firma debe verificar y el XML seguir siendo válido.
		SignatureVerification verification = verifier.verify(signed);
		if (!verification.valid() || xmlCheck.problem(signed, schemaOf(tenantId, documentId)).isPresent()) {
			return fail(tenantId, documentId, from, SIGNATURE_FAILED, "SIGNATURE_NOT_VERIFIABLE");
		}
		artifacts.save(tenantId, documentId, ArtifactKind.XML_SIGNED, signed);
		documents.transition(tenantId, documentId, from, SIGNED, null);
		return SIGNED;
	}

	/** Esquema con que se generó el documento: su tipo y la versión guardada al generarlo. */
	private DocumentSchema schemaOf(UUID tenantId, UUID documentId) {
		DocumentType type = documents.envelope(tenantId, documentId).orElseThrow(ApiException::notFound).type();
		String version = documents.schemaVersion(tenantId, documentId)
			.orElseThrow(() -> new IllegalStateException("Documento generado sin versión de esquema: " + documentId));
		return DocumentSchema.of(type, version);
	}

	/**
	 * Deja el documento en un estado de fallo con su motivo. Si ya estaba en ese estado, solo añade
	 * un evento cuando el motivo es otro: reintentar el mismo fallo no llena el historial.
	 */
	private String fail(UUID tenantId, UUID documentId, String from, String failure, String reason) {
		String recorded = truncate(reason);
		if (!failure.equals(from)) {
			documents.transition(tenantId, documentId, from, failure, recorded);
		}
		else if (!recorded.equals(documents.lastReason(tenantId, documentId, failure).orElse(null))) {
			documents.recordStateChange(tenantId, documentId, from, failure, recorded);
		}
		return failure;
	}

	private static String truncate(String reason) {
		String text = String.valueOf(reason);
		return text.length() <= MAX_REASON_LENGTH ? text : text.substring(0, MAX_REASON_LENGTH);
	}

	/** Estados desde los que {@link #generateAndSign} tiene trabajo pendiente. */
	public static List<String> pendingStates() {
		return List.of(RESERVED, GENERATED, SIGNATURE_FAILED);
	}

}
