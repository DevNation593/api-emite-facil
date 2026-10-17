package com.example.einvoice.webhook;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.example.einvoice.certificate.SecretCipher;
import com.example.einvoice.compliance.AuditLog;
import com.example.einvoice.tenancy.TenantAccess;
import com.example.einvoice.web.ApiException;
import com.example.einvoice.web.PageResponse;

/**
 * Suscripciones de una empresa a los avisos de sus comprobantes. El secreto con que se firman lo
 * genera la aplicación, se entrega una vez y se guarda cifrado; ni él ni la dirección (que suele
 * llevar una ficha) salen de aquí hacia registros o auditoría.
 */
@Service
@EnableConfigurationProperties(WebhookProperties.class)
public class WebhookSubscriptionService {

	public static final List<String> EVENT_TYPES = List.of("document.received", "document.authorized",
			"document.rejected", "document.delivered");

	public static final String DISABLED_MANUALLY = "MANUAL";

	public static final String DISABLED_BY_FAILURES = "TOO_MANY_FAILURES";

	/** El aviso de prueba: no anuncia ningún comprobante y nadie se suscribe a él. */
	public static final String TEST_EVENT = "webhook.test";

	public record Subscription(UUID id, String url, List<String> eventTypes, String description, String status,
			String disabledReason, int consecutiveFailures, OffsetDateTime createdAt,
			OffsetDateTime previousSecretExpiresAt) {
	}

	/** La suscripción recién creada, con su secreto: es la única vez que se entrega. */
	public record Created(UUID id, String url, List<String> eventTypes, String description, String status,
			OffsetDateTime createdAt, String secret) {
	}

	public record RotatedSecret(String secret, OffsetDateTime previousSecretExpiresAt) {
	}

	/** Un aviso del historial de una suscripción. El cuerpo de la respuesta del receptor no se guarda. */
	public record Delivery(UUID id, UUID eventId, String eventType, UUID documentId, String status, int attemptCount,
			Integer lastStatus, String lastError, OffsetDateTime occurredAt, OffsetDateTime sentAt,
			OffsetDateTime nextAttemptAt) {
	}

	private record RetryTarget(String status, String eventType, String subscriptionStatus) {
	}

	private record Current(String url, String status) {
	}

	private record Row(UUID id, String url, String eventTypes, String description, String status, String disabledReason,
			int consecutiveFailures, OffsetDateTime createdAt, OffsetDateTime previousSecretExpiresAt) {

		Subscription view() {
			return new Subscription(id, url, List.of(eventTypes.split(",")), description, status, disabledReason,
					consecutiveFailures, createdAt, previousSecretExpiresAt);
		}

	}

	private static final String COLUMNS = """
			id, url, array_to_string(event_types, ',') AS event_types, description, status, disabled_reason,
			consecutive_failures, created_at,
			CASE WHEN previous_secret_expires_at > now() THEN previous_secret_expires_at END AS previous_secret_expires_at
			""";

	private final JdbcClient jdbc;

	private final AuditLog audit;

	private final SecretCipher cipher;

	private final WebhookUrlPolicy urls;

	private final WebhookProperties properties;

	private final TransactionTemplate tx;

	public WebhookSubscriptionService(JdbcClient jdbc, AuditLog audit, SecretCipher cipher, WebhookUrlPolicy urls,
			WebhookProperties properties, PlatformTransactionManager transactionManager) {
		this.tx = new TransactionTemplate(transactionManager);
		this.jdbc = jdbc;
		this.audit = audit;
		this.cipher = cipher;
		this.urls = urls;
		this.properties = properties;
	}

	/**
	 * Resolver el nombre de la dirección puede tardar lo que su servidor de nombres quiera, así que se
	 * hace antes de abrir la transacción: no retiene ni una conexión ni un bloqueo.
	 */
	public Created create(TenantAccess access, String url, List<String> eventTypes, String description) {
		access.requireWrite();
		requireEnabled();
		String address = allowed(url);
		return tx.execute(status -> insert(access, address, joined(eventTypes), description));
	}

