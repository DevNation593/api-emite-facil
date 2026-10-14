package com.example.einvoice.web;

import java.util.List;

import org.springframework.http.HttpStatus;

/** Error de negocio o de acceso que la API devuelve como application/problem+json (docs/09-error-handling.md). */
public class ApiException extends RuntimeException {

	private final HttpStatus status;

	/** Qué dato concreto motivó el rechazo, con el mismo formato que los errores de validación. */
	public record FieldError(String field, String message) {
	}

	private final String errorCode;

	private final List<FieldError> errors;

	public ApiException(HttpStatus status, String errorCode, String detail) {
		this(status, errorCode, detail, List.of());
	}

	public ApiException(HttpStatus status, String errorCode, String detail, List<FieldError> errors) {
		super(detail);
		this.status = status;
		this.errorCode = errorCode;
		this.errors = List.copyOf(errors);
	}

	public List<FieldError> errors() {
		return errors;
	}

	/** Mismo cuerpo para un recurso inexistente y para uno de otro tenant. */
	public static ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Recurso no encontrado");
	}

	public HttpStatus status() {
		return status;
	}

	public String errorCode() {
		return errorCode;
	}

}
