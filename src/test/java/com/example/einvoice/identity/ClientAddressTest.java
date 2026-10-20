package com.example.einvoice.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** La IP sale de la entrada que puso el último proxy de confianza, no de la que escribe quien llama. */
class ClientAddressTest {

	static String ip(int hops, String... headers) {
		return ClientAddress.fromForwardedFor(List.of(headers), hops);
	}

	@Test
	void aProxyThatReplacesTheHeaderLeavesOneEntry() {
		assertThat(ip(1, "203.0.113.9")).isEqualTo("203.0.113.9");
		assertThat(ip(1, " 2001:db8::1 ")).isEqualTo("2001:db8::1");
	}

	@Test
	void whatTheCallerWritesToTheLeftDoesNotCount() {
		// Cloud Run directo: el frente de Google añade la IP real al final.
		assertThat(ip(1, "1.2.3.4, 203.0.113.9")).isEqualTo("203.0.113.9");
		assertThat(ip(1, "1.2.3.4, 5.6.7.8, 203.0.113.9")).isEqualTo("203.0.113.9");
		// La web pone la IP del navegador y el frente de Google añade la de la web.
		assertThat(ip(2, "203.0.113.9, 10.8.0.3")).isEqualTo("203.0.113.9");
		assertThat(ip(2, "1.2.3.4, 203.0.113.9, 10.8.0.3")).isEqualTo("203.0.113.9");
	}

	@Test
	void repeatedHeadersCountAsOneList() {
		assertThat(ip(1, "1.2.3.4", "203.0.113.9")).isEqualTo("203.0.113.9");
		assertThat(ip(2, "1.2.3.4", "203.0.113.9, 10.8.0.3")).isEqualTo("203.0.113.9");
	}

	@Test
	void withFewerEntriesThanProxiesAllOfThemAreTrustedAndTheFirstWins() {
		assertThat(ip(2, "203.0.113.9")).isEqualTo("203.0.113.9");
	}

	@Test
	void anEmptyOrOversizedEntryIsNotAnAddress() {
		assertThat(ip(1)).isNull();
		assertThat(ip(1, "  ")).isNull();
		assertThat(ip(1, "203.0.113.9, ")).isNull();
		assertThat(ip(1, "x".repeat(65))).isNull();
		// La basura a la izquierda no estorba.
		assertThat(ip(1, "x".repeat(500) + ",, 203.0.113.9")).isEqualTo("203.0.113.9");
	}

	@Test
	void withoutAUsableHeaderItIsTheCaller() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr("198.51.100.7");
		assertThat(ClientAddress.of(request, 1)).isEqualTo("198.51.100.7");
		request.addHeader("X-Forwarded-For", "1.2.3.4, 203.0.113.9");
		assertThat(ClientAddress.of(request, 1)).isEqualTo("203.0.113.9");
	}

}
