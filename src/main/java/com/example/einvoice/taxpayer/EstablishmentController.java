package com.example.einvoice.taxpayer;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.einvoice.tenancy.TenantAccess;
import com.example.einvoice.web.TextRules;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/taxpayers/{taxpayerId}/establishments")
public class EstablishmentController {

	private final EstablishmentService service;

	public EstablishmentController(EstablishmentService service) {
		this.service = service;
	}

	record CreateEstablishmentRequest(@NotNull @Pattern(regexp = "[0-9]{3}") String code,
			@NotBlank @Size(max = 300) @Pattern(regexp = TextRules.NO_CONTROL_CHARS) String address) {
	}

	record EstablishmentResponse(UUID id, UUID taxpayerId, String code, String address) {

		static EstablishmentResponse of(Establishment establishment) {
			return new EstablishmentResponse(establishment.getId(), establishment.getTaxpayerId(),
					establishment.getCode(), establishment.getAddress());
		}

	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	EstablishmentResponse create(TenantAccess access, @PathVariable UUID taxpayerId,
			@Valid @RequestBody CreateEstablishmentRequest request) {
		return EstablishmentResponse
			.of(service.createEstablishment(access, taxpayerId, request.code(), request.address()));
	}

	@GetMapping
	List<EstablishmentResponse> list(TenantAccess access, @PathVariable UUID taxpayerId) {
		return service.listEstablishments(access, taxpayerId).stream().map(EstablishmentResponse::of).toList();
	}

}
