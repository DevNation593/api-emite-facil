package com.example.einvoice.sri;

import java.util.List;

/**
 * Resultado de una llamada a la recepción del SRI. FAILED significa que no se obtuvo una respuesta
 * válida: no dice si el SRI recibió o no el comprobante.
 */
public record ReceptionResult(Outcome outcome, String sriStatus, List<SriMessage> messages, Integer httpStatus,
		String errorDetail, byte[] rawResponse) {

	public enum Outcome {

		RECEIVED, RETURNED, FAILED

	}

	public static ReceptionResult failed(Integer httpStatus, String errorDetail, byte[] rawResponse) {
		return new ReceptionResult(Outcome.FAILED, null, List.of(), httpStatus, errorDetail, rawResponse);
	}

	public boolean hasMessage(String identifier) {
		return messages.stream().anyMatch(message -> identifier.equals(message.identifier()));
	}

	ReceptionResult withTransport(Integer status, byte[] raw) {
		return new ReceptionResult(outcome, sriStatus, messages, status, errorDetail, raw);
	}

}
