package com.example.einvoice.orchestration;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.einvoice.billing.FiscalDocumentRepository;
import com.example.einvoice.billing.FiscalDocumentRepository.ProcessingState;
import com.example.einvoice.billing.OutboxWriter;
import com.example.einvoice.compliance.AuditLog;
import com.example.einvoice.tenancy.TenantAccess;
import com.example.einvoice.web.ApiException;

/**
 * Reintento manual de un comprobante que quedó a medio camino: sin firmar por falta de certificado,
 * o sin respuesta definitiva del SRI tras agotar los reintentos automáticos. No cambia el estado:
 * devuelve el documento a la cola para que el worker lo retome donde está.
 */
@RestController
@RequestMapping("/api/v1/documents/{id}/retry")
public class DocumentRetryController {

	public static final String RETRY_REQUESTED = "DOCUMENT_RETRY_REQUESTED";

	/** Estados desde los que el pipeline tiene algo que hacer. Los rechazos del SRI no se reintentan tal cual. */
	private static final Set<String> RETRYABLE = Set.of(DocumentProcessor.RESERVED, DocumentProcessor.GENERATED,
			DocumentProcessor.SIGNATURE_FAILED, DocumentProcessor.SIGNED, DocumentPipeline.SENDING,
			DocumentPipeline.RECEIVED);

	record RetryAccepted(UUID id, String state) {
	}

	private final FiscalDocumentRepository documents;

	private final OutboxWriter outbox;

	private final AuditLog audit;

	public DocumentRetryController(FiscalDocumentRepository documents, OutboxWriter outbox, AuditLog audit) {
		this.documents = documents;
		this.outbox = outbox;
		this.audit = audit;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.ACCEPTED)
	@Transactional
	public RetryAccepted retry(TenantAccess access, @PathVariable UUID id) {
		access.requireBilling();
		ProcessingState locked = documents.lockForProcessing(access.tenantId(), id).orElseThrow(ApiException::notFound);
		if (!RETRYABLE.contains(locked.state())) {
			throw new ApiException(HttpStatus.CONFLICT, "DOCUMENT_NOT_RETRYABLE",
					"El comprobante está en un estado que no admite reintento");
		}
		documents.schedule(access.tenantId(), id, Duration.ZERO, 0);
		outbox.write(access.tenantId(), id, RETRY_REQUESTED, Map.of("documentId", id.toString()));
		audit.record(access, RETRY_REQUESTED, "FISCAL_DOCUMENT", id,
				Map.of("state", locked.state()));
		return new RetryAccepted(id, locked.state());
	}

}
