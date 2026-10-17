package com.example.einvoice.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

/** Base de las pruebas *IT: un solo contexto de Spring y un solo juego de contenedores para todas. */
@SpringBootTest(properties = { "EINVOICE_JWT_SECRET=" + TestTokens.SECRET, "EINVOICE_WEB_URL=http://web.test", "einvoice.provider.ruc=1790099999001",
		// Clave maestra solo de pruebas: Base64 de 32 bytes fijos.
		"EINVOICE_MASTER_KEY=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
		"management.endpoint.health.show-components=always",
		// Las pruebas llaman al pipeline a mano; el worker solo se enciende donde se prueba de extremo a extremo.
		"einvoice.worker.enabled=false",
		// Solo se transmite en el ambiente de pruebas; una prueba puede abrir también producción.
		"einvoice.sri.enabled-environments=1",
		// El límite de peticiones se prueba aparte; aquí no debe estorbar a las pruebas que insisten.
		"einvoice.rate-limit.requests-per-minute=1000000", "einvoice.rate-limit.writes-per-minute=1000000",
		"einvoice.auth.public-requests-per-minute=1000000",
		// Los webhooks van a un receptor local sin TLS; la política estricta se prueba aparte.
		"einvoice.webhooks.enabled=true", "einvoice.webhooks.allow-insecure-http=true",
		"einvoice.webhooks.allow-private-addresses=true" })
@AutoConfigureMockMvc
@Import({ ContainersConfig.class, FailingController.class })
public abstract class AbstractIntegrationTest {

	public static final String TENANT_HEADER = "X-Tenant-Id";

	/**
	 * Modo certificación: el SRI es el de verdad (ambiente de pruebas) y solo corre la prueba marcada
	 * con {@link RealSri}. Lo activa el perfil Maven "certification".
	 */
	protected static final boolean CERTIFICATION = Boolean.getBoolean("einvoice.certification");

	/**
	 * Ensayo del modo certificación: el mismo recorrido y las mismas variables, pero contra el SRI
	 * simulado. Sirve para comprobar la corrida antes de tener un certificado real.
	 */
	protected static final boolean SIMULATED_CERTIFICATION = Boolean.getBoolean("einvoice.certification.simulated");

	/** En modo certificación nada que no sea la corrida de certificación llega a emitir un comprobante. */
	@BeforeEach
	void onlyTheCertificationRunTalksToTheRealSri() {
		Assumptions.assumeTrue(!CERTIFICATION || getClass().isAnnotationPresent(RealSri.class),
				"Modo certificación: esta prueba no corre contra el SRI real");
	}

	/** El SRI de las pruebas es el simulado: solo el ambiente 1 está habilitado, con esperas mínimas. */
	@DynamicPropertySource
	static void simulatedSri(DynamicPropertyRegistry registry) {
		if (CERTIFICATION && !SIMULATED_CERTIFICATION) {
			// Valen las direcciones oficiales y los tiempos de application.yml.
			return;
		}
		SriStub sri = SriStub.instance();
		registry.add("einvoice.sri.allow-custom-endpoints", () -> "true");
		registry.add("einvoice.sri.test.reception", sri::receptionUrl);
		registry.add("einvoice.sri.test.authorization", sri::authorizationUrl);
		registry.add("einvoice.sri.production.reception", sri::receptionUrl);
		registry.add("einvoice.sri.production.authorization", sri::authorizationUrl);
		registry.add("einvoice.sri.connect-timeout", () -> "2s");
		registry.add("einvoice.sri.read-timeout", () -> "2s");
		registry.add("einvoice.sri.authorization-delay", () -> "0s");
		registry.add("einvoice.sri.retry-backoff", () -> "1s");
		registry.add("einvoice.sri.retry-max-attempts", () -> "5");
	}

	/** El correo de las pruebas va a un servidor SMTP simulado, con esperas mínimas. */
	@DynamicPropertySource
	static void simulatedSmtp(DynamicPropertyRegistry registry) {
		SmtpStub smtp = SmtpStub.instance();
		registry.add("spring.mail.host", smtp::host);
		registry.add("spring.mail.port", smtp::port);
		// El servidor simulado no habla TLS.
		registry.add("spring.mail.properties.mail.smtp.starttls.enable", () -> "false");
		registry.add("spring.mail.properties.mail.smtp.starttls.required", () -> "false");
		registry.add("spring.mail.properties.mail.smtp.connectiontimeout", () -> "2000");
		registry.add("spring.mail.properties.mail.smtp.timeout", () -> "3000");
		registry.add("spring.mail.properties.mail.smtp.writetimeout", () -> "3000");
		// Sin estos dos, JavaMail busca el nombre completo del equipo para saludar al servidor y para el
		// Message-ID, y en algunas máquinas Windows esa búsqueda tarda segundos por cada correo.
		registry.add("spring.mail.properties.mail.smtp.localhost", () -> "localhost");
		registry.add("spring.mail.properties.mail.from", () -> "facturas@plataforma.test");
		registry.add("einvoice.delivery.email.enabled", () -> "true");
		registry.add("einvoice.delivery.email.from", () -> "facturas@plataforma.test");
		registry.add("einvoice.delivery.retry-backoff", () -> "1s");
		registry.add("einvoice.delivery.send-lease", () -> "30s");
	}

	@Autowired
	protected MockMvc mvc;

	@Autowired
	protected JdbcTemplate jdbc;

	/** Token de un usuario recién creado, sin empresas. */
	protected String newUserToken() {
		return TestTokens.bearer(TestData.user(jdbc));
	}

