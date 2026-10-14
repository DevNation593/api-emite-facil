package com.example.einvoice.sequence;

/** Clave de acceso de 49 dígitos con dígito verificador módulo 11 (docs/06-access-key-algorithm.md). */
public final class AccessKey {

	private AccessKey() {
	}

	public static String build(String base48) {
		if (base48 == null || !base48.matches("[0-9]{48}")) {
			throw new IllegalArgumentException("Se requieren exactamente 48 dígitos");
		}
		int sum = 0;
		int factor = 2;
		for (int i = base48.length() - 1; i >= 0; i--) {
			sum += (base48.charAt(i) - '0') * factor;
			factor = factor == 7 ? 2 : factor + 1;
		}
		int dv = 11 - (sum % 11);
		if (dv == 11) {
			dv = 0;
		}
		else if (dv == 10) {
			dv = 1;
		}
		return base48 + dv;
	}

	public static boolean valid(String key) {
		return key != null && key.matches("[0-9]{49}") && build(key.substring(0, 48)).equals(key);
	}

}
