package com.example.einvoice.identity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

import com.example.einvoice.web.ApiException;

/**
 * Contraseñas: qué se acepta y cómo se guardan. Solo se exige longitud (12 a 128 caracteres) y que no
 * sea el propio correo, sin reglas de composición (NIST SP 800-63B). Se guardan con Argon2id.
 */
@Component
public class Passwords {

	static final int MIN_LENGTH = 12;

	static final int MAX_LENGTH = 128;

	private final Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

	/** Hash de una contraseña que nadie tiene: un correo inexistente cuesta lo mismo que uno real. */
	private final String decoy = encoder.encode("contraseña-de-relleno-" + System.nanoTime());

	public String hash(String password) {
		return encoder.encode(password);
	}

	/** Compara siempre contra un hash, aunque la cuenta no exista o no tenga contraseña. */
	public boolean matches(String password, String hash) {
		boolean known = hash != null;
		boolean matches = encoder.matches(password == null ? "" : password, known ? hash : decoy);
		return known && matches;
	}

	/** Rechaza con 422 una contraseña que no cumple las reglas, con el campo que la trajo. */
	public void requireAcceptable(String field, String password, String email) {
		List<ApiException.FieldError> errors = new ArrayList<>();
		int length = password == null ? 0 : password.codePointCount(0, password.length());
		if (length < MIN_LENGTH || length > MAX_LENGTH) {
			errors.add(new ApiException.FieldError(field,
					"La contraseña debe tener entre " + MIN_LENGTH + " y " + MAX_LENGTH + " caracteres"));
		}
		else if (email != null && password.strip().toLowerCase(Locale.ROOT).equals(email.toLowerCase(Locale.ROOT))) {
			errors.add(new ApiException.FieldError(field, "La contraseña no puede ser el correo"));
		}
		if (!errors.isEmpty()) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "WEAK_PASSWORD", "La contraseña no es válida",
					errors);
		}
	}

}
