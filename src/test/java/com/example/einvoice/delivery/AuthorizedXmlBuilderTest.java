package com.example.einvoice.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.example.einvoice.sri.SriEnvironment;
import com.example.einvoice.xml.SecureXml;

class AuthorizedXmlBuilderTest {

	private static final String KEY = "1210202601179001234500110010010000000011234567813";

	private static final OffsetDateTime AUTHORIZED_AT = OffsetDateTime.parse("2026-10-12T14:30:00Z");

	private static final String SIGNED = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><factura id=\"comprobante\""
			+ " version=\"1.1.0\"><infoTributaria><razonSocial>Ñandú &amp; Cía. S.A.</razonSocial><claveAcceso>" + KEY
			+ "</claveAcceso></infoTributaria><ds:Signature xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\">"
			+ "<ds:SignatureValue>AbC+/=</ds:SignatureValue></ds:Signature></factura>";

	private static String text(Document document, String name) {
		return ((Element) document.getDocumentElement().getElementsByTagName(name).item(0)).getTextContent();
	}

	private static Document build(String signed, SriEnvironment environment) throws Exception {
		return SecureXml.parse(AuthorizedXmlBuilder.build(KEY, AUTHORIZED_AT, environment,
				signed.getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	void wrapsTheSignedInvoiceUntouched() throws Exception {
		byte[] xml = AuthorizedXmlBuilder.build(KEY, AUTHORIZED_AT, SriEnvironment.TEST,
				SIGNED.getBytes(StandardCharsets.UTF_8));

		assertThat(new String(xml, StandardCharsets.UTF_8)).startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
		Document document = SecureXml.parse(xml);
		assertThat(document.getDocumentElement().getTagName()).isEqualTo("autorizacion");
		assertThat(text(document, "estado")).isEqualTo("AUTORIZADO");
		assertThat(text(document, "numeroAutorizacion")).isEqualTo(KEY);
		assertThat(text(document, "fechaAutorizacion")).isEqualTo("2026-10-12T09:30:00-05:00");
		assertThat(text(document, "ambiente")).isEqualTo("PRUEBAS");
		assertThat(text(document, "comprobante")).isEqualTo(SIGNED);
		assertThat(document.getDocumentElement().getElementsByTagName("mensajes").getLength()).isEqualTo(1);
	}

	@Test
	void productionLabel() throws Exception {
		assertThat(text(build(SIGNED, SriEnvironment.PRODUCTION), "ambiente")).isEqualTo("PRODUCCIÓN");
	}

	@Test
	void cdataTerminatorInsideTheInvoiceIsPreserved() throws Exception {
		String tricky = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><factura><descripcion>a ]]&gt; b ]]> c</descripcion>"
				+ "</factura>";

		assertThat(text(build(tricky, SriEnvironment.TEST), "comprobante")).isEqualTo(tricky);
	}

	@Test
	void rejectsMissingData() {
		byte[] signed = SIGNED.getBytes(StandardCharsets.UTF_8);

		assertThatThrownBy(() -> AuthorizedXmlBuilder.build(null, AUTHORIZED_AT, SriEnvironment.TEST, signed))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> AuthorizedXmlBuilder.build(" ", AUTHORIZED_AT, SriEnvironment.TEST, signed))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> AuthorizedXmlBuilder.build("12<3", AUTHORIZED_AT, SriEnvironment.TEST, signed))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> AuthorizedXmlBuilder.build(KEY, null, SriEnvironment.TEST, signed))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> AuthorizedXmlBuilder.build(KEY, AUTHORIZED_AT, SriEnvironment.TEST, new byte[0]))
			.isInstanceOf(IllegalArgumentException.class);
	}

}
