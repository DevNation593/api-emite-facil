package com.example.einvoice.sri;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Resultado de una consulta de autorización al SRI. NOT_FOUND: el SRI no devolvió ninguna
 * autorización para la clave. PROCESSING: hay una entrada que no es ni autorizada ni rechazada.
 * FAILED: no se obtuvo una respuesta válida.
 */
public record AuthorizationResult(Outcome outcome, String sriStatus, String consultedAccessKey,
		String authorizationNumber, OffsetDateTime authorizedAt, String environmentLabel, List<SriMessage> messages,
		Integer httpStatus, String errorDetail, byte[] rawResponse) {

	public enum Outcome {

		AUTHORIZED, NOT_AUTHORIZED, PROCESSING, NOT_FOUND, FAILED

	}

	public static AuthorizationResult failed(Integer httpStatus, String errorDetail, byte[] rawResponse) {
		return new AuthorizationResult(Outcome.FAILED, null, null, null, null, null, List.of(), httpStatus,
				errorDetail, rawResponse);
	}

	AuthorizationResult withTransport(Integer status, byte[] raw) {
		return new AuthorizationResult(outcome, sriStatus, consultedAccessKey, authorizationNumber, authorizedAt,
				environmentLabel, messages, status, errorDetail, raw);
	}

}
