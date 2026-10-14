package com.example.einvoice.xml;

import java.util.List;

public record ValidationResult(boolean valid, List<String> errors) {

	public ValidationResult {
		errors = List.copyOf(errors);
	}

	static ValidationResult of(List<String> errors) {
		return new ValidationResult(errors.isEmpty(), errors);
	}

}
