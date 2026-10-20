package com.example.einvoice.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.logback.StructuredLogEncoder;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.util.LogbackMDCAdapter;

/** Los registros en JSON llevan la gravedad con los nombres de Google Cloud Logging. */
class CloudLoggingSeverityTest {

	static String log(Level level) {
		LoggerContext context = new LoggerContext();
		context.setMDCAdapter(new LogbackMDCAdapter());
		context.putObject(Environment.class.getName(), new MockEnvironment()
			.withProperty("logging.structured.json.customizer", CloudLoggingSeverity.class.getName()));
		StructuredLogEncoder encoder = new StructuredLogEncoder();
		encoder.setContext(context);
		encoder.setFormat("logstash");
		encoder.start();
		LoggingEvent event = new LoggingEvent();
		event.setLoggerContext(context);
		event.setLoggerName("prueba");
		event.setLevel(level);
		event.setMessage("mensaje");
		event.setTimeStamp(0);
		return new String(encoder.encode(event), StandardCharsets.UTF_8);
	}

	@Test
	void eachLevelGetsTheSeverityCloudLoggingUnderstands() {
		assertThat(log(Level.ERROR)).contains("\"severity\":\"ERROR\"");
		assertThat(log(Level.WARN)).contains("\"severity\":\"WARNING\"");
		assertThat(log(Level.INFO)).contains("\"severity\":\"INFO\"");
		assertThat(log(Level.DEBUG)).contains("\"severity\":\"DEBUG\"");
		assertThat(log(Level.TRACE)).contains("\"severity\":\"DEBUG\"");
	}

	@Test
	void theMessageStaysInItsOwnField() {
		assertThat(log(Level.INFO)).contains("\"message\":\"mensaje\"");
	}

}
