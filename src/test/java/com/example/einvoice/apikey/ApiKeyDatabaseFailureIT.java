package com.example.einvoice.apikey;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.CannotCreateTransactionException;

import com.example.einvoice.support.AbstractIntegrationTest;
import com.jayway.jsonpath.JsonPath;

/**
 * Un fallo de la base al comprobar la key no se confunde con una key inválida: el sistema externo
 * recibe 503 y sabe que puede reintentar. Anotar el último uso no es imprescindible. Va en su propia
 * clase porque el servicio espiado obliga a un contexto de Spring aparte.
 */
class ApiKeyDatabaseFailureIT extends AbstractIntegrationTest {

	@MockitoSpyBean
	ApiKeyService keys;

	String key;

	@BeforeEach
	void aCompanyWithAKey() throws Exception {
		String owner = newUserToken();
		createTenant(owner);
		String response = mvc.perform(json(post("/api/v1/api-keys"), owner, "{\"name\":\"ERP Contable\"}"))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		key = JsonPath.read(response, "$.key");
	}

	@Test
	void databaseFailureWhileCheckingTheKeyIsNotUnauthenticated() throws Exception {
		List<RuntimeException> failures = List.of(new DataAccessResourceFailureException("base caída"),
				new CannotCreateTransactionException("sin conexión"));
		for (RuntimeException failure : failures) {
			doThrow(failure).when(keys).authenticate(anyString());
			mvc.perform(get("/api/v1/tenants/current").header(ApiKeyAuthenticationFilter.HEADER, key))
				.andExpect(status().isServiceUnavailable())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.errorCode").value("INTERNAL_ERROR"))
				.andExpect(jsonPath("$.correlationId").isNotEmpty());
		}
	}

	@Test
	void failingToRecordLastUseDoesNotFailTheRequest() throws Exception {
		doThrow(new DataAccessResourceFailureException("base caída")).when(keys).touch(any(UUID.class));
		mvc.perform(get("/api/v1/tenants/current").header(ApiKeyAuthenticationFilter.HEADER, key))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.role").value("BILLER"));
	}

}
