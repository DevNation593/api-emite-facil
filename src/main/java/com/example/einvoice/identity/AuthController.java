package com.example.einvoice.identity;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cuentas y sesiones. Todo es público salvo /me, /email-verification/resend y /password, que necesitan
 * un token de acceso. La API no está expuesta al navegador: la IP del usuario la pasa la web en
 * X-Forwarded-For.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

	/** Límite generoso: las reglas de la contraseña las aplica el servicio, con un mensaje claro. */
	private static final int MAX_PASSWORD = 1024;

	private static final int MAX_TOKEN = 200;

	private final AuthService service;

	public AuthController(AuthService service) {
		this.service = service;
	}

	record Credentials(@NotBlank @Email @Size(max = 254) String email, @NotNull @Size(max = MAX_PASSWORD) String password) {
	}

	record RefreshRequest(@NotBlank @Size(max = MAX_TOKEN) String refreshToken) {
	}

	record LinkRequest(@NotBlank @Size(max = MAX_TOKEN) String token) {
	}

	record EmailRequest(@NotBlank @Email @Size(max = 254) String email) {
	}

	record ResetRequest(@NotBlank @Size(max = MAX_TOKEN) String token,
			@NotNull @Size(max = MAX_PASSWORD) String newPassword) {
	}

	record ChangePasswordRequest(@NotNull @Size(max = MAX_PASSWORD) String currentPassword,
			@NotNull @Size(max = MAX_PASSWORD) String newPassword) {
	}

	record Me(UUID id, String email, boolean emailVerified) {
	}

	@PostMapping("/register")
	@ResponseStatus(HttpStatus.ACCEPTED)
	void register(@Valid @RequestBody Credentials request) {
		service.register(request.email(), request.password());
	}

	@PostMapping("/login")
	AuthService.Tokens login(@Valid @RequestBody Credentials request, HttpServletRequest http) {
		return service.login(request.email(), request.password(), clientIp(http));
	}

	@PostMapping("/refresh")
	AuthService.Tokens refresh(@Valid @RequestBody RefreshRequest request) {
		return service.refresh(request.refreshToken());
	}

	@PostMapping("/logout")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void logout(@Valid @RequestBody RefreshRequest request) {
		service.logout(request.refreshToken());
	}

	@PostMapping("/email-verification")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void verifyEmail(@Valid @RequestBody LinkRequest request) {
		service.verifyEmail(request.token());
	}

	@PostMapping("/email-verification/resend")
	@ResponseStatus(HttpStatus.ACCEPTED)
	void resendVerification(UserIdentity user) {
		service.resendVerification(user.userId());
	}

	@PostMapping("/password-reset/request")
	@ResponseStatus(HttpStatus.ACCEPTED)
	void requestPasswordReset(@Valid @RequestBody EmailRequest request, HttpServletRequest http) {
		service.requestPasswordReset(request.email(), clientIp(http));
	}

	@PostMapping("/password-reset")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void resetPassword(@Valid @RequestBody ResetRequest request) {
		service.resetPassword(request.token(), request.newPassword());
	}

	@PostMapping("/password")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void changePassword(UserIdentity user, @Valid @RequestBody ChangePasswordRequest request) {
		service.changePassword(user.userId(), user.sessionId(), request.currentPassword(), request.newPassword());
	}

	@GetMapping("/me")
	Me me(UserIdentity user) {
		return new Me(user.userId(), user.email(), user.emailVerified());
	}

	/** La primera dirección de X-Forwarded-For, que pone la web; sin ella, la del que llama. */
	static String clientIp(HttpServletRequest request) {
		String forwarded = request.getHeader("X-Forwarded-For");
		if (forwarded != null && !forwarded.isBlank()) {
			String first = forwarded.split(",", 2)[0].strip();
			if (!first.isEmpty() && first.length() <= 64) {
				return first;
			}
		}
		return request.getRemoteAddr();
	}

}
