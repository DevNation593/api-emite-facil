package com.example.einvoice.billing;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.example.einvoice.xml.DocumentSchema;
import com.example.einvoice.xml.EnvelopeRules;
import com.example.einvoice.xml.EnvelopeViolation;
import com.example.einvoice.xml.ValidationResult;
import com.example.einvoice.xml.XsdValidator;

/** La comprobación completa del XML de un comprobante: tamaño de envío, XSD y reglas del sobre. */
@Component
public class DocumentXmlCheck {

	/** Tamaño máximo de un comprobante en el envío individual al SRI (ficha técnica, sección 7). */
	private static final int MAX_XML_BYTES = 320 * 1024;

	private final XsdValidator validator;

	public DocumentXmlCheck(XsdValidator validator) {
		this.validator = validator;
	}

	/** El problema del XML, o vacío si cumple su XSD, las reglas que el XSD no cubre y el tamaño máximo. */
	public Optional<String> problem(byte[] xml, DocumentSchema schema) {
		if (xml.length > MAX_XML_BYTES) {
			return Optional.of("El XML supera el tamaño máximo de envío de 320 KB");
		}
		ValidationResult result = validator.validate(xml, schema);
		if (!result.valid()) {
			return Optional.of(String.join("; ", result.errors()));
		}
		Set<EnvelopeViolation> violations = EnvelopeRules.check(xml, schema);
		if (!violations.isEmpty()) {
			return Optional.of(violations.stream().map(Enum::name).sorted().collect(Collectors.joining(", ")));
		}
		return Optional.empty();
	}

}
