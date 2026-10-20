package com.example.einvoice.observability;

import org.springframework.boot.json.JsonWriter.Members;
import org.springframework.boot.logging.structured.StructuredLoggingJsonMembersCustomizer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Añade a cada registro en JSON el campo {@code severity} con los nombres que entiende Google Cloud
 * Logging (DEBUG, INFO, WARNING, ERROR). Sin él, Cloud Run guarda todas las líneas con la misma
 * gravedad y no se puede filtrar por errores. Solo actúa con los registros en JSON
 * ({@code LOGGING_STRUCTURED_FORMAT_CONSOLE}).
 */
public class CloudLoggingSeverity implements StructuredLoggingJsonMembersCustomizer<ILoggingEvent> {

	@Override
	public void customize(Members<ILoggingEvent> members) {
		members.add("severity", event -> severity(event.getLevel()));
	}

	static String severity(Level level) {
		if (level.isGreaterOrEqual(Level.ERROR)) {
			return "ERROR";
		}
		if (level.isGreaterOrEqual(Level.WARN)) {
			return "WARNING";
		}
		if (level.isGreaterOrEqual(Level.INFO)) {
			return "INFO";
		}
		return "DEBUG";
	}

}
