package com.example.einvoice.support;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Ruta solo de pruebas que falla de forma inesperada, para observar el tratamiento de un error 500. */
@RestController
public class FailingController {

	@GetMapping("/test-only/fail")
	String fail() {
		throw new IllegalStateException("fallo provocado por la prueba");
	}

}
