package com.example.einvoice.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import com.example.einvoice.support.AbstractIntegrationTest;
import com.example.einvoice.support.BillingScenario;
import com.jayway.jsonpath.JsonPath;

class InvoiceIdempotencyIT extends AbstractIntegrationTest {

	BillingScenario s;

	@BeforeEach
	void scenario() throws Exception {
		s = newBillingScenario();
	}

	MvcResult send(String token, String key, String body) throws Exception {
		return mvc.perform(json(post("/api/v1/invoices"), token, body).header("Idempotency-Key", key)).andReturn();
	}

	String acceptedId(MvcResult result) throws Exception {
		assertThat(result.getResponse().getStatus()).isEqualTo(202);
		return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
	}

	long documentCount(UUID tenant) {
		return jdbc.queryForObject("SELECT count(*) FROM fiscal_documents WHERE tenant_id = ?", Long.class, tenant);
	}

	long nextValue() {
		return jdbc.queryForObject("SELECT next_value FROM document_sequences WHERE emission_point_id = ?",
				Long.class, s.point());
	}

	@Test
	void sameKeyAndBodyReturnsTheSameDocument() throws Exception {
		String body = s.invoiceJson("1", "11.50");
		String first = acceptedId(send(s.owner(), "retry-1", body));
		MvcResult second = send(s.owner(), "retry-1", body);

		assertThat(acceptedId(second)).isEqualTo(first);
		assertThat(JsonPath.<String>read(second.getResponse().getContentAsString(), "$.state")).isEqualTo("RESERVED");
		assertThat(documentCount(s.tenant())).isEqualTo(1);
		assertThat(nextValue()).isEqualTo(2);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE tenant_id = ?", Long.class, s.tenant()))
			.isEqualTo(1);
	}

	@Test
	void reorderedJsonIsTheSameRequest() throws Exception {
		String body = s.invoiceJson("1", "11.50");
		String reordered = "{ \"payments\": [ {\"amount\":\"11.50\", \"methodCode\":\"20\"} ],\n  \"lines\":[{\"quantity\":\"1\","
				+ "\"productId\":\"" + s.product() + "\"}], \"customerId\":\"" + s.customer()
				+ "\", \"emissionPointCode\":\"001\", \"establishmentCode\":\"001\", \"taxpayerId\":\"" + s.taxpayer()
				+ "\" }";
		String first = acceptedId(send(s.owner(), "retry-2", body));
		assertThat(acceptedId(send(s.owner(), "retry-2", reordered))).isEqualTo(first);
		assertThat(documentCount(s.tenant())).isEqualTo(1);
	}

	@Test
	void sameKeyWithDifferentBodyIsConflict() throws Exception {
		acceptedId(send(s.owner(), "retry-3", s.invoiceJson("1", "11.50")));
		mvc.perform(json(post("/api/v1/invoices"), s.owner(), s.invoiceJson("2", "23.00")).header("Idempotency-Key",
				"retry-3"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_REUSED"));
		assertThat(documentCount(s.tenant())).isEqualTo(1);
		assertThat(nextValue()).isEqualTo(2);
	}

	@Test
	void sameKeyInAnotherTenantIsIndependent() throws Exception {
		BillingScenario other = newBillingScenario();
		String first = acceptedId(send(s.owner(), "shared-key", s.invoiceJson("1", "11.50")));
		String second = acceptedId(send(other.owner(), "shared-key", other.invoiceJson("1", "11.50")));
		assertThat(second).isNotEqualTo(first);
		assertThat(documentCount(s.tenant())).isEqualTo(1);
		assertThat(documentCount(other.tenant())).isEqualTo(1);
	}

	@Test
	void sameKeyFromAnotherMemberOfTheTenantIsTheSameRequest() throws Exception {
		String body = s.invoiceJson("1", "11.50");
		String first = acceptedId(send(s.owner(), "retry-4", body));
		assertThat(acceptedId(send(memberToken(s.tenant(), "BILLER"), "retry-4", body))).isEqualTo(first);
		assertThat(send(memberToken(s.tenant(), "AUDITOR"), "retry-4", body).getResponse().getStatus())
			.isEqualTo(403);
	}

	@Test
	void failedRequestDoesNotBurnTheKey() throws Exception {
		assertThat(send(s.owner(), "retry-5", s.invoiceJson("1", "99.99")).getResponse().getStatus()).isEqualTo(422);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_records WHERE tenant_id = ?", Long.class,
				s.tenant()))
			.isZero();
		acceptedId(send(s.owner(), "retry-5", s.invoiceJson("1", "11.50")));
		assertThat(documentCount(s.tenant())).isEqualTo(1);
		assertThat(nextValue()).isEqualTo(2);
	}

	@Test
	void concurrentRequestsWithTheSameKeyCreateOneDocument() throws Exception {
		int threads = 8;
		int rounds = 10;
		String body = s.invoiceJson("1", "11.50");
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			for (int round = 0; round < rounds; round++) {
				String key = "race-" + round;
				CountDownLatch start = new CountDownLatch(1);
				List<Future<String>> futures = new ArrayList<>();
				for (int t = 0; t < threads; t++) {
					Callable<String> request = () -> {
						start.await();
						return acceptedId(send(s.owner(), key, body));
					};
					futures.add(pool.submit(request));
				}
				start.countDown();
				Set<String> ids = new HashSet<>();
				for (Future<String> future : futures) {
					ids.add(future.get());
				}
				assertThat(ids).as("ronda %s", round).hasSize(1);
			}
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(documentCount(s.tenant())).isEqualTo(rounds);
		assertThat(nextValue()).isEqualTo(rounds + 1L);
		assertThat(jdbc.queryForList(
				"SELECT sequence FROM fiscal_documents WHERE tenant_id = ? ORDER BY sequence", Integer.class,
				s.tenant()))
			.containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
	}

}
