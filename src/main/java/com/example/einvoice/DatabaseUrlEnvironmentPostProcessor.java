package com.example.einvoice;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Toma la conexión a PostgreSQL de una sola variable, DATABASE_URL, con la forma
 * {@code postgresql://usuario:contraseña@host:puerto/base?parámetros} (o {@code postgres://}), y la reparte en la
 * URL JDBC, el usuario y la contraseña que espera Spring. El usuario y la contraseña van aparte, y no en la URL
 * JDBC, para que SPRING_FLYWAY_USER pueda seguir migrando con otro usuario. Los caracteres especiales del usuario
 * o la contraseña se escriben codificados ({@code %40} para la arroba).
 */
public class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor {

	static final String VARIABLE = "DATABASE_URL";

	private static final int DEFAULT_PORT = 5432;

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		String value = environment.getProperty(VARIABLE);
		if (value == null || value.isBlank()) {
			return;
		}
		environment.getPropertySources().addLast(new MapPropertySource(VARIABLE, toDatasourceProperties(value.trim())));
	}

	static Map<String, Object> toDatasourceProperties(String value) {
		URI uri;
		try {
			uri = new URI(value);
		}
		catch (URISyntaxException ex) {
			// El mensaje de URISyntaxException repite la URL entera, contraseña incluida.
			throw invalid("no es una URL válida");
		}
		String scheme = uri.getScheme();
		if (!"postgresql".equals(scheme) && !"postgres".equals(scheme)) {
			throw invalid("debe empezar por postgresql://");
		}
		if (uri.getHost() == null) {
			throw invalid("no dice el servidor");
		}
		String path = uri.getRawPath();
		if (path == null || path.length() <= 1) {
			throw invalid("no dice el nombre de la base de datos");
		}
		int port = uri.getPort() == -1 ? DEFAULT_PORT : uri.getPort();
		String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();

		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("spring.datasource.url", "jdbc:postgresql://" + uri.getHost() + ":" + port + path + query);
		String userInfo = uri.getRawUserInfo();
		if (userInfo != null) {
			int colon = userInfo.indexOf(':');
			properties.put("spring.datasource.username", decode(colon == -1 ? userInfo : userInfo.substring(0, colon)));
			if (colon != -1) {
				properties.put("spring.datasource.password", decode(userInfo.substring(colon + 1)));
			}
		}
		return properties;
	}

	/** Decodifica %XX sin tratar el + como espacio, que eso es de los formularios y no de las URL. */
	private static String decode(String raw) {
		try {
			return URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8);
		}
		catch (IllegalArgumentException ex) {
			throw invalid("tiene un % que no va seguido de dos dígitos hexadecimales");
		}
	}

	private static IllegalArgumentException invalid(String reason) {
		return new IllegalArgumentException(VARIABLE + " " + reason
				+ " (forma esperada: postgresql://usuario:contraseña@host:puerto/base)");
	}

}
