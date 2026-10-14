package com.example.einvoice.compliance;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.einvoice.compliance.ProductionActivation.Readiness;
import com.example.einvoice.tenancy.TenantAccess;

@RestController
@RequestMapping("/api/v1/taxpayers/{id}")
public class ProductionActivationController {

	private final ProductionActivation activation;

	public ProductionActivationController(ProductionActivation activation) {
		this.activation = activation;
	}

	record ActivationRequest(Boolean sriProductionAuthorized) {
	}

	@GetMapping("/production-readiness")
	Readiness readiness(TenantAccess access, @PathVariable UUID id) {
		return activation.readiness(access, id);
	}

	@PostMapping("/production-activation")
	Readiness activate(TenantAccess access, @PathVariable UUID id, @RequestBody ActivationRequest request) {
		return activation.activate(access, id, Boolean.TRUE.equals(request.sriProductionAuthorized()));
	}

}
