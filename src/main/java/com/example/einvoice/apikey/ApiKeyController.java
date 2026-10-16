package com.example.einvoice.apikey;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Gestión de las API keys de la empresa: solo OWNER y ADMIN. */
@RestController
@RequestMapping("/api/v1/api-keys")
public class ApiKeyController {

	private final ApiKeyService keys;

	public ApiKeyController(ApiKeyService keys) {
		this.keys = keys;
	}

	record CreateApiKeyRequest(
			@NotBlank @Size(max = 80) @Pattern(regexp = TextRules.NO_CONTROL_CHARS) String name) {
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	CreatedApiKey create(TenantAccess access, @Valid @RequestBody CreateApiKeyRequest request) {
		return keys.create(access, request.name());
	}

	@GetMapping
	List<ApiKeyView> list(TenantAccess access) {
		return keys.list(access);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void revoke(TenantAccess access, @PathVariable UUID id) {
		keys.revoke(access, id);
	}

}
