package com.example.einvoice.sequence;

import java.security.SecureRandom;
import java.util.Locale;

import org.springframework.stereotype.Component;

/** Código numérico de 8 dígitos de la clave de acceso; se genera una vez y se persiste. */
@Component
public class NumericCodeGenerator {

	private final SecureRandom random = new SecureRandom();

	public String next() {
		return String.format(Locale.ROOT, "%08d", random.nextInt(100_000_000));
	}

}
