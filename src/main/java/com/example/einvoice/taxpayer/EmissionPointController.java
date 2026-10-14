package com.example.einvoice.taxpayer;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.einvoice.tenancy.TenantAccess;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@RestController
@RequestMapping("/api/v1/emission-points")
public class EmissionPointController {

	private final EstablishmentService service;

	public EmissionPointController(EstablishmentService service) {
		this.service = service;
	}

	record CreateEmissionPointRequest(@NotNull UUID establishmentId,
			@NotNull @Pattern(regexp = "[0-9]{3}") String code) {
	}

	record EmissionPointResponse(UUID id, UUID establishmentId, String code, boolean enabled) {

		static EmissionPointResponse of(EmissionPoint point) {
			return new EmissionPointResponse(point.getId(), point.getEstablishmentId(), point.getCode(),
					point.isEnabled());
		}

	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	EmissionPointResponse create(TenantAccess access, @Valid @RequestBody CreateEmissionPointRequest request) {
		return EmissionPointResponse
			.of(service.createEmissionPoint(access, request.establishmentId(), request.code()));
	}

	@GetMapping
	List<EmissionPointResponse> list(TenantAccess access, @RequestParam UUID establishmentId) {
		return service.listEmissionPoints(access, establishmentId).stream().map(EmissionPointResponse::of).toList();
	}

}
