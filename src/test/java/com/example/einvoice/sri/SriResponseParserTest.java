package com.example.einvoice.sri;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.example.einvoice.support.SriSoapSamples;
import com.example.einvoice.support.SriSoapSamples.Msg;

class SriResponseParserTest {

	private static final String KEY = "0503201201176001321000110010030009900641234567814";

	private static byte[] bytes(String xml) {
		return xml.getBytes(StandardCharsets.UTF_8);
	}

	@Test
	void receivedHasNoMessages() {
		ReceptionResult result = SriResponseParser.reception(bytes(SriSoapSamples.received()));

		assertThat(result.outcome()).isEqualTo(ReceptionResult.Outcome.RECEIVED);
		assertThat(result.sriStatus()).isEqualTo("RECIBIDA");
		assertThat(result.messages()).isEmpty();
		assertThat(result.errorDetail()).isNull();
	}

	@Test
	void returnedCarriesEveryMessage() {
		ReceptionResult result = SriResponseParser.reception(bytes(SriSoapSamples.returned(KEY,
				new Msg("35", "DOCUMENTO INVÁLIDO", "Se encontró el siguiente error en la estructura", "ERROR"),
				Msg.error("45", "SECUENCIAL REGISTRADO"))));

		assertThat(result.outcome()).isEqualTo(ReceptionResult.Outcome.RETURNED);
		assertThat(result.sriStatus()).isEqualTo("DEVUELTA");
		assertThat(result.messages()).containsExactly(
				new SriMessage("35", "DOCUMENTO INVÁLIDO", "Se encontró el siguiente error en la estructura", "ERROR"),
				new SriMessage("45", "SECUENCIAL REGISTRADO", null, "ERROR"));
		assertThat(result.hasMessage("35")).isTrue();
		assertThat(result.hasMessage("70")).isFalse();
	}

	@Test
	void authorizedCarriesNumberDateAndEnvironment() {
		AuthorizationResult result = SriResponseParser.authorization(bytes(SriSoapSamples.authorized(KEY)));

		assertThat(result.outcome()).isEqualTo(AuthorizationResult.Outcome.AUTHORIZED);
		assertThat(result.sriStatus()).isEqualTo("AUTORIZADO");
		assertThat(result.consultedAccessKey()).isEqualTo(KEY);
		assertThat(result.authorizationNumber()).isEqualTo(KEY);
		assertThat(result.authorizedAt()).isEqualTo(OffsetDateTime.parse("2012-03-05T16:57:34.997-05:00"));
		assertThat(result.environmentLabel()).isEqualTo("PRUEBAS");
		assertThat(result.messages()).containsExactly(new SriMessage("60",
				"ESTE PROCESO FUE REALIZADO EN EL AMBIENTE DE PRUEBAS", null, "ADVERTENCIA"));
	}

	@Test
	void rejectedIsNotAuthorized() {
		AuthorizationResult result = SriResponseParser
			.authorization(bytes(SriSoapSamples.notAuthorized(KEY, "RECHAZADO", Msg.error("46", "RUC no existe"))));

		assertThat(result.outcome()).isEqualTo(AuthorizationResult.Outcome.NOT_AUTHORIZED);
		assertThat(result.sriStatus()).isEqualTo("RECHAZADO");
		assertThat(result.messages()).extracting(SriMessage::identifier).containsExactly("46");
		assertThat(result.authorizationNumber()).isNull();
	}

	@Test
	void noAutorizadoIsNotAuthorized() {
		AuthorizationResult result = SriResponseParser
			.authorization(bytes(SriSoapSamples.notAuthorized(KEY, "NO AUTORIZADO", Msg.error("46", "RUC no existe"))));

		assertThat(result.outcome()).isEqualTo(AuthorizationResult.Outcome.NOT_AUTHORIZED);
		assertThat(result.sriStatus()).isEqualTo("NO AUTORIZADO");
	}

	@Test
	void oneAuthorizedWinsOverEarlierRejections() {
		String body = SriSoapSamples.authorizations(KEY,
				SriSoapSamples.autorizacion("NO AUTORIZADO", null, "2012-03-05T10:00:00-05:00", "PRUEBAS",
						Msg.error("39", "FIRMA INVALIDA")),
				SriSoapSamples.autorizacion("AUTORIZADO", KEY, "2012-03-05T12:00:00-05:00", "PRUEBAS"),
				SriSoapSamples.autorizacion("NO AUTORIZADO", null, "2012-03-05T11:00:00-05:00", "PRUEBAS",
						Msg.error("52", "ERROR EN DIFERENCIAS")));

		AuthorizationResult result = SriResponseParser.authorization(bytes(body));

		assertThat(result.outcome()).isEqualTo(AuthorizationResult.Outcome.AUTHORIZED);
		assertThat(result.authorizedAt()).isEqualTo(OffsetDateTime.parse("2012-03-05T12:00:00-05:00"));
		assertThat(result.messages()).isEmpty();
	}

	@Test
	void latestRejectionIsReported() {
		String body = SriSoapSamples.authorizations(KEY,
				SriSoapSamples.autorizacion("NO AUTORIZADO", null, "2012-03-05T11:00:00-05:00", "PRUEBAS",
						Msg.error("52", "ERROR EN DIFERENCIAS")),
				SriSoapSamples.autorizacion("NO AUTORIZADO", null, "2012-03-05T10:00:00-05:00", "PRUEBAS",
						Msg.error("39", "FIRMA INVALIDA")));

		AuthorizationResult result = SriResponseParser.authorization(bytes(body));

		assertThat(result.outcome()).isEqualTo(AuthorizationResult.Outcome.NOT_AUTHORIZED);
		assertThat(result.messages()).extracting(SriMessage::identifier).containsExactly("52");
	}

