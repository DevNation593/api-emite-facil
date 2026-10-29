package com.example.einvoice.billing;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.einvoice.billing.FiscalDocumentRepository.NewDocument;
import com.example.einvoice.catalog.Customer;
import com.example.einvoice.compliance.AuditLog;
import com.example.einvoice.sequence.AccessKeyParts;
import com.example.einvoice.sequence.FiscalClock;
import com.example.einvoice.sequence.NumericCodeGenerator;
import com.example.einvoice.sequence.SequenceService;
import com.example.einvoice.sri.SriEnvironment;
import com.example.einvoice.sri.SriProperties;
import com.example.einvoice.taxpayer.Taxpayer;
import com.example.einvoice.taxpayer.TaxpayerService;
import com.example.einvoice.tenancy.TenantAccess;
import com.example.einvoice.web.ApiException;

/**
 * Emisión de un comprobante de cualquier tipo hasta el estado RESERVED: lo que no depende del tipo. Todo
 * ocurre en una transacción: si algo falla, incluido el XML, no queda documento, ni secuencial consumido, ni
 * evento por publicar, y la clave de idempotencia queda libre. Las validaciones de la petición van antes de
 * reservar el secuencial; el XML solo puede armarse con el secuencial real, así que se genera y valida
 * después de reservarlo, con la fila del contador bloqueada hasta confirmar o revertir.
 */
@Service
public class DocumentIssuer {

	public static final String STATE_RESERVED = "RESERVED";

	/** Evento del outbox de todo comprobante reservado; lo recoge la cola de emisión. */
	public static final String EVENT_RESERVED = "DOCUMENT_RESERVED";

	/** Tipo de emisión normal: el único del esquema off-line. */
	private static final String NORMAL_EMISSION = "1";

	private static final String CURRENCY = "USD";

	/** Largo máximo del detalle de un XML inválido, como el motivo de XSD_INVALID. */
	private static final int MAX_PROBLEM_LENGTH = 500;

	/** Fallos sin reintento posible: el SRI lo rechazó, o con esos datos no sale un XML válido. */
	private static final Set<String> REPLACEABLE_STATES = Set.of("RETURNED", "NOT_AUTHORIZED", "XSD_INVALID");

	/**
	 * Lo común de la petición de emisión. issueDate y currency pueden ser nulos (hoy y USD);
	 * replacesDocumentId, nulo si no es una reemisión.
	 */
	public record IssueRequest(UUID taxpayerId, String establishmentCode, String emissionPointCode,
			LocalDate issueDate, String currency, UUID replacesDocumentId) {
	}

	/** Lo que el tipo necesita saber para preparar su parte: el emisor y la fecha de emisión ya resuelta. */
	public record IssueContext(Taxpayer taxpayer, LocalDate issueDate) {
	}

	/**
	 * La parte del tipo, ya validada: el receptor (su fila del catálogo de clientes y proveedores y los datos que
	 * el comprobante congela de él), los totales (o nulo), el documento modificado (o nulo) y su contenido
	 * propio. Los datos del receptor pueden no ser los del catálogo de hoy: una nota copia los de su factura.
	 */
	public record Preparation<C>(DocumentKind<C> kind, UUID customerId, DocumentEnvelope.Recipient recipient,
			DocumentEnvelope.Totals totals, DocumentEnvelope.ModifiedDocument modified, C content) {

		/** El receptor tal como está hoy en el catálogo. */
		public static <C> Preparation<C> forCustomer(DocumentKind<C> kind, Customer customer,
				DocumentEnvelope.Totals totals, DocumentEnvelope.ModifiedDocument modified, C content) {
			return new Preparation<>(kind, customer.getId(), recipientOf(customer), totals, modified, content);
		}

		/** Los datos que el comprobante congela de un cliente del catálogo. */
		public static DocumentEnvelope.Recipient recipientOf(Customer customer) {
			return new DocumentEnvelope.Recipient(customer.getIdType(), customer.getIdNumber(), customer.getName(),
					customer.getAddress(), customer.getEmail(), customer.getPhone());
		}

	}

	private record EmissionPointRef(UUID id, boolean enabled, String establishmentAddress) {
	}

	private final TaxpayerService taxpayers;

	private final SequenceService sequences;

	private final NumericCodeGenerator numericCodes;

	private final FiscalDocumentRepository documents;

	private final DocumentXmlCheck xmlCheck;

	private final OutboxWriter outbox;

	private final AuditLog audit;

	private final IdempotencyStore idempotency;

	private final JdbcClient jdbc;

	/** Plazo tras el cual un comprobante que nadie procesó vuelve a la cola. */
	private final Duration redeliveryLease;

	/** RUC del proveedor del sistema de facturación (ficha, Anexo 26); nulo si no está configurado. */
	private final String providerRuc;

