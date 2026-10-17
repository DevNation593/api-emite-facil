package com.example.einvoice.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;

/**
 * Para las pocas pruebas que necesitan el servidor de verdad, en un puerto aleatorio, y no MockMvc:
 * lo que depende de cómo el contenedor de servlets despacha errores o resuelve rutas. Las
 * propiedades son las mismas de {@link AbstractIntegrationTest}; si cambian allí, cambian aquí.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = { "EINVOICE_JWT_SECRET=" + TestTokens.SECRET, "EINVOICE_WEB_URL=http://web.test", "einvoice.provider.ruc=1790099999001",
				"EINVOICE_MASTER_KEY=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
				"management.endpoint.health.show-components=always", "einvoice.worker.enabled=false",
				"einvoice.sri.enabled-environments=1", "einvoice.rate-limit.requests-per-minute=1000000",
				"einvoice.rate-limit.writes-per-minute=1000000" })
public @interface RealHttpTest {
}
