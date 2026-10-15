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

	/** Segundos tras los que conviene volver a intentar (cabecera Retry-After); 0 si no aplica. */
	private final long retryAfterSeconds;

	public ApiException(HttpStatus status, String errorCode, String detail) {
		this(status, errorCode, detail, List.of());
	}

	public ApiException(HttpStatus status, String errorCode, String detail, List<FieldError> errors) {
		this(status, errorCode, detail, errors, 0);
	}

	private ApiException(HttpStatus status, String errorCode, String detail, List<FieldError> errors,
			long retryAfterSeconds) {
		super(detail);
		this.status = status;
		this.errorCode = errorCode;
		this.errors = List.copyOf(errors);
		this.retryAfterSeconds = retryAfterSeconds;
	}

	/** 429 que dice cuándo se puede volver a intentar. */
	public static ApiException tooManyRequests(String errorCode, String detail, long retryAfterSeconds) {
		return new ApiException(HttpStatus.TOO_MANY_REQUESTS, errorCode, detail, List.of(),
				Math.max(1, retryAfterSeconds));
	}

	public long retryAfterSeconds() {
		return retryAfterSeconds;
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