	@Test
	void noAuthorizationsMeansNotFound() {
		AuthorizationResult result = SriResponseParser.authorization(bytes(SriSoapSamples.notFound(KEY)));

		assertThat(result.outcome()).isEqualTo(AuthorizationResult.Outcome.NOT_FOUND);
		assertThat(result.consultedAccessKey()).isEqualTo(KEY);
		assertThat(result.sriStatus()).isNull();
	}

	@Test
	void unknownStateIsProcessing() {
		AuthorizationResult result = SriResponseParser.authorization(bytes(SriSoapSamples.processing(KEY)));

		assertThat(result.outcome()).isEqualTo(AuthorizationResult.Outcome.PROCESSING);
		assertThat(result.sriStatus()).isEqualTo("EN PROCESO");
	}

	@Test
	void dateWithoutOffsetIsReadAsEcuadorTime() {
		String body = SriSoapSamples.authorizations(KEY,
				SriSoapSamples.autorizacion("AUTORIZADO", KEY, "2026-10-11T09:30:00", "PRUEBAS"));

		assertThat(SriResponseParser.authorization(bytes(body)).authorizedAt())
			.isEqualTo(OffsetDateTime.parse("2026-10-11T09:30:00-05:00"));
	}

	static Stream<Arguments> garbage() {
		return Stream.of(Arguments.of("vacío", ""), Arguments.of("html", "<html><body>503 Service Unavailable</body></html>"),
				Arguments.of("doctype",
						"<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>" + SriSoapSamples.received()),
				Arguments.of("sin respuesta",
						"<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body/></soap:Envelope>"),
				Arguments.of("fault", SriSoapSamples.fault()), Arguments.of("texto", "no es xml"));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("garbage")
	void garbageNeverThrows(String name, String body) {
		ReceptionResult reception = SriResponseParser.reception(bytes(body));
		assertThat(reception.outcome()).isEqualTo(ReceptionResult.Outcome.FAILED);
		assertThat(reception.errorDetail()).isNotBlank();

		AuthorizationResult authorization = SriResponseParser.authorization(bytes(body));
		assertThat(authorization.outcome()).isEqualTo(AuthorizationResult.Outcome.FAILED);
		assertThat(authorization.errorDetail()).isNotBlank();
	}

	@Test
	void unknownReceptionStateIsAFailure() {
		ReceptionResult result = SriResponseParser.reception(bytes(SriSoapSamples.receptionWithState("EN COLA")));

		assertThat(result.outcome()).isEqualTo(ReceptionResult.Outcome.FAILED);
		assertThat(result.sriStatus()).isEqualTo("EN COLA");
	}

	@Test
	void authorizedWithoutNumberOrReadableDateIsAFailure() {
		String withoutNumber = SriSoapSamples.authorizations(KEY,
				SriSoapSamples.autorizacion("AUTORIZADO", null, SriSoapSamples.AUTHORIZATION_DATE, "PRUEBAS"));
		String badDate = SriSoapSamples.authorizations(KEY,
				SriSoapSamples.autorizacion("AUTORIZADO", KEY, "ayer por la tarde", "PRUEBAS"));
		String badNumber = SriSoapSamples.authorizations(KEY,
				SriSoapSamples.autorizacion("AUTORIZADO", "ABC", SriSoapSamples.AUTHORIZATION_DATE, "PRUEBAS"));

		for (String body : new String[] { withoutNumber, badDate, badNumber }) {
			AuthorizationResult result = SriResponseParser.authorization(bytes(body));
			assertThat(result.outcome()).isEqualTo(AuthorizationResult.Outcome.FAILED);
			assertThat(result.sriStatus()).isEqualTo("AUTORIZADO");
			assertThat(result.errorDetail()).isNotBlank();
		}
	}

	@Test
	void longTextsAndLongListsAreBounded() {
		Msg[] many = new Msg[150];
		for (int i = 0; i < many.length; i++) {
			many[i] = new Msg("35", "x".repeat(5000), "y".repeat(5000), "ERROR");
		}

		ReceptionResult result = SriResponseParser.reception(bytes(SriSoapSamples.returned(KEY, many)));

		assertThat(result.messages()).hasSize(100);
		assertThat(result.messages().get(0).message()).hasSize(2000);
		assertThat(result.messages().get(0).additionalInfo()).hasSize(2000);
	}

	@Test
	void environmentLabels() {
		assertThat(SriEnvironment.TEST.matchesLabel("PRUEBAS")).isTrue();
		assertThat(SriEnvironment.PRODUCTION.matchesLabel("PRODUCCIÓN")).isTrue();
		assertThat(SriEnvironment.PRODUCTION.matchesLabel("produccion")).isTrue();
		assertThat(SriEnvironment.TEST.matchesLabel("PRODUCCIÓN")).isFalse();
		assertThat(SriEnvironment.PRODUCTION.matchesLabel("PRUEBAS")).isFalse();
		assertThat(SriEnvironment.TEST.matchesLabel(null)).isFalse();
		assertThat(SriEnvironment.fromCode("1")).isEqualTo(SriEnvironment.TEST);
		assertThat(SriEnvironment.fromCode("2")).isEqualTo(SriEnvironment.PRODUCTION);
	}

}
