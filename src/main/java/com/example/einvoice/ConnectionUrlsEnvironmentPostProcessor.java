package com.example.einvoice;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Toma cada conexión de una sola variable y la reparte en las propiedades que espera Spring:
 * <ul>
 * <li>DATABASE_URL, {@code postgresql://usuario:contraseña@host:puerto/base?parámetros} (o {@code postgres://}): URL
 * JDBC, usuario y contraseña. El usuario y la contraseña van aparte, y no en la URL JDBC, para que
 * SPRING_FLYWAY_USER pueda seguir migrando con otro usuario.</li>
 * <li>RABBITMQ_URL, {@code amqp://usuario:contraseña@host:puerto/vhost} (o {@code amqps://} con TLS): servidor,
 * puerto, credenciales y vhost. Spring Boot entiende esta forma en spring.rabbitmq.addresses, pero no decodifica
 * las credenciales, y una contraseña con arroba o dos puntos la rompería.</li>
 * </ul>
 * REDIS_URL no pasa por aquí: Spring Boot la lee entera en spring.data.redis.url (ver application.yml).
 * Los caracteres especiales del usuario, la contraseña o el vhost se escriben codificados ({@code %40} para la
 * arroba). Una URL que no se entiende detiene el arranque con un mensaje que nunca repite la contraseña.
 */
public class ConnectionUrlsEnvironmentPostProcessor implements EnvironmentPostProcessor {

	static final String DATABASE_URL = "DATABASE_URL";

	static final String RABBITMQ_URL = "RABBITMQ_URL";

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		Map<String, Object> properties = new LinkedHashMap<>();
		String database = environment.getProperty(DATABASE_URL);
		if (database != null && !database.isBlank()) {
			properties.putAll(datasourceProperties(database.trim()));
		}
		String rabbit = environment.getProperty(RABBITMQ_URL);
		if (rabbit != null && !rabbit.isBlank()) {
			properties.putAll(rabbitProperties(rabbit.trim()));
		}
		if (!properties.isEmpty()) {
			environment.getPropertySources().addLast(new MapPropertySource("connectionUrls", properties));
		}
	}

	static Map<String, Object> datasourceProperties(String value) {
		URI uri = parse(DATABASE_URL, value, Set.of("postgresql", "postgres"), "postgresql://usuario:contraseña@host:puerto/base");
		String path = uri.getRawPath();
		if (path == null || path.length() <= 1) {
			throw invalid(DATABASE_URL, "no dice el nombre de la base de datos", "postgresql://usuario:contraseña@host:puerto/base");
		}
		int port = uri.getPort() == -1 ? 5432 : uri.getPort();
		String query = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();

		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("spring.datasource.url", "jdbc:postgresql://" + uri.getHost() + ":" + port + path + query);
		putCredentials(DATABASE_URL, uri, "spring.datasource.username", "spring.datasource.password", properties);
		String schema = schema(uri.getRawQuery());
		if (schema != null) {
			properties.put("spring.flyway.schemas", schema);
		}
		return properties;
	}

	/**
	 * El parámetro currentSchema de la URL fija el esquema en el que trabaja la aplicación; Flyway debe migrar y
	 * crear ese mismo, o no hallaría las tablas. Sirve para una base compartida cuyo esquema public no es de esta
	 * aplicación (Supabase, por ejemplo, deja ahí una función propia y publica ese esquema en su API).
	 */
	private static String schema(String rawQuery) {
		if (rawQuery == null) {
			return null;
		}
		for (String parameter : rawQuery.split("&")) {
			int equals = parameter.indexOf('=');
			if (equals > 0 && "currentSchema".equals(parameter.substring(0, equals))) {
				String schema = decode(DATABASE_URL, parameter.substring(equals + 1)).strip();
				if (schema.isEmpty() || schema.contains(",")) {
					// Flyway gestionaría todos los de la lista, incluido limpiarlos.
					throw new IllegalArgumentException(DATABASE_URL + " debe indicar un solo esquema en currentSchema");
				}
				return schema;
			}
		}
		return null;
	}

	static Map<String, Object> rabbitProperties(String value) {
		URI uri = parse(RABBITMQ_URL, value, Set.of("amqp", "amqps"), "amqp://usuario:contraseña@host:puerto");
		boolean tls = "amqps".equals(uri.getScheme());

		Map<String, Object> properties = new LinkedHashMap<>();
		properties.put("spring.rabbitmq.host", uri.getHost());
		properties.put("spring.rabbitmq.port", String.valueOf(uri.getPort() != -1 ? uri.getPort() : tls ? 5671 : 5672));
		putCredentials(RABBITMQ_URL, uri, "spring.rabbitmq.username", "spring.rabbitmq.password", properties);
		String path = uri.getRawPath();
		if (path != null && path.length() > 1) {
			properties.put("spring.rabbitmq.virtual-host", decode(RABBITMQ_URL, path.substring(1)));
		}
		if (tls) {
			properties.put("spring.rabbitmq.ssl.enabled", "true");
		}
		return properties;
	}

	private static URI parse(String variable, String value, Set<String> schemes, String form) {
		URI uri;
		try {
			uri = new URI(value);
		}
		catch (URISyntaxException ex) {
			// El mensaje de URISyntaxException repite la URL entera, contraseña incluida.
			throw invalid(variable, "no es una URL válida", form);
		}
		if (!schemes.contains(uri.getScheme())) {
			throw invalid(variable, "debe empezar por " + form.substring(0, form.indexOf("//") + 2), form);
		}
		if (uri.getHost() == null) {
			throw invalid(variable, "no dice el servidor", form);
		}
		return uri;
	}

	private static void putCredentials(String variable, URI uri, String userProperty, String passwordProperty,
			Map<String, Object> properties) {
		String userInfo = uri.getRawUserInfo();
		if (userInfo == null) {
			return;
		}
		int colon = userInfo.indexOf(':');
		properties.put(userProperty, decode(variable, colon == -1 ? userInfo : userInfo.substring(0, colon)));
		if (colon != -1) {
			properties.put(passwordProperty, decode(variable, userInfo.substring(colon + 1)));
		}
	}

	/** Decodifica %XX sin tratar el + como espacio, que eso es de los formularios y no de las URL. */
	private static String decode(String variable, String raw) {
		try {
			return URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8);
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalArgumentException(variable + " tiene un % que no va seguido de dos dígitos hexadecimales");
		}
	}

	private static IllegalArgumentException invalid(String variable, String reason, String form) {
		return new IllegalArgumentException(variable + " " + reason + " (forma esperada: " + form + ")");
	}

}
