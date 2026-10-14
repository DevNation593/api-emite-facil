package com.example.einvoice.catalog;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Catálogos tributarios del SRI. Son datos de la base, sembrados desde la ficha técnica: el código
 * de negocio pregunta aquí y nunca compara contra códigos o porcentajes escritos a mano.
 */
@Repository
public class CatalogRepository {

	/** Código del IVA en la tabla 16 de la ficha. Identifica el impuesto, no una tarifa. */
	public static final String IVA_TAX_CODE = "2";

	public record CodeName(String code, String name) {
	}

	/** idNumberPattern es una expresión regular de Java que la identificación debe cumplir completa. */
	public record IdentificationType(String code, String name, String idNumberPattern, boolean finalConsumer) {
	}

	public record PaymentMethod(String code, String name, LocalDate validFrom, LocalDate validTo) {
	}

	public record TaxRate(String taxCode, String rateCode, String description, BigDecimal rate) {
	}

	private final JdbcClient jdbc;

	public CatalogRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public List<CodeName> identificationTypes() {
		return jdbc.sql("SELECT code, name FROM identification_types ORDER BY code").query(CodeName.class).list();
	}

	public boolean identificationTypeExists(String code) {
		return identificationType(code).isPresent();
	}

	public Optional<IdentificationType> identificationType(String code) {
		return jdbc.sql("SELECT code, name, id_number_pattern, final_consumer FROM identification_types WHERE code = :code")
			.param("code", code)
			.query(IdentificationType.class)
			.optional();
	}

	/** Indica si la identificación es la reservada para ventas a consumidor final. */
	public boolean isFinalConsumerNumber(String idNumber) {
		return jdbc.sql("SELECT id_number_pattern FROM identification_types WHERE final_consumer")
			.query(String.class)
			.list()
			.stream()
			.anyMatch(idNumber::matches);
	}

	public List<PaymentMethod> paymentMethods() {
		return jdbc.sql("SELECT code, name, valid_from, valid_to FROM payment_methods ORDER BY code")
			.query(PaymentMethod.class)
			.list();
	}

	public boolean paymentMethodValidOn(String code, LocalDate date) {
		return jdbc.sql("""
				SELECT count(*) FROM payment_methods
				WHERE code = :code AND valid_from <= :date AND (valid_to IS NULL OR valid_to >= :date)
				""").param("code", code).param("date", date).query(Long.class).single() > 0;
	}

	public List<TaxRate> taxRates() {
		return jdbc.sql("""
				SELECT tax_code, rate_code, description, rate FROM tax_rates
				ORDER BY tax_code, CAST(rate_code AS integer)
				""").query(TaxRate.class).list();
	}

	public Optional<BigDecimal> ivaRate(String rateCode) {
		return jdbc.sql("SELECT rate FROM tax_rates WHERE tax_code = :taxCode AND rate_code = :rateCode")
			.param("taxCode", IVA_TAX_CODE)
			.param("rateCode", rateCode)
			.query(BigDecimal.class)
			.optional();
	}

}
