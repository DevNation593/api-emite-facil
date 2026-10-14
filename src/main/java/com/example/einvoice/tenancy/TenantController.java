package com.example.einvoice.tenancy;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.einvoice.identity.UserIdentity;
import com.example.einvoice.web.TextRules;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/tenants")
public class TenantController {

	private final TenantService service;

	public TenantController(TenantService service) {
		this.service = service;
	}

	record CreateTenantRequest(
			@NotBlank @Size(max = 200) @Pattern(regexp = TextRules.NO_CONTROL_CHARS) String name) {
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	TenantView create(UserIdentity user, @Valid @RequestBody CreateTenantRequest request) {
		return service.create(user, request.name());
	}

	@GetMapping
	List<TenantView> list(UserIdentity user) {
		return service.listFor(user);
	}

	@GetMapping("/current")
	TenantView current(TenantAccess access) {
		return service.current(access);
	}

}
