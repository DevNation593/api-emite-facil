package com.example.einvoice.delivery;

/** Envío de un correo de entrega. Nunca lanza: un fallo es un resultado. */
public interface DeliveryMailer {

	/**
	 * @param sent si el servidor aceptó el mensaje
	 * @param permanent si el fallo es definitivo (reintentar no lo arreglará)
	 * @param error descripción breve del fallo, sin el contenido del mensaje
	 */
	record Result(boolean sent, String messageId, boolean permanent, String error) {

		public static Result sent(String messageId) {
			return new Result(true, messageId, false, null);
		}

		public static Result failed(boolean permanent, String error) {
			return new Result(false, null, permanent, error);
		}

	}

	Result send(DeliveryEmail email);

}
