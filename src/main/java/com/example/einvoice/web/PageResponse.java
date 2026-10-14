package com.example.einvoice.web;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

/** Página de resultados de un listado. */
public record PageResponse<T>(List<T> items, int page, int size, long total) {

	public static final int MAX_SIZE = 100;

	/** Más allá de esta página no hay nada que mostrar y el desplazamiento desborda. */
	public static final int MAX_PAGE = 100_000;

	public static <S, T> PageResponse<T> of(Page<S> page, Function<S, T> mapper) {
		return new PageResponse<>(page.getContent().stream().map(mapper).toList(), page.getNumber(), page.getSize(),
				page.getTotalElements());
	}

	/** Valida los parámetros de paginación recibidos por la API. */
	public static Pageable request(int page, int size) {
		if (page < 0 || page > MAX_PAGE || size < 1 || size > MAX_SIZE) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
					"page debe estar entre 0 y " + MAX_PAGE + " y size entre 1 y " + MAX_SIZE);
		}
		return PageRequest.of(page, size);
	}

}
