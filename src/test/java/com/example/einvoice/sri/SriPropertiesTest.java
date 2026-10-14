package com.example.einvoice.sri;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.example.einvoice.sri.SriProperties.Endpoints;

class SriPropertiesTest {

	private static final Endpoints TEST = new Endpoints(
			URI.create("https://celcer.sri.gob.ec/comprobantes-electronicos-ws/RecepcionComprobantesOffline"),
			URI.create("https://celcer.sri.gob.ec/comprobantes-electronicos-ws/AutorizacionComprobantesOffline"));

	private static final Endpoints PRODUCTION = new Endpoints(
			URI.create("https://cel.sri.gob.ec/comprobantes-electronicos-ws/RecepcionComprobantesOffline"),
			URI.create("https://cel.sri.gob.ec/comprobantes-electronicos-ws/AutorizacionComprobantesOffline"));

	private static SriProperties properties(Set<String> enabled, boolean custom, Endpoints test) {
		return new SriProperties(enabled, custom, test, PRODUCTION, Duration.ofSeconds(10), Duration.ofSeconds(60),
				Duration.ofSeconds(3), Duration.ofSeconds(15), Duration.ofHours(1), 30);
	}

	@Test
	void officialHostsAreAccepted() {
		assertThatCode(() -> properties(Set.of(), false, TEST)).doesNotThrowAnyException();
	}

	@Test
	void otherHostIsRejectedUnlessCustomEndpointsAreAllowed() {
		Endpoints elsewhere = new Endpoints(URI.create("https://ejemplo.test/recepcion"), TEST.authorization());

		assertThatThrownBy(() -> properties(Set.of(), false, elsewhere)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("einvoice.sri");
		assertThatCode(() -> properties(Set.of(), true, elsewhere)).doesNotThrowAnyException();
	}

	@Test
	void plainHttpIsRejected() {
		Endpoints plain = new Endpoints(
				URI.create("http://celcer.sri.gob.ec/comprobantes-electronicos-ws/RecepcionComprobantesOffline"),
				TEST.authorization());

		assertThatThrownBy(() -> properties(Set.of(), false, plain)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void lookalikeHostIsRejected() {
		Endpoints lookalike = new Endpoints(URI.create("https://celcer.sri.gob.ec.ejemplo.test/recepcion"),
				URI.create("https://ejemplo.test/?celcer.sri.gob.ec"));

		assertThatThrownBy(() -> properties(Set.of(), false, lookalike)).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void missingEndpointIsRejectedEvenWithCustomEndpoints() {
		assertThatThrownBy(() -> properties(Set.of(), true, new Endpoints(null, TEST.authorization())))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(
				() -> properties(Set.of(), true, new Endpoints(URI.create("ftp://ejemplo.test/x"), TEST.authorization())))
			.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void environmentIsEnabledOnlyWhenListed() {
		assertThat(properties(Set.of(), false, TEST).enabled(SriEnvironment.TEST)).isFalse();
		assertThat(properties(null, false, TEST).enabled(SriEnvironment.TEST)).isFalse();
		SriProperties onlyTest = properties(Set.of("1"), false, TEST);
		assertThat(onlyTest.enabled(SriEnvironment.TEST)).isTrue();
		assertThat(onlyTest.enabled(SriEnvironment.PRODUCTION)).isFalse();
		assertThat(onlyTest.endpoints(SriEnvironment.TEST)).isEqualTo(TEST);
		assertThat(onlyTest.endpoints(SriEnvironment.PRODUCTION)).isEqualTo(PRODUCTION);
	}

	@Test
	void unknownEnvironmentCodeIsRejected() {
		assertThatThrownBy(() -> properties(Set.of("3"), false, TEST)).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("enabled-environments");
	}

	@Test
	void sendLeaseCoversBothTimeouts() {
		assertThat(properties(Set.of(), false, TEST).sendLease()).isEqualTo(Duration.ofSeconds(100));
	}

}
