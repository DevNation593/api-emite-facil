package com.example.einvoice.sri;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.einvoice.billing.FiscalDocumentRepository;
import com.example.einvoice.sri.SriAttemptLog.AttemptView;
import com.example.einvoice.sri.SriAttemptLog.MessageView;
import com.example.einvoice.tenancy.TenantAccess;
import com.example.einvoice.web.ApiException;

/**
 * Intentos de un comprobante ante el SRI, con los mensajes que devolvió. Es lo que mira un operador
 * para saber por qué un comprobante no avanza. Nunca expone la respuesta cruda.
 */
@RestController
@RequestMapping("/api/v1/documents/{id}/sri-attempts")
public class SriAttemptController {

	record SriAttemptView(UUID id, String operation, String outcome, String sriStatus, Integer httpStatus,
			Instant startedAt, Instant completedAt, List<MessageView> messages) {

		static SriAttemptView of(AttemptView attempt) {
			return new SriAttemptView(attempt.id(), attempt.operation(), attempt.outcome(), attempt.sriStatus(),
					attempt.httpStatus(), instant(attempt.startedAt()), instant(attempt.completedAt()),
					attempt.messages());
		}

		private static Instant instant(OffsetDateTime time) {
			return time == null ? null : time.toInstant();
		}

	}

	private final FiscalDocumentRepository documents;

	private final SriAttemptLog attempts;

	public SriAttemptController(FiscalDocumentRepository documents, SriAttemptLog attempts) {
		this.documents = documents;
		this.attempts = attempts;
	}

	@GetMapping
	@Transactional(readOnly = true)
	public List<SriAttemptView> list(TenantAccess access, @PathVariable UUID id) {
		documents.processingState(access.tenantId(), id).orElseThrow(ApiException::notFound);
		return attempts.list(access.tenantId(), id).stream().map(SriAttemptView::of).toList();
	}

}
