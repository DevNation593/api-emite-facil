package com.example.einvoice.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.example.einvoice.sri.SriProperties;
import com.example.einvoice.sri.SriProperties.Endpoints;

/** El RUC del proveedor (Resolución NAC-DGERCGC26-00000027) se exige en cuanto se transmite a producción. */
class ProviderRucTest {

	private static final Endpoints TEST = new Endpoints(
			URI.create("https://celcer.sri.gob.ec/comprobantes-electronicos-ws/RecepcionComprobantesOffline"),
			URI.create("https://celcer.sri.gob.ec/comprobantes-electronicos-ws/AutorizacionComprobantesOffline"));

	private static final Endpoints PRODUCTION = new Endpoints(
			URI.create("https://cel.sri.gob.ec/comprobantes-electronicos-ws/RecepcionComprobantesOffline"),
			URI.create("https://cel.sri.gob.ec/comprobantes-electronicos-ws/AutorizacionComprobantesOffline"));

	private static SriProperties transmitting(String... environments) {
		return new SriProperties(Set.of(environments), false, TEST, PRODUCTION, Duration.ofSeconds(10),
				Duration.ofSeconds(60), Duration.ofSeconds(3), Duration.ofSeconds(15), Duration.ofHours(1), 30);
	}

	@Test
	void isOptionalWhileProductionIsClosed() {
		assertThat(DocumentIssuer.providerRuc("", transmitting())).isNull();
		assertThat(DocumentIssuer.providerRuc(" ", transmitting("1"))).isNull();
	}

	@Test
	void isRequiredOnceProductionIsOpen() {
		assertThatThrownBy(() -> DocumentIssuer.providerRuc("", transmitting("1", "2")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("EINVOICE_PROVIDER_RUC");
		assertThat(DocumentIssuer.providerRuc("1790099999001", transmitting("1", "2"))).isEqualTo("1790099999001");
	}

	@Test
	void mustHaveThirteenDigits() {
		assertThatThrownBy(() -> DocumentIssuer.providerRuc("179009999900", transmitting()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("13 dígitos");
	}

}