	protected static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String token) {
		return request.header(HttpHeaders.AUTHORIZATION, token);
	}

	protected static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String token,
			String body) {
		return as(request, token).contentType(MediaType.APPLICATION_JSON).content(body);
	}

	/** Crea un tenant por la API; el usuario del token queda como OWNER. */
	protected UUID createTenant(String token) throws Exception {
		String body = mvc.perform(json(post("/api/v1/tenants"), token, "{\"name\":\"Empresa de prueba\"}"))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return UUID.fromString(JsonPath.read(body, "$.id"));
	}

	protected static String taxpayerJson(String ruc) {
		return "{\"ruc\":\"" + ruc + "\",\"legalName\":\"Comercial Andina S.A.\",\"tradeName\":\"Andina\","
				+ "\"headOfficeAddress\":\"Av. Amazonas N34-12, Quito\",\"defaultEnvironment\":\"1\"}";
	}

	/** Da de alta un emisor por la API con el tenant único del usuario del token. */
	protected UUID createTaxpayer(String token, String ruc) throws Exception {
		String body = mvc.perform(json(post("/api/v1/taxpayers"), token, taxpayerJson(ruc)))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return UUID.fromString(JsonPath.read(body, "$.id"));
	}

	protected UUID createEstablishment(String token, UUID taxpayerId, String code) throws Exception {
		String body = mvc
			.perform(json(post("/api/v1/taxpayers/" + taxpayerId + "/establishments"), token,
					"{\"code\":\"" + code + "\",\"address\":\"Av. 10 de Agosto, Quito\"}"))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return UUID.fromString(JsonPath.read(body, "$.id"));
	}

	protected static String emissionPointJson(UUID establishmentId, String code) {
		return "{\"establishmentId\":\"" + establishmentId + "\",\"code\":\"" + code + "\"}";
	}

	protected UUID createEmissionPoint(String token, UUID establishmentId, String code) throws Exception {
		String body = mvc.perform(json(post("/api/v1/emission-points"), token, emissionPointJson(establishmentId, code)))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return UUID.fromString(JsonPath.read(body, "$.id"));
	}

	protected static String customerJson(String idType, String idNumber, String name) {
		return "{\"idType\":\"" + idType + "\",\"idNumber\":\"" + idNumber + "\",\"name\":\"" + name
				+ "\",\"email\":\"cliente@example.test\",\"address\":\"Av. República, Quito\",\"phone\":\"022345678\"}";
	}

	protected UUID createCustomer(String token, String idType, String idNumber) throws Exception {
		String body = mvc
			.perform(json(post("/api/v1/customers"), token, customerJson(idType, idNumber, "María Pérez")))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return UUID.fromString(JsonPath.read(body, "$.id"));
	}

	protected static String productJson(String sku, String unitPrice, String ivaRateCode) {
		return "{\"sku\":\"" + sku + "\",\"name\":\"Servicio de consultoría\",\"unitPrice\":\"" + unitPrice
				+ "\",\"ivaRateCode\":\"" + ivaRateCode + "\"}";
	}

	protected UUID createProduct(String token, String sku, String unitPrice, String ivaRateCode) throws Exception {
		String body = mvc.perform(json(post("/api/v1/products"), token, productJson(sku, unitPrice, ivaRateCode)))
			.andExpect(status().isCreated())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return UUID.fromString(JsonPath.read(body, "$.id"));
	}

	protected BillingScenario newBillingScenario() throws Exception {
		String owner = newUserToken();
		UUID tenant = createTenant(owner);
		String ruc = TestData.ruc();
		UUID taxpayer = createTaxpayer(owner, ruc);
		UUID establishment = createEstablishment(owner, taxpayer, "001");
		UUID point = createEmissionPoint(owner, establishment, "001");
		UUID customer = createCustomer(owner, "05", "1712345678");
		UUID product = createProduct(owner, "SRV-001", "10.00", "4");
		return new BillingScenario(owner, tenant, taxpayer, ruc, establishment, point, customer, product);
	}

	protected MockHttpServletRequestBuilder invoiceRequest(String token, String body) {
		return json(post("/api/v1/invoices"), token, body).header("Idempotency-Key", UUID.randomUUID().toString());
	}

	/** Emite una factura con una clave de idempotencia nueva y devuelve el id del documento. */
	protected UUID issueInvoice(String token, String body) throws Exception {
		String response = mvc.perform(invoiceRequest(token, body))
			.andExpect(status().isAccepted())
			.andReturn()
			.getResponse()
			.getContentAsString();
		return UUID.fromString(JsonPath.read(response, "$.id"));
	}

	/** Carga como certificado activo del emisor uno de los .p12 de {@link TestCertificates}. */
	protected void uploadCertificate(String token, UUID taxpayerId, byte[] p12) throws Exception {
		String body = "{\"taxpayerId\":\"" + taxpayerId + "\",\"p12\":\""
				+ java.util.Base64.getEncoder().encodeToString(p12) + "\",\"password\":\"" + TestCertificates.PASSWORD
				+ "\"}";
		mvc.perform(json(post("/api/v1/certificates"), token, body)).andExpect(status().isCreated());
	}

	/** Usuario nuevo con una membresía insertada directamente con el rol indicado. */
	protected String memberToken(UUID tenantId, String role) throws Exception {
		UUID userId = TestData.user(jdbc);
		jdbc.update("INSERT INTO memberships (user_id, tenant_id, role) VALUES (?, ?, ?)", userId, tenantId, role);
		return TestTokens.bearer(userId);
	}

}