	private Created insert(TenantAccess access, String address, String types, String description) {
		// Serializa las altas de la empresa para que el tope se decida de una en una, sin detener a
		// quien mientras tanto emite o registra algo de ella.
		jdbc.sql("SELECT id FROM tenants WHERE id = :tenant FOR NO KEY UPDATE")
			.param("tenant", access.tenantId())
			.query()
			.listOfRows();
		long existing = jdbc.sql("SELECT count(*) FROM webhook_subscriptions WHERE tenant_id = :tenant")
			.param("tenant", access.tenantId())
			.query(Long.class)
			.single();
		if (existing >= properties.maxSubscriptions()) {
			throw new ApiException(HttpStatus.CONFLICT, "WEBHOOK_LIMIT_REACHED",
					"La empresa ya tiene el máximo de suscripciones; borre alguna");
		}
		UUID id = UUID.randomUUID();
		String secret = WebhookSigner.newSecret();
		Row row = jdbc.sql("""
				INSERT INTO webhook_subscriptions (id, tenant_id, url, event_types, description, secret, created_by)
				VALUES (:id, :tenant, :url, string_to_array(:types, ','), :description, :secret, :user)
				""" + " RETURNING " + COLUMNS)
			.param("id", id)
			.param("tenant", access.tenantId())
			.param("url", address)
			.param("types", types)
			.param("description", blankToNull(description))
			.param("secret", encrypt(access.tenantId(), id, secret))
			.param("user", access.userId())
			.query(Row.class)
			.single();
		audit.record(access, "WEBHOOK_CREATED", "WEBHOOK", id, Map.of("eventTypes", types));
		Subscription view = row.view();
		return new Created(view.id(), view.url(), view.eventTypes(), view.description(), view.status(), view.createdAt(),
				secret);
	}

	@Transactional(readOnly = true)
	public List<Subscription> list(TenantAccess access) {
		access.requireWrite();
		return jdbc.sql("SELECT " + COLUMNS + " FROM webhook_subscriptions WHERE tenant_id = :tenant ORDER BY created_at, id")
			.param("tenant", access.tenantId())
			.query(Row.class)
			.list()
			.stream()
			.map(Row::view)
			.toList();
	}

	public Subscription update(TenantAccess access, UUID id, String url, List<String> eventTypes, String description,
			boolean enabled) {
		access.requireWrite();
		Current current = jdbc.sql("SELECT url, status FROM webhook_subscriptions WHERE id = :id AND tenant_id = :tenant")
			.param("id", id)
			.param("tenant", access.tenantId())
			.query(Current.class)
			.optional()
			.orElseThrow(ApiException::notFound);
		boolean turningOn = enabled && !"ACTIVE".equals(current.status());
		if (turningOn) {
			requireEnabled();
		}
		// Apagar una dirección que dejó de existir no puede depender de que exista: solo se comprueba
		// la que cambia o la que se vuelve a encender. Y, como al crear, fuera de la transacción.
		if (turningOn || !url.equals(current.url())) {
			allowed(url);
		}
		return tx.execute(status -> change(access, id, url, joined(eventTypes), description, enabled));
	}

	private Subscription change(TenantAccess access, UUID id, String address, String types, String description,
			boolean enabled) {
		lockedStatus(access, id);
		// Volver a encenderla es empezar de nuevo: la cuenta de fallos vuelve a cero.
		Row row = jdbc.sql("""
				UPDATE webhook_subscriptions
				   SET url = :url, event_types = string_to_array(:types, ','), description = :description,
				       status = CASE WHEN :enabled THEN 'ACTIVE' ELSE 'DISABLED' END,
				       disabled_reason = CASE WHEN :enabled THEN NULL
				                              WHEN status = 'DISABLED' THEN disabled_reason ELSE :manual END,
				       consecutive_failures = CASE WHEN :enabled AND status = 'DISABLED' THEN 0 ELSE consecutive_failures END,
				       failing_since = CASE WHEN :enabled AND status = 'DISABLED' THEN NULL ELSE failing_since END
				 WHERE id = :id AND tenant_id = :tenant
				""" + " RETURNING " + COLUMNS)
			.param("url", address)
			.param("types", types)
			.param("description", blankToNull(description))
			.param("enabled", enabled)
			.param("manual", DISABLED_MANUALLY)
			.param("id", id)
			.param("tenant", access.tenantId())
			.query(Row.class)
			.single();
		audit.record(access, "WEBHOOK_UPDATED", "WEBHOOK", id,
				Map.of("eventTypes", types, "enabled", enabled));
		return row.view();
	}

