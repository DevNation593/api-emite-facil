package com.example.einvoice.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.example.einvoice.sequence.AccessKey;
import com.example.einvoice.support.AbstractIntegrationTest;
import com.example.einvoice.support.BillingScenario;

/**
 * Cuatro facturadores emiten a la vez sobre el mismo punto de emisión: ningún secuencial ni clave de
 * acceso se repite y no queda ningún hueco (docs/12-testing-certification.md).
 */
class SequenceConcurrencyIT extends AbstractIntegrationTest {

	static final int WORKERS = 4;

	static final int INVOICES_PER_WORKER = 25;

	@Test
	void concurrentInvoicesGetConsecutiveUniqueSequences() throws Exception {
		BillingScenario s = newBillingScenario();
		String body = s.invoiceJson("1", "11.50");
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
		List<UUID> issued = new ArrayList<>();
		try {
			List<Future<List<UUID>>> futures = new ArrayList<>();
			for (int w = 0; w < WORKERS; w++) {
				Callable<List<UUID>> worker = () -> {
					start.await();
					List<UUID> ids = new ArrayList<>();
					for (int i = 0; i < INVOICES_PER_WORKER; i++) {
						ids.add(issueInvoice(s.owner(), body));
					}
					return ids;
				};
				futures.add(pool.submit(worker));
			}
			start.countDown();
			for (Future<List<UUID>> future : futures) {
				issued.addAll(future.get());
			}
		}
		finally {
			pool.shutdownNow();
		}

		int total = WORKERS * INVOICES_PER_WORKER;
		assertThat(issued).hasSize(total).doesNotHaveDuplicates();
		assertThat(jdbc.queryForList("SELECT sequence FROM fiscal_documents WHERE tenant_id = ? ORDER BY sequence",
				Integer.class, s.tenant()))
			.containsExactlyElementsOf(IntStream.rangeClosed(1, total).boxed().toList());
		List<String> accessKeys = jdbc.queryForList("SELECT access_key FROM fiscal_documents WHERE tenant_id = ?",
				String.class, s.tenant());
		assertThat(accessKeys).hasSize(total).doesNotHaveDuplicates().allMatch(AccessKey::valid);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE tenant_id = ?", Integer.class,
				s.tenant()))
			.isEqualTo(total);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM state_events WHERE tenant_id = ?", Integer.class,
				s.tenant()))
			.isEqualTo(total);
		assertThat(jdbc.queryForObject("SELECT next_value FROM document_sequences WHERE emission_point_id = ?",
				Long.class, s.point()))
			.isEqualTo(total + 1L);
	}

}
