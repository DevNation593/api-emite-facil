-- Webhooks salientes: a qué direcciones avisa cada empresa y qué avisos están por salir.
CREATE TABLE webhook_subscriptions (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenants(id),
 url text NOT NULL CHECK (length(url) <= 2048),
 event_types text[] NOT NULL CHECK (cardinality(event_types) >= 1
  AND event_types <@ ARRAY['document.received','document.authorized','document.rejected','document.delivered']),
 description varchar(200),
 status varchar(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED')),
 -- MANUAL (la apagó la empresa) o TOO_MANY_FAILURES (se apagó sola).
 disabled_reason varchar(40),
 -- Cifrado con la clave maestra; en claro solo se entrega al crear y al rotar.
 secret bytea NOT NULL,
 -- El secreto anterior sigue firmando hasta que caduca, para rotar sin perder avisos.
 previous_secret bytea,
 previous_secret_expires_at timestamptz,
 -- Intentos fallidos seguidos y desde cuándo; un envío correcto borra ambos.
 consecutive_failures integer NOT NULL DEFAULT 0,
 failing_since timestamptz,
 created_by uuid NOT NULL REFERENCES users(id),
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE (tenant_id, id),
 CHECK ((status = 'DISABLED') = (disabled_reason IS NOT NULL))
);
CREATE INDEX idx_webhook_subscriptions_tenant ON webhook_subscriptions (tenant_id);

-- Un aviso por evento y suscripción. El mismo event_id para todas las suscripciones de un evento.
CREATE TABLE webhook_deliveries (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL,
 subscription_id uuid NOT NULL,
 event_id uuid NOT NULL,
 event_type varchar(40) NOT NULL,
 document_id uuid NOT NULL,
 -- Estado que se anuncia (el comprobante puede haber avanzado cuando el aviso sale).
 document_state varchar(30) NOT NULL,
 occurred_at timestamptz NOT NULL DEFAULT now(),
 status varchar(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','SENDING','SENT','FAILED')),
 attempt_count integer NOT NULL DEFAULT 0,
 -- Cuándo debe retomarse; nulo si no hay nada programado.
 next_attempt_at timestamptz,
 -- Tras un fallo temporal, no se reintenta antes de este momento.
 retry_after timestamptz,
 -- Inicio del envío en curso; pasado el plazo de envío se considera abandonado.
 sending_since timestamptz,
 -- Código HTTP de la última respuesta, si la hubo. El cuerpo no se guarda nunca.
 last_status integer,
 last_error text,
 sent_at timestamptz,
 FOREIGN KEY (tenant_id, subscription_id) REFERENCES webhook_subscriptions (tenant_id, id) ON DELETE CASCADE,
 FOREIGN KEY (tenant_id, document_id) REFERENCES fiscal_documents (tenant_id, id),
 UNIQUE (subscription_id, event_id)
);
CREATE INDEX idx_webhook_deliveries_subscription ON webhook_deliveries (subscription_id, occurred_at DESC);
CREATE INDEX idx_webhook_deliveries_document ON webhook_deliveries (document_id);
CREATE INDEX idx_webhook_deliveries_due ON webhook_deliveries (next_attempt_at) WHERE next_attempt_at IS NOT NULL;
