package com.example.einvoice.delivery;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.einvoice.billing.FiscalDocumentRepository;
import com.example.einvoice.billing.FiscalDocumentRepository.ProcessingState;
import com.example.einvoice.billing.OutboxWriter;
import com.example.einvoice.compliance.AuditLog;
import com.example.einvoice.delivery.DeliveryRepository.DeliveryRow;
import com.example.einvoice.tenancy.TenantAccess;
import com.example.einvoice.web.ApiException;

/**
 * Entregas de un comprobante al receptor: consulta de lo enviado y petición de un nuevo envío, a la
 * dirección del cliente o a otra. El envío lo hace el worker; aquí solo se registra la petición.
 */
@RestController
@RequestMapping("/api/v1/documents/{id}/deliveries")
public class DeliveryController {

	public static final String DELIVERY_REQUESTED = "DELIVERY_REQUESTED";

	private static final String AUTHORIZED = "AUTHORIZED";

	record DeliveryRequest(String email) {
	}

	record DeliveryView(UUID id, String channel, String recipient, String status, int attemptCount, String lastError,
			Instant createdAt, Instant sentAt, boolean automatic) {

		static DeliveryView of(DeliveryRow row) {
			return new DeliveryView(row.id(), row.channel(), row.recipient(), row.status(), row.attemptCount(),
					row.lastError(), instant(row.createdAt()), instant(row.sentAt()), row.requestedBy() == null);
		}

		private static Instant instant(OffsetDateTime time) {
			return time == null ? null : time.toInstant();
		}

	}

	private final FiscalDocumentRepository documents;

	private final DeliveryRepository deliveries;

	private final OutboxWriter outbox;

	private final AuditLog audit;

	private final DeliveryProperties properties;

	public DeliveryController(FiscalDocumentRepository documents, DeliveryRepository deliveries, OutboxWriter outbox,
			AuditLog audit, DeliveryProperties properties) {
		this.documents = documents;
		this.deliveries = deliveries;
		this.outbox = outbox;
		this.audit = audit;
		this.properties = properties;
	}

	@GetMapping
	@Transactional(readOnly = true)
	public List<DeliveryView> list(TenantAccess access, @PathVariable UUID id) {
		documents.processingState(access.tenantId(), id).orElseThrow(ApiException::notFound);
		return deliveries.list(access.tenantId(), id).stream().map(DeliveryView::of).toList();
	}

	@PostMapping
	@ResponseStatus(HttpStatus.ACCEPTED)
	@Transactional
	public DeliveryView request(TenantAccess access, @PathVariable UUID id,
			@RequestBody(required = false) DeliveryRequest request) {
		access.requireBilling();
		// El bloqueo ordena peticiones simultáneas: el tope por comprobante no se puede superar a la vez.
		ProcessingState locked = documents.lockForProcessing(access.tenantId(), id).orElseThrow(ApiException::notFound);
		if (!AUTHORIZED.equals(locked.state())) {
			throw new ApiException(HttpStatus.CONFLICT, "DOCUMENT_NOT_AUTHORIZED",
					"Solo se entrega un comprobante autorizado");
		}
		if (!properties.email().enabled()) {
			throw new ApiException(HttpStatus.CONFLICT, "DELIVERY_DISABLED",
					"El envío de comprobantes por correo no está habilitado");
		}
		String requested = request == null || request.email() == null ? "" : request.email().strip();
		String recipient = requested.isEmpty()
				? documents.customerEmail(access.tenantId(), id).map(String::strip).orElse("") : requested;
		if (recipient.isEmpty()) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DELIVERY_RECIPIENT_MISSING",
					"El comprobante no tiene correo del cliente: indique una dirección");
		}
		if (!DeliveryProperties.validAddress(recipient)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
					"La dirección de correo no es válida");
		}
		if (deliveries.count(id) >= properties.maxPerDocument()) {
			throw new ApiException(HttpStatus.CONFLICT, "DELIVERY_LIMIT_REACHED",
					"El comprobante alcanzó el máximo de entregas");
		}
		UUID deliveryId = deliveries.create(access.tenantId(), id, recipient, access.userId());
		outbox.write(access.tenantId(), id, DELIVERY_REQUESTED, Map.of("documentId", id.toString()));
		// La dirección no va a la auditoría: es un dato del cliente y ya consta en la entrega.
		audit.record(access, DELIVERY_REQUESTED, "FISCAL_DOCUMENT", id,
				Map.of("deliveryId", deliveryId.toString()));
		return DeliveryView.of(deliveries.find(access.tenantId(), deliveryId).orElseThrow());
	}

}
