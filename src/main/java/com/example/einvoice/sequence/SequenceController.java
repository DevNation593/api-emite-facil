package com.example.einvoice.sequence;

import java.util.UUID;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.einvoice.sequence.SequenceService.SequenceView;
import com.example.einvoice.tenancy.TenantAccess;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Permite continuar la numeración de un emisor que ya facturaba con otro sistema. */
@RestController
@RequestMapping("/api/v1/emission-points/{id}/sequence")
public class SequenceController {

	private final SequenceService service;

	public SequenceController(SequenceService service) {
		this.service = service;
	}

	// Por ahora solo se emiten facturas (01).
	record SetSequenceRequest(@NotNull @Pattern(regexp = "[12]") String environment,
			@NotNull @Pattern(regexp = "01") String documentType,
			@NotNull @Min(1) @Max(999_999_999) Integer nextValue) {
	}

	@PutMapping
	SequenceView set(TenantAccess access, @PathVariable UUID id, @Valid @RequestBody SetSequenceRequest request) {
		return service.set(access, id, request.environment(), request.documentType(), request.nextValue());
	}

}
