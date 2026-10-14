package com.example.einvoice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import com.example.einvoice.support.AbstractIntegrationTest;
import com.example.einvoice.support.TestTokens;

@ExtendWith(OutputCaptureExtension.class)
class SecurityIT extends AbstractIntegrationTest {

	static final String CORRELATION = "X-Correlation-ID";

	@Test
	void healthIsPublic() throws Exception {
		mvc.perform(get("/actuator/health")).andExpect(status().isOk());
	}

	@Test
	void apiRequiresToken() throws Exception {
		mvc.perform(get("/api/v1/tenants"))
			.andExpect(status().isUnauthorized())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"))
			.andExpect(jsonPath("$.status").value(401))
			.andExpect(jsonPath("$.correlationId").isNotEmpty());
	}

	@Test
	void rejectsExpiredToken() throws Exception {
		mvc.perform(get("/api/v1/tenants").header(HttpHeaders.AUTHORIZATION, TestTokens.expired("u-expired")))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"));
	}

	@Test
	void rejectsTokenFromAnotherIssuer() throws Exception {
		mvc.perform(get("/api/v1/tenants").header(HttpHeaders.AUTHORIZATION, TestTokens.wrongIssuer("u-issuer")))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void rejectsTokenSignedWithAnotherKey() throws Exception {
		mvc.perform(get("/api/v1/tenants").header(HttpHeaders.AUTHORIZATION, TestTokens.wrongKey("u-key")))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void rejectsTokenIssuedForAnotherApplication() throws Exception {
		mvc.perform(get("/api/v1/tenants").header(HttpHeaders.AUTHORIZATION, TestTokens.wrongAudience("u-aud")))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.errorCode").value("UNAUTHENTICATED"));
		mvc.perform(get("/api/v1/tenants").header(HttpHeaders.AUTHORIZATION, TestTokens.withoutAudience("u-noaud")))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void rejectsTokenWithoutExpiry() throws Exception {
		mvc.perform(get("/api/v1/tenants").header(HttpHeaders.AUTHORIZATION, TestTokens.withoutExpiry("u-noexp")))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void rejectsTokenWithBlankSubject() throws Exception {
		mvc.perform(get("/api/v1/tenants").header(HttpHeaders.AUTHORIZATION, TestTokens.bearer("", null)))
			.andExpect(status().isUnauthorized());
		mvc.perform(get("/api/v1/tenants").header(HttpHeaders.AUTHORIZATION, TestTokens.bearer("   ", null)))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void unexpectedErrorIsLoggedWithItsCorrelationId(CapturedOutput output) throws Exception {
		mvc.perform(get("/test-only/fail").header(HttpHeaders.AUTHORIZATION, TestTokens.bearer("u-500"))
			.header(CORRELATION, "corr-500-test"))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
			.andExpect(jsonPath("$.correlationId").value("corr-500-test"))
			.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("provocado"))));
		assertThat(output.getOut().lines().filter(line -> line.contains("Error no controlado")))
			.isNotEmpty()
			.allMatch(line -> line.contains("[corr-500-test]"));
	}

	@Test
	void unknownRouteIsProblemNotFound() throws Exception {
		mvc.perform(get("/api/v1/no-existe").header(HttpHeaders.AUTHORIZATION, TestTokens.bearer("u-404")))
			.andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
	}

	@Test
	void echoesCorrelationId() throws Exception {
		mvc.perform(get("/api/v1/tenants").header(CORRELATION, "abc-123"))
			.andExpect(status().isUnauthorized())
			.andExpect(header().string(CORRELATION, "abc-123"))
			.andExpect(jsonPath("$.correlationId").value("abc-123"));
	}

	@Test
	void replacesUnsafeCorrelationId() throws Exception {
		for (String unsafe : new String[] { "con espacios y\ttab", "x".repeat(65), "<script>" }) {
			String returned = mvc.perform(get("/actuator/health").header(CORRELATION, unsafe))
				.andReturn()
				.getResponse()
				.getHeader(CORRELATION);
			assertThat(returned).isNotEqualTo(unsafe).matches("[A-Za-z0-9._-]{1,64}");
		}
	}

}
