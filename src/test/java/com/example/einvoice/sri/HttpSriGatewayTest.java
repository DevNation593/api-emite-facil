package com.example.einvoice.sri;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.example.einvoice.sri.SriProperties.Endpoints;
import com.example.einvoice.support.SriSoapSamples;
import com.example.einvoice.support.SriSoapSamples.Msg;
import com.example.einvoice.support.SriStub;
import com.example.einvoice.support.SriStub.Captured;
import com.example.einvoice.support.SriStub.Reply;
import com.example.einvoice.xml.SecureXml;

class HttpSriGatewayTest {

	private static final String SOAP = "http://schemas.xmlsoap.org/soap/envelope/";

	private final SriStub stub = SriStub.instance();

	private static SriProperties properties(Endpoints test, Duration readTimeout) {
		return new SriProperties(Set.of("1"), true, test, test, Duration.ofSeconds(2), readTimeout, Duration.ZERO,
				Duration.ofSeconds(1), Duration.ofMinutes(1), 5);
	}

	private HttpSriGateway gateway(Duration readTimeout) {
		return new HttpSriGateway(properties(
				new Endpoints(URI.create(stub.receptionUrl()), URI.create(stub.authorizationUrl())), readTimeout));
	}

	private HttpSriGateway gateway() {
		return gateway(Duration.ofSeconds(5));
	}

	private static String accessKey() {
		StringBuilder key = new StringBuilder();
		for (int i = 0; i < 49; i++) {
			key.append(ThreadLocalRandom.current().nextInt(10));
		}
		return key.toString();
	}

