package com.example.einvoice.compliance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import com.example.einvoice.support.AbstractIntegrationTest;
import com.example.einvoice.support.BillingScenario;

/**
 * La bitácora (quién hizo qué) y la historia de estados de cada comprobante solo crecen: ni la
 * aplicación ni quien tenga sus credenciales puede reescribirlas o borrarlas.
 */
class AuditImmutabilityIT extends AbstractIntegrationTest {

	@Test
	void whatWasRecordedCannotBeRewrittenOrErased() throws Exception {
		BillingScenario s = newBillingScenario();
		UUID document = issueInvoice(s.owner(), s.invoiceJson("2", "23.00"));
		int audit = jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE tenant_id = ?", Integer.class, s.tenant());
		int states = jdbc.queryForObject("SELECT count(*) FROM state_events WHERE document_id = ?", Integer.class, document);
		assertThat(audit).isPositive();
		assertThat(states).isPositive();

		for (String statement : new String[] { "UPDATE audit_events SET action = 'OTRA' WHERE tenant_id = '" + s.tenant() + "'",
				"DELETE FROM audit_events WHERE tenant_id = '" + s.tenant() + "'", "TRUNCATE audit_events",
				"UPDATE state_events SET new_state = 'AUTHORIZED' WHERE document_id = '" + document + "'",
				"DELETE FROM state_events WHERE document_id = '" + document + "'", "TRUNCATE state_events" }) {
			assertThatThrownBy(() -> jdbc.execute(statement)).as(statement)
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("solo inserción");
		}

		assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE tenant_id = ?", Integer.class, s.tenant()))
			.isEqualTo(audit);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM state_events WHERE document_id = ?", Integer.class, document))
			.isEqualTo(states);
		// Y sigue creciendo: emitir otra factura deja sus registros.
		issueInvoice(s.owner(), s.invoiceJson("1", "11.50"));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_events WHERE tenant_id = ?", Integer.class, s.tenant()))
			.isGreaterThan(audit);
	}

}
