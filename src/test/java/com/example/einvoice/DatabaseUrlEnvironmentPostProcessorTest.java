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

/** DATABASE_URL es la única variable de conexión a PostgreSQL. */
class DatabaseUrlEnvironmentPostProcessorTest {

	static MockEnvironment process(String databaseUrl) {
		MockEnvironment environment = new MockEnvironment();
		if (databaseUrl != null) {
			environment.setProperty("DATABASE_URL", databaseUrl);
		}
		new DatabaseUrlEnvironmentPostProcessor().postProcessEnvironment(environment, new SpringApplication());
		return environment;
	}

	@Test
	void theApplicationReadsBothConnectionsFromOneVariableEach() {
		try (ConfigurableApplicationContext context = new SpringApplicationBuilder(Empty.class)
			.web(WebApplicationType.NONE)
			.properties("DATABASE_URL=postgresql://app:secreto@db:5432/einvoice", "REDIS_URL=rediss://:clave@cache:6380")
			.run()) {
			ConfigurableEnvironment environment = context.getEnvironment();
			assertThat(environment.getProperty("spring.datasource.url")).isEqualTo("jdbc:postgresql://db:5432/einvoice");
			assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("app");
			assertThat(environment.getProperty("spring.data.redis.url")).isEqualTo("rediss://:clave@cache:6380");
		}
	}

	@Configuration(proxyBeanMethods = false)
	static class Empty {

	}

	@Test
	void splitsTheUrlIntoJdbcUrlUserAndPassword() {
		MockEnvironment environment = process("postgresql://app:secreto@db.example.com:6543/einvoice");
		assertThat(environment.getProperty("spring.datasource.url"))
			.isEqualTo("jdbc:postgresql://db.example.com:6543/einvoice");
		assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("app");
		assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("secreto");
	}

	@Test
	void acceptsThePostgresSchemeDefaultPortAndKeepsTheParameters() {
		MockEnvironment environment = process("postgres://app:secreto@db.example.com/einvoice?sslmode=require");
		assertThat(environment.getProperty("spring.datasource.url"))
			.isEqualTo("jdbc:postgresql://db.example.com:5432/einvoice?sslmode=require");
	}

	@Test
	void decodesPercentEncodedCredentials() {
		MockEnvironment environment = process("postgresql://app%40corp:p%40ss%3Aw+rd%2F@localhost:5432/einvoice");
		assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("app@corp");
		assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("p@ss:w+rd/");
	}

	@Test
	void allowsAUrlWithoutPassword() {
		MockEnvironment environment = process("postgresql://app@localhost/einvoice");
		assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("app");
		assertThat(environment.containsProperty("spring.datasource.password")).isFalse();
	}

	@Test
	void doesNothingWithoutTheVariable() {
		MockEnvironment environment = process(null);
		assertThat(environment.containsProperty("spring.datasource.url")).isFalse();
	}

	@Test
	void rejectsOtherSchemesWithoutRevealingThePassword() {
		assertThatThrownBy(() -> process("mysql://app:secreto@localhost/einvoice"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("DATABASE_URL")
			.hasMessageNotContaining("secreto");
	}

	@Test
	void rejectsAUrlWithoutDatabaseName() {
		assertThatThrownBy(() -> process("postgresql://app:secreto@localhost:5432"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("DATABASE_URL")
			.hasMessageNotContaining("secreto");
	}

	@Test
	void rejectsAMalformedUrlWithoutRevealingThePassword() {
		assertThatThrownBy(() -> process("postgresql://app:sec reto@localhost/einvoice"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("DATABASE_URL")
			.hasMessageNotContaining("sec reto");
	}

}