	private static byte[] invoice(String accessKey) {
		return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><factura id=\"comprobante\" version=\"1.1.0\">"
				+ "<infoTributaria><claveAcceso>" + accessKey + "</claveAcceso><razonSocial>Ñandú &amp; Cía.</razonSocial>"
				+ "</infoTributaria></factura>")
			.getBytes(StandardCharsets.UTF_8);
	}

	@Test
	void receptionSendsTheSignedXmlAsBase64InTheOfficialEnvelope() throws Exception {
		String key = accessKey();
		byte[] xml = invoice(key);

		ReceptionResult result = gateway().receive(SriEnvironment.TEST, xml);

		assertThat(result.outcome()).isEqualTo(ReceptionResult.Outcome.RECEIVED);
		assertThat(result.httpStatus()).isEqualTo(200);
		Captured request = stub.lastReception(key);
		assertThat(request.contentType()).startsWith("text/xml");
		assertThat(request.soapAction()).isNotNull();
		Document soap = SecureXml.parse(request.body());
		Element envelope = soap.getDocumentElement();
		assertThat(envelope.getNamespaceURI()).isEqualTo(SOAP);
		assertThat(envelope.getLocalName()).isEqualTo("Envelope");
		Element body = (Element) envelope.getElementsByTagNameNS(SOAP, "Body").item(0);
		Element operation = (Element) body.getFirstChild();
		assertThat(operation.getNamespaceURI()).isEqualTo("http://ec.gob.sri.ws.recepcion");
		assertThat(operation.getLocalName()).isEqualTo("validarComprobante");
		Element payload = (Element) operation.getFirstChild();
		assertThat(payload.getNamespaceURI()).isNull();
		assertThat(payload.getLocalName()).isEqualTo("xml");
		assertThat(Base64.getDecoder().decode(payload.getTextContent())).isEqualTo(xml);
		assertThat(stub.lastReceptionXml(key)).isEqualTo(xml);
	}

	@Test
	void authorizationSendsTheAccessKey() throws Exception {
		String key = accessKey();

		AuthorizationResult result = gateway().authorize(SriEnvironment.TEST, key);

		assertThat(result.outcome()).isEqualTo(AuthorizationResult.Outcome.AUTHORIZED);
		assertThat(result.authorizationNumber()).isEqualTo(key);
		assertThat(result.httpStatus()).isEqualTo(200);
		assertThat(new String(result.rawResponse(), StandardCharsets.UTF_8)).isEqualTo(SriSoapSamples.authorized(key));
		Document soap = SecureXml.parse(stub.lastAuthorization(key).body());
		Element body = (Element) soap.getDocumentElement().getElementsByTagNameNS(SOAP, "Body").item(0);
		Element operation = (Element) body.getFirstChild();
		assertThat(operation.getNamespaceURI()).isEqualTo("http://ec.gob.sri.ws.autorizacion");
		assertThat(operation.getLocalName()).isEqualTo("autorizacionComprobante");
		Element payload = (Element) operation.getFirstChild();
		assertThat(payload.getNamespaceURI()).isNull();
		assertThat(payload.getLocalName()).isEqualTo("claveAccesoComprobante");
		assertThat(payload.getTextContent()).isEqualTo(key);
	}

	@Test
	void accessKeyThatIsNotFortyNineDigitsNeverReachesTheSri() {
		AuthorizationResult result = gateway().authorize(SriEnvironment.TEST, "1</claveAccesoComprobante><x>");

		assertThat(result.outcome()).isEqualTo(AuthorizationResult.Outcome.FAILED);
		assertThat(result.httpStatus()).isNull();
	}

	@Test
	void returnedIsParsedWithItsMessages() {
		String key = accessKey();
		stub.onReception(key, Reply.xml(SriSoapSamples.returned(key, Msg.error("35", "DOCUMENTO INVÁLIDO"))));

		ReceptionResult result = gateway().receive(SriEnvironment.TEST, invoice(key));

		assertThat(result.outcome()).isEqualTo(ReceptionResult.Outcome.RETURNED);
		assertThat(result.messages()).extracting(SriMessage::message).containsExactly("DOCUMENTO INVÁLIDO");
		assertThat(result.rawResponse()).isNotEmpty();
	}

	@Test
	void http500IsAFailureThatKeepsTheStatus() {
		String key = accessKey();
		stub.onReception(key, Reply.status(500, SriSoapSamples.fault()));
		stub.onAuthorization(key, Reply.status(503, "Service Unavailable"));

		ReceptionResult reception = gateway().receive(SriEnvironment.TEST, invoice(key));
		AuthorizationResult authorization = gateway().authorize(SriEnvironment.TEST, key);

		assertThat(reception.outcome()).isEqualTo(ReceptionResult.Outcome.FAILED);
		assertThat(reception.httpStatus()).isEqualTo(500);
		assertThat(reception.errorDetail()).contains("500");
		assertThat(authorization.outcome()).isEqualTo(AuthorizationResult.Outcome.FAILED);
		assertThat(authorization.httpStatus()).isEqualTo(503);
		assertThat(new String(authorization.rawResponse(), StandardCharsets.UTF_8)).isEqualTo("Service Unavailable");
	}

	@Test
	void htmlWith200IsAFailure() {
		String key = accessKey();
		stub.onReception(key, Reply.xml("<html><body>Mantenimiento</body></html>"));

		ReceptionResult result = gateway().receive(SriEnvironment.TEST, invoice(key));

		assertThat(result.outcome()).isEqualTo(ReceptionResult.Outcome.FAILED);
		assertThat(result.httpStatus()).isEqualTo(200);
		assertThat(result.errorDetail()).isNotBlank();
	}

	@Test
	void slowAnswerTimesOut() {
		String key = accessKey();
		stub.onReception(key, Reply.delayed(Duration.ofSeconds(4), Reply.xml(SriSoapSamples.received())));
		long start = System.nanoTime();

		ReceptionResult result = gateway(Duration.ofMillis(300)).receive(SriEnvironment.TEST, invoice(key));

		assertThat(result.outcome()).isEqualTo(ReceptionResult.Outcome.FAILED);
		assertThat(result.httpStatus()).isNull();
		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(3500));
	}

	@Test
	void droppedConnectionIsAFailure() {
		String key = accessKey();
		stub.onAuthorization(key, Reply.dropConnection());

		AuthorizationResult result = gateway().authorize(SriEnvironment.TEST, key);

		assertThat(result.outcome()).isEqualTo(AuthorizationResult.Outcome.FAILED);
		assertThat(result.errorDetail()).isNotBlank();
	}

	@Test
	void nothingListeningIsAFailureThatDoesNotLeakTheAddress() {
		Endpoints nowhere = new Endpoints(URI.create("http://127.0.0.1:1/recepcion"),
				URI.create("http://127.0.0.1:1/autorizacion"));
		HttpSriGateway gateway = new HttpSriGateway(properties(nowhere, Duration.ofSeconds(2)));

		ReceptionResult result = gateway.receive(SriEnvironment.TEST, invoice(accessKey()));

		assertThat(result.outcome()).isEqualTo(ReceptionResult.Outcome.FAILED);
		assertThat(result.errorDetail()).isNotBlank().doesNotContain("127.0.0.1");
	}

	@Test
	void oversizedAnswerIsAFailure() {
		String key = accessKey();
		stub.onAuthorization(key, Reply.bytes(3 * 1024 * 1024));

		AuthorizationResult result = gateway().authorize(SriEnvironment.TEST, key);

		assertThat(result.outcome()).isEqualTo(AuthorizationResult.Outcome.FAILED);
		assertThat(result.errorDetail()).contains("grande");
		assertThat(result.rawResponse()).isNull();
	}

}
