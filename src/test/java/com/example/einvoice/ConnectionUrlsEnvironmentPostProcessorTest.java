package com.example.einvoice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.mock.env.MockEnvironment;

/** DATABASE_URL, RABBITMQ_URL y REDIS_URL son las únicas variables de conexión. */
class ConnectionUrlsEnvironmentPostProcessorTest {

	static MockEnvironment process(String name, String value) {
		MockEnvironment environment = new MockEnvironment();
		if (value != null) {
			environment.setProperty(name, value);
		}
		new ConnectionUrlsEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());
		return environment;
	}

	static MockEnvironment database(String url) {
		return process("DATABASE_URL", url);
	}

	static MockEnvironment rabbit(String url) {
		return process("RABBITMQ_URL", url);
	}

	@Test
	void theApplicationReadsEachConnectionFromOneVariable() {
		try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Empty.class)
			.web(WebApplicationType.NONE)
			.properties("DATABASE_URL=postgresql://app:secreto@db:5432/einvoice",
					"RABBITMQ_URL=amqp://einvoice:clave@broker:5672", "REDIS_URL=rediss://:clave@cache:6380")
			.run()) {
			ConfigurableEnvironment environment = context.getEnvironment();
			assertThat(environment.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://db:5432/einvoice");
			assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("app");
			assertThat(environment.getProperty("spring.rabbitmq.host")).isEqualTo("broker");
			assertThat(environment.getProperty("spring.rabbitmq.username")).isEqualTo("einvoice");
			assertThat(environment.getProperty("spring.data.redis.url")).isEqualTo("rediss://:clave@cache:6380");
		}
	}

	@Configuration(proxyBeanMethods = false)
	static class Empty {

	}

	@Test
	void splitsTheDatabaseUrlIntoJdbcUrlUserAndPassword() {
		MockEnvironment environment = database("postgresql://app:secreto@db.example.com:6543/einvoice");
		assertThat(environment.getProperty("spring.datasource.url"))
			.isEqualTo("jdbc:postgresql://db.example.com:6543/einvoice");
		assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("app");
		assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("secreto");
	}

	@Test
	void acceptsThePostgresSchemeDefaultPortAndKeepsTheParameters() {
		MockEnvironment environment = database("postgres://app:secreto@db.example.com/einvoice?sslmode=require");
		assertThat(environment.getProperty("spring.datasource.url"))
			.isEqualTo("jdbc:postgresql://db.example.com:5432/einvoice?sslmode=require");
	}

	@Test
	void decodesPercentEncodedCredentials() {
		MockEnvironment environment = database("postgresql://app%40corp:p%40ss%3Aw+rd%2F@localhost:5432/einvoice");
		assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("app@corp");
		assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("p@ss:w+rd/");
	}

	@Test
	void allowsAUrlWithoutPassword() {
		MockEnvironment environment = database("postgresql://app@localhost/einvoice");
		assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("app");
		assertThat(environment.containsProperty("spring.datasource.password")).isFalse();
	}

	@Test
	void doesNothingWithoutTheVariables() {
		MockEnvironment environment = database(null);
		assertThat(environment.containsProperty("spring.datasource.url")).isFalse();
		assertThat(environment.containsProperty("spring.rabbitmq.host")).isFalse();
	}

	@Test
	void rejectsOtherSchemesWithoutRevealingThePassword() {
		assertThatThrownBy(() -> database("mysql://app:secreto@localhost/einvoice"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("DATABASE_URL")
			.hasMessageNotContaining("secreto");
	}

	@Test
	void rejectsADatabaseUrlWithoutDatabaseName() {
		assertThatThrownBy(() -> database("postgresql://app:secreto@localhost:5432"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("DATABASE_URL")
			.hasMessageNotContaining("secreto");
	}

	@Test
	void rejectsAMalformedUrlWithoutRevealingThePassword() {
		assertThatThrownBy(() -> database("postgresql://app:sec reto@localhost/einvoice"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("DATABASE_URL")
			.hasMessageNotContaining("sec reto");
	}

	@Test
	void splitsTheRabbitUrlAndDecodesItsCredentials() {
		MockEnvironment environment = rabbit("amqp://einvoice:cl%40ve%3A1@broker.example.com:5673");
		assertThat(environment.getProperty("spring.rabbitmq.host")).isEqualTo("broker.example.com");
		assertThat(environment.getProperty("spring.rabbitmq.port")).isEqualTo("5673");
		assertThat(environment.getProperty("spring.rabbitmq.username")).isEqualTo("einvoice");
		assertThat(environment.getProperty("spring.rabbitmq.password")).isEqualTo("cl@ve:1");
		assertThat(environment.containsProperty("spring.rabbitmq.virtual-host")).isFalse();
		assertThat(environment.containsProperty("spring.rabbitmq.ssl.enabled")).isFalse();
	}

	@Test
	void rabbitOverTlsUsesItsDefaultPortAndTheEncodedVirtualHost() {
		MockEnvironment environment = rabbit("amqps://einvoice:clave@broker.example.com/facturas%2Fprod");
		assertThat(environment.getProperty("spring.rabbitmq.port")).isEqualTo("5671");
		assertThat(environment.getProperty("spring.rabbitmq.ssl.enabled")).isEqualTo("true");
		assertThat(environment.getProperty("spring.rabbitmq.virtual-host")).isEqualTo("facturas/prod");
	}

	@Test
	void rejectsARabbitUrlWithAnotherSchemeWithoutRevealingThePassword() {
		assertThatThrownBy(() -> rabbit("http://einvoice:secreto@broker:5672"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("RABBITMQ_URL")
			.hasMessageNotContaining("secreto");
	}

}