	public DocumentIssuer(TaxpayerService taxpayers, SequenceService sequences, NumericCodeGenerator numericCodes,
			FiscalDocumentRepository documents, DocumentXmlCheck xmlCheck, OutboxWriter outbox, AuditLog audit,
			IdempotencyStore idempotency, JdbcClient jdbc, @Value("${einvoice.provider.ruc:}") String providerRuc,
			SriProperties sri, @Value("${einvoice.worker.redelivery-lease:5m}") Duration redeliveryLease) {
		this.taxpayers = taxpayers;
		this.sequences = sequences;
		this.numericCodes = numericCodes;
		this.documents = documents;
		this.xmlCheck = xmlCheck;
		this.outbox = outbox;
		this.audit = audit;
		this.idempotency = idempotency;
		this.jdbc = jdbc;
		this.redeliveryLease = redeliveryLease;
		this.providerRuc = providerRuc(providerRuc, sri);
	}

	/**
	 * El RUC del proveedor configurado, o nulo si no lo hay. Con la transmisión a producción abierta es
	 * obligatorio (Resolución NAC-DGERCGC26-00000027, art. 5): la aplicación no arranca sin él, porque cada
	 * comprobante lo congela al emitirse y uno emitido sin él ya no lo puede llevar.
	 */
	static String providerRuc(String configured, SriProperties sri) {
		if (configured.isBlank()) {
			if (sri.enabled(SriEnvironment.PRODUCTION)) {
				throw new IllegalStateException("Con la transmisión a producción abierta (einvoice.sri.enabled-environments"
						+ " con 2) hay que definir el RUC del proveedor: einvoice.provider.ruc (EINVOICE_PROVIDER_RUC)");
			}
			return null;
		}
		if (!configured.matches("[0-9]{13}")) {
			throw new IllegalStateException("einvoice.provider.ruc debe ser un RUC de 13 dígitos");
		}
		return configured;
	}

	/**
	 * Emite el comprobante, o devuelve el ya emitido si la clave de idempotencia se repite con la misma
	 * petición. prepare valida y arma la parte propia del tipo; corre dentro de la transacción, antes de
	 * reservar el secuencial. El XML se genera y valida con el secuencial y la clave reales: si no es válido
	 * responde 422 DOCUMENT_XML_INVALID y la transacción se revierte.
	 */
	@Transactional
	public <C> UUID issue(TenantAccess access, String idempotencyKey, String requestHash, IssueRequest request,
			Function<IssueContext, Preparation<C>> prepare) {
		access.requireBilling();
		Optional<UUID> previous = idempotency.begin(access.tenantId(), idempotencyKey, requestHash);
		if (previous.isPresent()) {
			return previous.get();
		}
		String replacedType = request.replacesDocumentId() == null ? null
				: requireReplaceable(access, request.replacesDocumentId(), request.taxpayerId());
		Taxpayer taxpayer = taxpayers.get(access, request.taxpayerId());
		EmissionPointRef point = emissionPoint(access, taxpayer, request);
		if (!point.enabled()) {
			throw unprocessable("EMISSION_POINT_DISABLED", "El punto de emisión está deshabilitado");
		}
		String currency = request.currency() == null ? CURRENCY : request.currency();
		if (!CURRENCY.equals(currency)) {
			throw unprocessable("UNSUPPORTED_CURRENCY", "Solo se emiten comprobantes en USD");
		}
		LocalDate today = FiscalClock.dateOf(Instant.now());
		LocalDate issueDate = request.issueDate() == null ? today : request.issueDate();
		if (issueDate.isAfter(today)) {
			throw unprocessable("INVALID_ISSUE_DATE", "La fecha de emisión no puede ser futura");
		}

		Preparation<C> preparation = prepare.apply(new IssueContext(taxpayer, issueDate));
		DocumentKind<C> kind = preparation.kind();
		if (replacedType != null && !replacedType.equals(kind.type().code())) {
			throw new ApiException(HttpStatus.CONFLICT, "REPLACED_DOCUMENT_OTHER_TYPE",
					"El comprobante que se reemplaza es de otro tipo");
		}

		String environment = taxpayer.getDefaultEnvironment();
		String documentType = kind.type().code();
		int sequence = sequences.reserve(access.tenantId(), point.id(), environment, documentType, issueDate);
		String accessKey = new AccessKeyParts(issueDate, documentType, taxpayer.getRuc(), environment,
				request.establishmentCode(), request.emissionPointCode(), sequence, numericCodes.next(),
				NORMAL_EMISSION)
			.toAccessKey();
		DocumentEnvelope envelope = new DocumentEnvelope(kind.type(), environment,
				new DocumentEnvelope.Issuer(taxpayer.getLegalName(), taxpayer.getTradeName(), taxpayer.getRuc(),
						taxpayer.getHeadOfficeAddress(), point.establishmentAddress(), taxpayer.isAccountingRequired(),
						taxpayer.getSpecialTaxpayerCode(), taxpayer.getWithholdingAgentCode(),
						taxpayer.getRimpeRegime().name(), taxpayer.getPhone()),
				accessKey, request.establishmentCode(), request.emissionPointCode(), sequence, issueDate,
				preparation.recipient(), preparation.totals(), preparation.modified(), providerRuc);
		// Se valida el contenido como el worker lo leerá, no el objeto en memoria.
		requireValidXml(kind, envelope, kind.contentJson(preparation.content()) == null ? preparation.content()
				: kind.reloaded(preparation.content()));

		UUID documentId = documents.insert(new NewDocument(access.tenantId(), taxpayer.getId(), point.id(),
				preparation.customerId(), STATE_RESERVED, access.userId(), envelope, kind.contentJson(preparation.content())));
		kind.saveDetails(access.tenantId(), documentId, preparation.content());
		documents.recordStateChange(access.tenantId(), documentId, null, STATE_RESERVED, null);
		audit.record(access, kind.auditReserved(), "FISCAL_DOCUMENT", documentId,
				Map.of("sequence", sequence, "environment", environment));
		outbox.write(access.tenantId(), documentId, EVENT_RESERVED, Map.of("documentId", documentId.toString()));
		if (request.replacesDocumentId() != null) {
			documents.linkReplacement(access.tenantId(), documentId, request.replacesDocumentId());
			audit.record(access, "DOCUMENT_REPLACED", "FISCAL_DOCUMENT", request.replacesDocumentId(),
					Map.of("replacedBy", documentId.toString()));
		}
		// Red de seguridad: si ese evento se pierde o nadie logra procesarlo, el planificador retoma el documento.
		documents.schedule(access.tenantId(), documentId, redeliveryLease, 0);
		idempotency.complete(access.tenantId(), idempotencyKey, documentId);
		return documentId;
	}

