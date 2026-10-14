package com.example.einvoice.tenancy;

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

import com.example.einvoice.identity.UserIdentity;
import com.example.einvoice.tenancy.InvitationService.Created;
import com.example.einvoice.tenancy.InvitationService.Pending;
import com.example.einvoice.tenancy.InvitationService.Preview;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/invitations")
public class InvitationController {

	private static final String LOCAL = "[^\\s@,;:<>()\\[\\]\\\\\"]";

	private static final String LABEL = "[^\\s@.,;:<>()\\[\\]\\\\\"]";

	/**
	 * Una sola dirección, sin nombre ni listas. El tamaño se comprueba dentro de la propia expresión:
	 * las restricciones se evalúan todas aunque otra ya falle, y sin ese tope un texto enorme costaría
	 * más cuanto más largo fuera.
	 */
	static final String EMAIL = "^(?=.{3,254}$)" + LOCAL + "+@(?:" + LABEL + "+\\.)+" + LABEL + "+$";

	private final InvitationService service;

	public InvitationController(InvitationService service) {
		this.service = service;
	}

	record CreateInvitationRequest(@NotNull @Size(max = 254) @Pattern(regexp = EMAIL) String email, @NotNull Role role) {
	}

	record TokenRequest(@NotBlank @Size(max = 200) String token) {
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	Created create(TenantAccess access, @Valid @RequestBody CreateInvitationRequest request) {
		return service.create(access, request.email(), request.role());
	}

	@GetMapping
	List<Pending> pending(TenantAccess access) {
		return service.pending(access);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void revoke(TenantAccess access, @PathVariable UUID id) {
		service.revoke(access, id);
	}

	/** Con sesión pero sin empresa: quien acepta todavía no pertenece a ella. */
	@PostMapping("/preview")
	Preview preview(UserIdentity user, @Valid @RequestBody TokenRequest request) {
		return service.preview(user, request.token());
	}

	@PostMapping("/accept")
	TenantView accept(UserIdentity user, @Valid @RequestBody TokenRequest request) {
		return service.accept(user, request.token());
	}

}