	@Transactional
	public void delete(TenantAccess access, UUID id) {
		access.requireWrite();
		lockedStatus(access, id);
		// Con ella se va su historial de avisos; queda el evento de auditoría.
		jdbc.sql("DELETE FROM webhook_subscriptions WHERE id = :id AND tenant_id = :tenant")
			.param("id", id)
			.param("tenant", access.tenantId())
			.update();
		audit.record(access, "WEBHOOK_DELETED", "WEBHOOK", id, Map.of());
	}

	/** Cambia el secreto. El anterior sigue firmando un tiempo para que el receptor pueda cambiarlo sin perder avisos. */
	@Transactional
	public RotatedSecret rotate(TenantAccess access, UUID id) {
		access.requireWrite();
		lockedStatus(access, id);
		String secret = WebhookSigner.newSecret();
		OffsetDateTime expires = jdbc.sql("""
				UPDATE webhook_subscriptions
				   SET previous_secret = secret, secret = :secret,
				       previous_secret_expires_at = now() + make_interval(secs => CAST(:overlap AS double precision))
				 WHERE id = :id AND tenant_id = :tenant
				RETURNING previous_secret_expires_at
				""")
			.param("secret", encrypt(access.tenantId(), id, secret))
			.param("overlap", (double) properties.secretOverlap().toSeconds())
			.param("id", id)
			.param("tenant", access.tenantId())
			.query(OffsetDateTime.class)
			.single();
		audit.record(access, "WEBHOOK_SECRET_ROTATED", "WEBHOOK", id, Map.of());
		return new RotatedSecret(secret, expires);
	}

	/** Avisos de la suscripción, los más recientes primero. */
	@Transactional(readOnly = true)
	public PageResponse<Delivery> deliveries(TenantAccess access, UUID subscriptionId, int page, int size) {
		access.requireWrite();
		PageResponse.request(page, size);
		long total = jdbc.sql("""
				SELECT (SELECT count(*) FROM webhook_deliveries d WHERE d.subscription_id = s.id)
				  FROM webhook_subscriptions s WHERE s.id = :id AND s.tenant_id = :tenant
				""")
			.param("id", subscriptionId)
			.param("tenant", access.tenantId())
			.query(Long.class)
			.optional()
			.orElseThrow(ApiException::notFound);
		List<Delivery> items = jdbc.sql("""
				SELECT id, event_id, event_type, document_id, status, attempt_count, last_status, last_error, occurred_at,
				       sent_at, next_attempt_at
				  FROM webhook_deliveries WHERE subscription_id = :id AND tenant_id = :tenant
				 ORDER BY occurred_at DESC, id LIMIT :size OFFSET :offset
				""")
			.param("id", subscriptionId)
			.param("tenant", access.tenantId())
			.param("size", size)
			.param("offset", (long) page * size)
			.query(Delivery.class)
			.list();
		return new PageResponse<>(items, page, size, total);
	}

