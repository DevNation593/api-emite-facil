package com.example.einvoice.signature;

import java.util.List;

public record SignatureVerification(boolean valid, List<String> errors) {

	public SignatureVerification {
		errors = List.copyOf(errors);
	}

	static SignatureVerification of(List<String> errors) {
		return new SignatureVerification(errors.isEmpty(), errors);
	}

}
