package com.example.einvoice.sequence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.einvoice.support.AbstractIntegrationTest;
import com.example.einvoice.support.TestData;
import com.example.einvoice.web.ApiException;

class SequenceServiceIT extends AbstractIntegrationTest {

	static final LocalDate DAY = LocalDate.of(2026, 10, 10);

	@Autowired
	SequenceService sequences;

	@Autowired
	PlatformTransactionManager transactionManager;

	TransactionTemplate tx;

	UUID tenant;

	UUID establishment;

	UUID point;

	@BeforeEach
	void emissionPoint() {
		tx = new TransactionTemplate(transactionManager);
		tenant = TestData.tenant(jdbc);
		establishment = TestData.establishment(jdbc, tenant, TestData.taxpayer(jdbc, tenant, TestData.ruc()), "001");
		point = TestData.emissionPoint(jdbc, tenant, establishment, "001");
	}

	int reserve(UUID emissionPoint, String environment, LocalDate date) {
		return tx.execute(status -> sequences.reserve(tenant, emissionPoint, environment, "01", date));
	}

	@Test
	void startsAtOneAndIncrements() {
		assertThat(List.of(reserve(point, "1", DAY), reserve(point, "1", DAY), reserve(point, "1", DAY)))
			.containsExactly(1, 2, 3);
	}

	@Test
	void countersAreIndependentPerEnvironmentAndPoint() {
		UUID otherPoint = TestData.emissionPoint(jdbc, tenant, establishment, "002");
		assertThat(reserve(point, "1", DAY)).isEqualTo(1);
		assertThat(reserve(point, "1", DAY)).isEqualTo(2);
		assertThat(reserve(point, "2", DAY)).isEqualTo(1);
		assertThat(reserve(otherPoint, "1", DAY)).isEqualTo(1);
	}

	@Test
	void rollbackReleasesTheNumber() {
		Integer reserved = tx.execute(status -> {
			int value = sequences.reserve(tenant, point, "1", "01", DAY);
			status.setRollbackOnly();
			return value;
		});
		assertThat(reserved).isEqualTo(1);
		assertThat(reserve(point, "1", DAY)).isEqualTo(1);
	}

	@Test
	void concurrentReservationsNeverRepeat() throws Exception {
		int threads = 8;
		int perThread = 25;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		try {
			List<Future<List<Integer>>> futures = new ArrayList<>();
			for (int t = 0; t < threads; t++) {
				Callable<List<Integer>> worker = () -> IntStream.range(0, perThread)
					.mapToObj(i -> reserve(point, "1", DAY))
					.toList();
				futures.add(pool.submit(worker));
			}
			List<Integer> all = new ArrayList<>();
			for (Future<List<Integer>> future : futures) {
				all.addAll(future.get());
			}
			assertThat(all).hasSize(threads * perThread)
				.containsExactlyInAnyOrderElementsOf(IntStream.rangeClosed(1, threads * perThread).boxed().toList());
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(jdbc.queryForObject("SELECT next_value FROM document_sequences WHERE emission_point_id = ?",
				Long.class, point))
			.isEqualTo(201L);
	}

	@Test
	void rejectsEarlierIssueDate() {
		assertThat(reserve(point, "1", DAY)).isEqualTo(1);
		assertThatThrownBy(() -> reserve(point, "1", DAY.minusDays(1)))
			.isInstanceOfSatisfying(ApiException.class,
					ex -> assertThat(ex.errorCode()).isEqualTo("INVALID_ISSUE_DATE"));
		assertThat(reserve(point, "1", DAY)).isEqualTo(2);
		assertThat(reserve(point, "1", DAY.plusDays(1))).isEqualTo(3);
	}

	@Test
	void exhaustedSequenceIsRejected() {
		jdbc.update("INSERT INTO document_sequences (tenant_id, emission_point_id, environment, document_type,"
				+ " next_value) VALUES (?, ?, '1', '01', 999999999)", tenant, point);
		assertThat(reserve(point, "1", DAY)).isEqualTo(999_999_999);
		assertThatThrownBy(() -> reserve(point, "1", DAY)).isInstanceOfSatisfying(ApiException.class,
				ex -> assertThat(ex.errorCode()).isEqualTo("SEQUENCE_EXHAUSTED"));
	}

	@Test
	void requiresAnOpenTransaction() {
		assertThatThrownBy(() -> sequences.reserve(tenant, point, "1", "01", DAY))
			.isInstanceOf(IllegalTransactionStateException.class);
	}

}