	/**
	 * Anota un aviso de prueba para la suscripción. Sale como los demás (firmado, por la cola de avisos)
	 * pero se intenta una sola vez y no cuenta para apagar la suscripción. Mientras uno esté en camino
	 * no se admite otro: nadie puede usar esto para lanzar peticiones en ráfaga contra una dirección.
	 */
	@Transactional
	public Delivery requestTest(TenantAccess access, UUID subscriptionId) {
		access.requireWrite();
		// La fila bloqueada serializa las peticiones de prueba de la suscripción.
		String status = lockedStatus(access, subscriptionId);
		requireEnabled();
		if (!"ACTIVE".equals(status)) {
			throw new ApiException(HttpStatus.CONFLICT, "WEBHOOK_SUBSCRIPTION_DISABLED",
					"La suscripción está desactivada; actívela antes de probarla");
		}
		boolean pending = jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM webhook_deliveries
				 WHERE subscription_id = :id AND event_type = :test AND status IN ('PENDING', 'SENDING'))
				""").param("id", subscriptionId).param("test", TEST_EVENT).query(Boolean.class).single();
		if (pending) {
			throw new ApiException(HttpStatus.CONFLICT, "WEBHOOK_TEST_PENDING",
					"Ya hay un aviso de prueba en camino a esta dirección; espere a que termine");
		}
		// Como los demás avisos, sale cuando el planificador lo devuelve a la cola, en un instante.
		Delivery delivery = jdbc.sql("""
				INSERT INTO webhook_deliveries (tenant_id, subscription_id, event_id, event_type, next_attempt_at)
				VALUES (:tenant, :id, :eventId, :test, now())
				RETURNING id, event_id, event_type, document_id, status, attempt_count, last_status, last_error,
				          occurred_at, sent_at, next_attempt_at
				""")
			.param("tenant", access.tenantId())
			.param("id", subscriptionId)
			.param("eventId", UUID.randomUUID())
			.param("test", TEST_EVENT)
			.query(Delivery.class)
			.single();
		audit.record(access, "WEBHOOK_TEST_REQUESTED", "WEBHOOK", subscriptionId, Map.of());
		return delivery;
	}

	/** Vuelve a poner en marcha un aviso que agotó sus intentos. Sale con el identificador de siempre. */
	@Transactional
	public void retry(TenantAccess access, UUID deliveryId) {
		access.requireWrite();
		// El aviso de otra empresa se trata igual que uno inexistente.
		RetryTarget target = jdbc.sql("""
				SELECT d.status, d.event_type, s.status AS subscription_status
				  FROM webhook_deliveries d JOIN webhook_subscriptions s ON s.id = d.subscription_id
				 WHERE d.id = :id AND d.tenant_id = :tenant FOR UPDATE OF d
				""")
			.param("id", deliveryId)
			.param("tenant", access.tenantId())
			.query(RetryTarget.class)
			.optional()
			.orElseThrow(ApiException::notFound);
		requireEnabled();
		if (TEST_EVENT.equals(target.eventType())) {
			throw new ApiException(HttpStatus.CONFLICT, "WEBHOOK_DELIVERY_NOT_RETRYABLE",
					"Un aviso de prueba no se reintenta: envíe otro");
		}
		if (!"FAILED".equals(target.status())) {
			throw new ApiException(HttpStatus.CONFLICT, "WEBHOOK_DELIVERY_NOT_RETRYABLE",
					"Solo se reintenta un aviso que falló; este está pendiente o ya se entregó");
		}
		if (!"ACTIVE".equals(target.subscriptionStatus())) {
			throw new ApiException(HttpStatus.CONFLICT, "WEBHOOK_SUBSCRIPTION_DISABLED",
					"La suscripción está desactivada; actívela antes de reintentar sus avisos");
		}
		jdbc.sql("""
				UPDATE webhook_deliveries
				   SET status = 'PENDING', attempt_count = 0, retry_after = NULL, sending_since = NULL, next_attempt_at = now()
				 WHERE id = :id
				""").param("id", deliveryId).update();
		audit.record(access, "WEBHOOK_DELIVERY_RETRIED", "WEBHOOK_DELIVERY", deliveryId,
				Map.of());
	}

	/** Contexto con que se cifra el secreto: uno guardado para una suscripción no vale para otra. */
	static byte[] secretContext(UUID tenantId, UUID subscriptionId) {
		return ("webhook|" + tenantId + "|" + subscriptionId).getBytes(StandardCharsets.UTF_8);
	}

	private byte[] encrypt(UUID tenantId, UUID subscriptionId, String secret) {
		return cipher.encrypt(secret.getBytes(StandardCharsets.UTF_8), secretContext(tenantId, subscriptionId));
	}

	/** Estado de la suscripción, con su fila bloqueada. La de otra empresa se trata como inexistente. */
	private String lockedStatus(TenantAccess access, UUID id) {
		// NO KEY: no detiene a quien, mientras tanto, anota un aviso de esta suscripción.
		return jdbc.sql("SELECT status FROM webhook_subscriptions WHERE id = :id AND tenant_id = :tenant FOR NO KEY UPDATE")
			.param("id", id)
			.param("tenant", access.tenantId())
			.query(String.class)
			.optional()
			.orElseThrow(ApiException::notFound);
	}

	private void requireEnabled() {
		if (!properties.enabled()) {
			throw new ApiException(HttpStatus.CONFLICT, "WEBHOOKS_DISABLED",
					"Los avisos a otros sistemas no están habilitados en esta instalación");
		}
	}

	/** La dirección, si se admite y su servidor es público. */
	private String allowed(String url) {
		try {
			urls.vet(urls.parse(url));
			return url;
		}
		catch (WebhookUrlException ex) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "WEBHOOK_URL_NOT_ALLOWED", ex.getMessage());
		}
	}

	private static String joined(List<String> eventTypes) {
		return String.join(",", new LinkedHashSet<>(eventTypes));
	}

	private static String blankToNull(String text) {
		return text == null || text.isBlank() ? null : text.strip();
	}

}
