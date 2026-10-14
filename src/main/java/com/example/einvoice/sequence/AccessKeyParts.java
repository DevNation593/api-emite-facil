package com.example.einvoice.sequence;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Componentes de la clave de acceso en el orden de la ficha técnica. Todo salvo el secuencial se
 * conserva como texto para no perder ceros iniciales.
 */
public record AccessKeyParts(LocalDate issueDate, String documentType, String ruc, String environment,
		String establishment, String emissionPoint, int sequence, String numericCode, String emissionType) {

	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("ddMMyyyy");

	public AccessKeyParts {
		if (issueDate == null) {
			throw new IllegalArgumentException("La fecha de emisión es obligatoria");
		}
		require(documentType, "[0-9]{2}", "tipo de comprobante");
		require(ruc, "[0-9]{13}", "RUC");
		require(environment, "[12]", "ambiente");
		require(establishment, "[0-9]{3}", "establecimiento");
		require(emissionPoint, "[0-9]{3}", "punto de emisión");
		if (sequence < 1 || sequence > 999_999_999) {
			throw new IllegalArgumentException("Secuencial fuera de rango: " + sequence);
		}
		require(numericCode, "[0-9]{8}", "código numérico");
		require(emissionType, "[0-9]", "tipo de emisión");
	}

	public String toBase48() {
		return issueDate.format(DATE) + documentType + ruc + environment + establishment + emissionPoint
				+ String.format(Locale.ROOT, "%09d", sequence) + numericCode + emissionType;
	}

	public String toAccessKey() {
		return AccessKey.build(toBase48());
	}

	private static void require(String value, String pattern, String name) {
		if (value == null || !value.matches(pattern)) {
			throw new IllegalArgumentException("Valor inválido para " + name + ": " + value);
		}
	}

}
