package com.example.einvoice.webhook;

/** La dirección de un webhook no se admite. El mensaje dice por qué, sin repetir la dirección. */
public class WebhookUrlException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final boolean temporary;

	public WebhookUrlException(String reason) {
		this(reason, false);
	}

	public WebhookUrlException(String reason, boolean temporary) {
		super(reason);
		this.temporary = temporary;
	}

	/** Si el motivo puede pasar solo (el nombre no resolvió ahora), a diferencia de una dirección que no se admite. */
	public boolean isTemporary() {
		return temporary;
	}

}