	/**
	 * El XML que el worker generará después desde lo guardado: si no cumple su XSD y las reglas del sobre,
	 * no se emite. No se guarda; el worker lo vuelve a generar igual.
	 */
	private <C> void requireValidXml(DocumentKind<C> kind, DocumentEnvelope envelope, C content) {
		byte[] xml;
		try {
			xml = kind.buildXml(envelope, content);
		}
		catch (IllegalArgumentException ex) {
			// Un dato que el XML no admite (un carácter de control, un régimen desconocido).
			throw xmlInvalid(ex.getMessage());
		}
		xmlCheck.problem(xml, kind.schema()).ifPresent(problem -> {
			throw xmlInvalid(problem);
		});
	}

	private static ApiException xmlInvalid(String problem) {
		String detail = problem == null ? "El XML del comprobante no es válido" : problem;
		return unprocessable("DOCUMENT_XML_INVALID",
				detail.length() > MAX_PROBLEM_LENGTH ? detail.substring(0, MAX_PROBLEM_LENGTH) : detail);
	}

	/**
	 * Solo se reemplaza un comprobante propio que el SRI devolvió o no autorizó, o cuyo XML no se pudo
	 * generar, y una sola vez. Lo deja bloqueado: dos reemisiones a la vez del mismo comprobante se deciden de
	 * una en una. Devuelve su tipo, que debe ser el del comprobante nuevo.
	 */
	private String requireReplaceable(TenantAccess access, UUID replacedId, UUID taxpayerId) {
		var replaced = documents.lockForProcessing(access.tenantId(), replacedId).orElseThrow(ApiException::notFound);
		if (!REPLACEABLE_STATES.contains(replaced.state())) {
			throw new ApiException(HttpStatus.CONFLICT, "REPLACED_DOCUMENT_NOT_REJECTED",
					"Solo se reemplaza un comprobante devuelto o no autorizado por el SRI, o cuyo XML no se pudo generar");
		}
		// El enlace es único y no se deshace: no se gasta en un comprobante de otro RUC.
		if (!replaced.taxpayerId().equals(taxpayerId)) {
			throw new ApiException(HttpStatus.CONFLICT, "REPLACED_DOCUMENT_OTHER_ISSUER",
					"El comprobante que se reemplaza es de otro emisor de la empresa");
		}
		if (documents.replacementOf(access.tenantId(), replacedId).isPresent()) {
			throw new ApiException(HttpStatus.CONFLICT, "DOCUMENT_ALREADY_REPLACED",
					"Ese comprobante ya fue reemplazado por otro");
		}
		return replaced.documentType();
	}

	/** El punto de emisión se localiza por los códigos del comprobante, siempre dentro del emisor y del tenant. */
	private EmissionPointRef emissionPoint(TenantAccess access, Taxpayer taxpayer, IssueRequest request) {
		return jdbc.sql("""
				SELECT ep.id, ep.enabled, e.address AS establishment_address
				FROM emission_points ep JOIN establishments e ON e.id = ep.establishment_id
				WHERE e.tenant_id = :tenant AND e.taxpayer_id = :taxpayer
				  AND e.code = :establishmentCode AND ep.code = :emissionPointCode
				""")
			.param("tenant", access.tenantId())
			.param("taxpayer", taxpayer.getId())
			.param("establishmentCode", request.establishmentCode())
			.param("emissionPointCode", request.emissionPointCode())
			.query(EmissionPointRef.class)
			.optional()
			.orElseThrow(ApiException::notFound);
	}

	private static ApiException unprocessable(String errorCode, String detail) {
		return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, errorCode, detail);
	}

}
