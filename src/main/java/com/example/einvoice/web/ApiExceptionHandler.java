package com.example.einvoice.web;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Convierte toda excepción en application/problem+json con errorCode y correlationId. El detalle es
 * siempre un texto fijo o de validación: nunca el mensaje interno de la excepción.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(ApiException.class)
	ResponseEntity<Object> handleApi(ApiException ex, WebRequest request) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(ex.status(), ex.getMessage());
		problem.setProperty("errorCode", ex.errorCode());
		if (!ex.errors().isEmpty()) {
			problem.setProperty("errors",
					ex.errors().stream().map(error -> Map.of("field", error.field(), "message", error.message())).toList());
		}
		HttpHeaders headers = new HttpHeaders();
		if (ex.retryAfterSeconds() > 0) {
			headers.set(HttpHeaders.RETRY_AFTER, Long.toString(ex.retryAfterSeconds()));
		}
		return handleExceptionInternal(ex, problem, headers, ex.status(), request);
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
		log.error("Error no controlado", ex);
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
				"Error interno; use el correlationId para reportarlo");
		problem.setProperty("errorCode", "INTERNAL_ERROR");
		return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
				"La petición no cumple las reglas de validación");
		problem.setProperty("errorCode", "VALIDATION_FAILED");
		List<Map<String, String>> errors = ex.getBindingResult()
			.getFieldErrors()
			.stream()
			.map(error -> Map.of("field", error.getField(), "message", String.valueOf(error.getDefaultMessage())))
			.toList();
		problem.setProperty("errors", errors);
		return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
	}

	@Override
	protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
				"El cuerpo de la petición no es JSON válido");
		problem.setProperty("errorCode", "MALFORMED_REQUEST");
		return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
	}

	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest request) {
		ProblemDetail problem = body instanceof ProblemDetail detail ? detail : ProblemDetail.forStatus(statusCode);
		if (problem.getProperties() == null || !problem.getProperties().containsKey("errorCode")) {
			problem.setProperty("errorCode", defaultErrorCode(statusCode));
			problem.setDetail(defaultDetail(statusCode));
		}
		problem.setProperty("correlationId",
				request.getAttribute(CorrelationIdFilter.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST));
		return super.handleExceptionInternal(ex, problem, headers, statusCode, request);
	}

	private static String defaultErrorCode(HttpStatusCode status) {
		return switch (status.value()) {
			case 400 -> "MALFORMED_REQUEST";
			case 404 -> "NOT_FOUND";
			case 405 -> "METHOD_NOT_ALLOWED";
			case 415 -> "UNSUPPORTED_MEDIA_TYPE";
			default -> status.is4xxClientError() ? "REQUEST_REJECTED" : "INTERNAL_ERROR";
		};
	}

	private static String defaultDetail(HttpStatusCode status) {
		return switch (status.value()) {
			case 400 -> "La petición está mal formada";
			case 404 -> "Recurso no encontrado";
			default -> status.is4xxClientError() ? "La petición no se pudo procesar"
					: "Error interno; use el correlationId para reportarlo";
		};
	}

}
