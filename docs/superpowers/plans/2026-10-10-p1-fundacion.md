# Plan 1 — Fundación: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** dejar un repositorio con backend Spring Boot que compila y valida los XSD de factura sin acceso a red, calcula la clave de acceso, arranca contra PostgreSQL/RabbitMQ/Redis y se verifica en CI.

**Architecture:** un módulo Maven `backend` con paquetes por dominio (doc 03). En este plan nacen `xml` (esquemas, validación) y `sequence` (clave de acceso). Los XSD se cargan del classpath con un resolver de allowlist cerrada; la infraestructura local corre en Docker Compose y las pruebas de integración usan Testcontainers.

**Tech Stack:** Java 21, Spring Boot, Maven wrapper, JUnit 5 + AssertJ, Flyway, PostgreSQL 16, RabbitMQ, Redis 7, Testcontainers, Docker Compose, GitHub Actions.

**Spec:** [docs/14-xsd-integration.md](../../14-xsd-integration.md), [docs/06-access-key-algorithm.md](../../06-access-key-algorithm.md), [docs/03-software-architecture.md](../../03-software-architecture.md), [docs/10-security.md](../../10-security.md), [docs/12-testing-certification.md](../../12-testing-certification.md), [docs/13-deployment.md](../../13-deployment.md). Contexto general: [plan maestro](2026-10-10-plan-maestro.md).

## Global Constraints

- Java 21: `maven.compiler.release=21`. Un JDK más nuevo sirve para compilar; el bytecode es 21.
- Spring Boot: última línea GA con soporte OSS en la fecha de ejecución. El doc 03 dice 3.x; si 3.x ya no tiene soporte, usar la vigente y corregir `docs/03-software-architecture.md` (sección "Dependencias sugeridas") en el mismo commit.
- Paquete raíz `com.example.einvoice`; `groupId` `com.example`, `artifactId` `einvoice`.
- Compilación y validación de esquemas con `XMLConstants.ACCESS_EXTERNAL_DTD=""` y `XMLConstants.ACCESS_EXTERNAL_SCHEMA=""`. Nunca relajarlas.
- Los archivos bajo `backend/src/main/resources/xsd/` no se editan.
- Todo componente de la clave de acceso es `String`; el código numérico usa `SecureRandom`.
- Zona fiscal: `America/Guayaquil`.
- Secretos solo por variables de entorno o `.env` sin versionar. `.env.example` lleva nombres, no valores reales.
- Ninguna imagen Docker con etiqueta `latest`.
- Los comandos se ejecutan en Git Bash desde la raíz del repositorio salvo que se indique `backend/`.
- Cada tarea termina con `./mvnw -B verify` en verde (desde `backend/`) y un commit.

## Review Focus

- **Fin de línea al clonar.** Con `core.autocrlf`, Git reescribe los XSD y sus SHA-256 dejan de coincidir en otra máquina o en CI. Esperado: los bytes son idénticos en todo checkout. Lo fija `.gitattributes` (Task 1) y lo prueba `XsdAssetIntegrityTest` en CI Linux (Tasks 2 y 8).
- **XML con `DOCTYPE` o entidad externa.** Esperado: se rechaza sin leer ningún archivo ni URL. Test en Task 4.
- **XML que pasa el XSD con `codDoc=00` o `version="version0"`.** Esperado: se rechaza en código. Test en Task 5.
- **Instante cercano a medianoche UTC.** A las 03:30 UTC en Ecuador todavía es el día anterior. Esperado: la fecha de la clave es la fecha local de Ecuador. Test en Task 6.
- **XSD sustituido o con un import nuevo.** Esperado: el resolver rechaza todo recurso fuera de sus dos entradas y el test de integridad falla si un archivo cambia sin actualizar el registro. Tests en Tasks 2 y 3.

## Estructura de archivos

```text
.gitattributes                         # bytes exactos para xsd/**
.env.example                           # nombres de variables de la infraestructura local
.github/workflows/ci.yml               # verify + verificador de activos
infra/docker-compose.yml               # postgres, rabbitmq, redis, minio
backend/
  pom.xml, mvnw, mvnw.cmd, .mvn/
  src/main/resources/application.yml
  src/main/resources/db/migration/V1__schema_registry.sql
  src/main/java/com/example/einvoice/
    EinvoiceApplication.java
    xml/SchemaVersion.java             # versiones de esquema y rutas de recurso
    xml/ClasspathSchemaResolver.java   # allowlist de imports y DTD
    xml/InvoiceSchemas.java            # compila y cachea Schema por versión
    xml/ValidationResult.java
    xml/XsdValidator.java              # valida bytes contra un Schema; rechaza DOCTYPE
    xml/EnvelopeViolation.java
    xml/InvoiceEnvelopeRules.java      # reglas que el XSD no cubre
    sequence/AccessKey.java            # módulo 11 (doc 06)
    sequence/AccessKeyParts.java       # componentes validados -> 48/49 dígitos
    sequence/FiscalClock.java          # fecha fiscal de Ecuador
    sequence/NumericCodeGenerator.java # 8 dígitos con SecureRandom
  src/test/java/com/example/einvoice/
    xml/XsdAssetIntegrityTest.java
    xml/ClasspathSchemaResolverTest.java
    xml/InvoiceSchemasTest.java
    xml/InvoiceXsdIT.java
    xml/InvoiceEnvelopeRulesTest.java
    sequence/AccessKeyTest.java
    sequence/AccessKeyPartsTest.java
    InfrastructureIT.java
```

---

### Task 1: Repositorio y esqueleto del backend

**Files:**
- Create: `.gitattributes`, `backend/pom.xml`, `backend/mvnw`, `backend/mvnw.cmd`, `backend/.mvn/`, `backend/src/main/java/com/example/einvoice/EinvoiceApplication.java`, `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/com/example/einvoice/HealthEndpointTest.java`

**Interfaces:**
- Produces: proyecto Maven ejecutable con `./mvnw -B verify`; `maven-failsafe-plugin` activo (las clases `*IT` corren en `verify`); endpoint `GET /actuator/health`.

- [ ] **Step 1: Fijar los bytes de los XSD e inicializar Git**

Crear `.gitattributes` **antes** del primer commit:

```gitattributes
backend/src/main/resources/xsd/** -text
```

```bash
git init -b main
git add -A
git commit -m "docs: especificación inicial y activos XSD"
```

- [ ] **Step 2: Generar el esqueleto con Spring Initializr**

```bash
curl -sS https://start.spring.io/starter.zip -d type=maven-project -d language=java -d javaVersion=21 -d groupId=com.example -d artifactId=einvoice -d name=einvoice -d packageName=com.example.einvoice -d dependencies=web,actuator,validation -o einvoice.zip
```

Descomprimir en una carpeta temporal y copiar su contenido a `backend/` **sin tocar** `backend/src/main/resources/xsd/`. Borrar `einvoice.zip`, el `application.properties` generado y la clase de test generada. Renombrar la clase principal a `EinvoiceApplication` si el generador usó otro nombre.

- [ ] **Step 3: Configurar `pom.xml` y `application.yml`**

En `pom.xml`: propiedad `<maven.compiler.release>21</maven.compiler.release>` y `maven-failsafe-plugin` con los goals `integration-test` y `verify`.

`backend/src/main/resources/application.yml`:

```yaml
spring:
  application:
    name: einvoice
management:
  endpoints:
    web:
      exposure:
        include: health
```

- [ ] **Step 4: Escribir el test de salud**

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HealthEndpointTest {
  @LocalServerPort int port;

  @Test
  void healthIsUp() throws Exception {
    var response = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health")).build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"status\":\"UP\"");
  }
}
```

- [ ] **Step 5: Ejecutar**

Run (desde `backend/`): `./mvnw -B verify`
Expected: `BUILD SUCCESS`, 1 test.

- [ ] **Step 6: Marcar `mvnw` como ejecutable y commit**

```bash
git add -A
git update-index --chmod=+x backend/mvnw
git commit -m "build: esqueleto Spring Boot del backend"
```

---

### Task 2: Integridad de los activos XSD

**Files:**
- Test: `backend/src/test/java/com/example/einvoice/xml/XsdAssetIntegrityTest.java`

**Interfaces:**
- Consumes: `docs/xsd-source-registry.csv` (9 columnas sin comas internas; `local_path` es la columna 3 y `sha256` la 6, contando desde 0).
- Produces: nada para otras tareas; es la guarda del registro.

- [ ] **Step 1: Escribir el test**

```java
class XsdAssetIntegrityTest {
  static final Path REPO = Path.of("..").toAbsolutePath().normalize();
  static final Path REGISTRY = REPO.resolve("docs/xsd-source-registry.csv");
  static final Path XSD_ROOT = REPO.resolve("backend/src/main/resources/xsd");

  record Row(String localPath, String sha256) {}

  static List<Row> rows() throws IOException {
    return Files.readAllLines(REGISTRY).stream().skip(1).filter(l -> !l.isBlank())
        .map(l -> l.split(",", 9)).map(c -> new Row(c[3], c[6])).toList();
  }

  @Test
  void registryListsTwelveAssets() throws IOException {
    assertThat(rows()).hasSize(12);
  }

  @ParameterizedTest
  @MethodSource("rows")
  void fileMatchesRegisteredHash(Row row) throws Exception {
    Path file = REPO.resolve(row.localPath());
    assertThat(file).isRegularFile();
    byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
    assertThat(HexFormat.of().formatHex(digest)).isEqualTo(row.sha256());
  }

  @Test
  void everyPackagedAssetIsRegistered() throws IOException {
    try (var files = Files.walk(XSD_ROOT)) {
      Set<String> packaged = files.filter(Files::isRegularFile)
          .map(p -> REPO.relativize(p).toString().replace('\\', '/')).collect(Collectors.toSet());
      assertThat(packaged).isEqualTo(rows().stream().map(Row::localPath).collect(Collectors.toSet()));
    }
  }
}
```

- [ ] **Step 2: Ejecutar**

Run (desde `backend/`): `./mvnw -B test -Dtest=XsdAssetIntegrityTest`
Expected: PASS, 14 tests.

- [ ] **Step 3: Comprobar que el test detecta un cambio**

Cambiar un carácter de un hash en `docs/xsd-source-registry.csv` y repetir el comando.
Expected: FAIL en `fileMatchesRegisteredHash`. Revertir con `git checkout docs/xsd-source-registry.csv` y confirmar PASS.

- [ ] **Step 4: Commit**

```bash
git add backend/src/test/java/com/example/einvoice/xml/XsdAssetIntegrityTest.java
git commit -m "test: integridad de activos XSD contra el registro"
```

---

### Task 3: Compilación de esquemas sin acceso externo

**Files:**
- Create: `backend/src/main/java/com/example/einvoice/xml/SchemaVersion.java`, `ClasspathSchemaResolver.java`, `InvoiceSchemas.java` (mismo paquete)
- Test: `backend/src/test/java/com/example/einvoice/xml/ClasspathSchemaResolverTest.java`, `InvoiceSchemasTest.java`

**Interfaces:**
- Produces:
  - `enum SchemaVersion { V1_0_0, V1_1_0, V2_0_0, V2_1_0 }` con `String value()` (`"1.0.0"`…), `static SchemaVersion of(String value)` (lanza `IllegalArgumentException` si no existe), `String resourceDir()` (`"xsd/invoice/" + value + "/"`), `String xsdResource()` (`resourceDir + "factura_V" + value + ".xsd"`), `String sampleResource()` (igual con `.xml`).
  - `final class ClasspathSchemaResolver implements LSResourceResolver` con constructor `ClasspathSchemaResolver(String resourceDir)`.
  - `@Component class InvoiceSchemas` con constructor sin argumentos, `Schema get(SchemaVersion version)` y `static SchemaFactory newSecureFactory()`.

- [ ] **Step 1: Escribir los tests**

```java
class ClasspathSchemaResolverTest {
  static final String XSD = "http://www.w3.org/2001/XMLSchema";
  static final String DTD = "http://www.w3.org/TR/REC-xml";
  final ClasspathSchemaResolver resolver = new ClasspathSchemaResolver("xsd/invoice/2.1.0/");

  @Test
  void resolvesPackagedXmldsig() throws Exception {
    LSInput in = resolver.resolveResource(XSD, "http://www.w3.org/2000/09/xmldsig#", null, "xmldsig-core-schema.xsd", null);
    assertThat(in.getByteStream().readAllBytes()).hasSize(10292);
    assertThat(in.getSystemId()).endsWith("xsd/invoice/2.1.0/xmldsig-core-schema.xsd");
  }

  @Test
  void answersSchemaDtdWithEmptySubset() throws Exception {
    LSInput in = resolver.resolveResource(DTD, null, "-//W3C//DTD XMLSchema 200102//EN", "http://www.w3.org/2001/XMLSchema.dtd", null);
    assertThat(in.getByteStream().readAllBytes()).isEmpty();
  }

  @Test
  void rejectsEverythingElse() {
    assertThatThrownBy(() -> resolver.resolveResource(XSD, null, null, "http://example.com/evil.xsd", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> resolver.resolveResource(XSD, null, null, "../2.0.0/xmldsig-core-schema.xsd", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> resolver.resolveResource(DTD, null, "-//OTRO//DTD X//EN", "http://example.com/x.dtd", null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}

class InvoiceSchemasTest {
  final InvoiceSchemas schemas = new InvoiceSchemas();

  @Test
  void factoryForbidsExternalAccess() throws Exception {
    SchemaFactory factory = InvoiceSchemas.newSecureFactory();
    assertThat(factory.getProperty(XMLConstants.ACCESS_EXTERNAL_DTD)).isEqualTo("");
    assertThat(factory.getProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA)).isEqualTo("");
    assertThat(factory.getFeature(XMLConstants.FEATURE_SECURE_PROCESSING)).isTrue();
  }

  @ParameterizedTest
  @EnumSource(SchemaVersion.class)
  void everyVersionCompiles(SchemaVersion version) {
    assertThat(schemas.get(version)).isNotNull();
  }

  @Test
  void schemaIsCompiledOnce() {
    assertThat(schemas.get(SchemaVersion.V2_1_0)).isSameAs(schemas.get(SchemaVersion.V2_1_0));
  }

  @Test
  void ofRejectsUnknownVersion() {
    assertThat(SchemaVersion.of("2.1.0")).isEqualTo(SchemaVersion.V2_1_0);
    assertThatThrownBy(() -> SchemaVersion.of("3.0.0")).isInstanceOf(IllegalArgumentException.class);
  }
}
```

- [ ] **Step 2: Ejecutar y ver que fallan**

Run (desde `backend/`): `./mvnw -B test -Dtest='ClasspathSchemaResolverTest,InvoiceSchemasTest'`
Expected: error de compilación, las clases no existen.

- [ ] **Step 3: Implementar `SchemaVersion`, `ClasspathSchemaResolver` e `InvoiceSchemas`**

Las dos entradas de la allowlist y el motivo están en el doc 14, sección "Resolución de dependencias sin red". Tres detalles que las firmas no determinan:
- El XSD principal se entrega a `SchemaFactory.newSchema` como `StreamSource` del recurso del classpath **con `systemId`**; sin él la fábrica no llama al resolver con una base utilizable.
- No hay implementación pública de `LSInput`: escribir una clase privada que devuelva `byteStream`, `systemId` y `publicId` y `null` en el resto.
- `Schema` es seguro entre hilos; cachearlo por versión. Usar una fábrica nueva por compilación.

- [ ] **Step 4: Ejecutar y ver que pasan**

Run: `./mvnw -B test -Dtest='ClasspathSchemaResolverTest,InvoiceSchemasTest'`
Expected: PASS, 10 tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/example/einvoice/xml backend/src/test/java/com/example/einvoice/xml
git commit -m "feat(xml): compilar XSD de factura sin acceso externo"
```

---

### Task 4: Validación XSD segura

**Files:**
- Create: `backend/src/main/java/com/example/einvoice/xml/ValidationResult.java`, `XsdValidator.java`
- Test: `backend/src/test/java/com/example/einvoice/xml/InvoiceXsdIT.java`

**Interfaces:**
- Consumes: `InvoiceSchemas.get(SchemaVersion)`, `SchemaVersion.sampleResource()` (Task 3).
- Produces:
  - `record ValidationResult(boolean valid, List<String> errors)`.
  - `@Component class XsdValidator` con constructor `XsdValidator(InvoiceSchemas schemas)` y `ValidationResult validate(byte[] xml, SchemaVersion version)`. Nunca lanza por contenido inválido: lo devuelve en `errors`. Un `DOCTYPE` produce un error que empieza por `DOCTYPE no permitido`.

- [ ] **Step 1: Escribir el test**

```java
class InvoiceXsdIT {
  final XsdValidator validator = new XsdValidator(new InvoiceSchemas());

  static byte[] sample(SchemaVersion version) throws IOException {
    try (var in = InvoiceXsdIT.class.getClassLoader().getResourceAsStream(version.sampleResource())) {
      return in.readAllBytes();
    }
  }

  @ParameterizedTest
  @EnumSource(value = SchemaVersion.class, names = {"V1_0_0", "V1_1_0", "V2_1_0"})
  void officialSampleValidates(SchemaVersion version) throws IOException {
    ValidationResult result = validator.validate(sample(version), version);
    assertThat(result.errors()).isEmpty();
    assertThat(result.valid()).isTrue();
  }

  @Test
  void sample200FailsOnEmptyIncoterm() throws IOException {
    ValidationResult result = validator.validate(sample(SchemaVersion.V2_0_0), SchemaVersion.V2_0_0);
    assertThat(result.valid()).isFalse();
    assertThat(result.errors()).anyMatch(e -> e.contains("incoTermFactura"));
  }

  @Test
  void rejectsDoctypeWithoutReadingExternalEntity() {
    String xml = "<?xml version=\"1.0\"?><!DOCTYPE factura [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
        + "<factura id=\"comprobante\" version=\"1.0.0\">&x;</factura>";
    ValidationResult result = validator.validate(xml.getBytes(StandardCharsets.UTF_8), SchemaVersion.V1_0_0);
    assertThat(result.valid()).isFalse();
    assertThat(result.errors().get(0)).startsWith("DOCTYPE no permitido");
  }

  @Test
  void malformedOrEmptyXmlIsInvalidNotAnException() {
    assertThat(validator.validate("<factura".getBytes(StandardCharsets.UTF_8), SchemaVersion.V1_0_0).valid()).isFalse();
    assertThat(validator.validate(new byte[0], SchemaVersion.V1_0_0).valid()).isFalse();
  }
}
```

- [ ] **Step 2: Ejecutar y ver que falla**

Run (desde `backend/`): `./mvnw -B verify -Dit.test=InvoiceXsdIT`
Expected: error de compilación, `XsdValidator` no existe.

- [ ] **Step 3: Implementar `ValidationResult` y `XsdValidator`**

Leer el XML con un parser SAX namespace-aware que tenga `http://apache.org/xml/features/disallow-doctype-decl` en `true` y pasarlo al `Validator` como `SAXSource`; así el `DOCTYPE` se rechaza antes de resolver nada. Traducir ese fallo al mensaje `DOCTYPE no permitido`.

- [ ] **Step 4: Ejecutar y ver que pasa**

Run: `./mvnw -B verify -Dit.test=InvoiceXsdIT`
Expected: PASS, 6 tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/example/einvoice/xml backend/src/test/java/com/example/einvoice/xml/InvoiceXsdIT.java
git commit -m "feat(xml): validación XSD que rechaza DOCTYPE"
```

---

### Task 5: Reglas del comprobante que el XSD no cubre

**Files:**
- Create: `backend/src/main/java/com/example/einvoice/xml/EnvelopeViolation.java`, `InvoiceEnvelopeRules.java`
- Test: `backend/src/test/java/com/example/einvoice/xml/InvoiceEnvelopeRulesTest.java`

**Interfaces:**
- Consumes: `SchemaVersion` (Task 3).
- Produces:
  - `enum EnvelopeViolation { COD_DOC_MISMATCH, VERSION_MISMATCH, ID_MISSING, SCHEMA_LOCATION_PRESENT }`.
  - `final class InvoiceEnvelopeRules` con `static Set<EnvelopeViolation> check(byte[] xml, SchemaVersion version)`. Precondición: el XML ya pasó `XsdValidator`; si no se puede leer lanza `IllegalArgumentException`.
- Reglas (doc 14, "Lo que el XSD no valida"): `infoTributaria/codDoc` debe ser `01`; el atributo `version` de la raíz debe ser `version.value()`; el atributo `id` debe ser `comprobante`; ningún elemento lleva `xsi:schemaLocation` ni `xsi:noNamespaceSchemaLocation`. La coherencia de `claveAcceso` se añade en el Plan 4, junto al generador.

- [ ] **Step 1: Escribir el test**

```java
class InvoiceEnvelopeRulesTest {
  static byte[] bytes(String xml) { return xml.getBytes(StandardCharsets.UTF_8); }

  static String envelope(String attributes, String codDoc) {
    return "<factura " + attributes + "><infoTributaria><codDoc>" + codDoc + "</codDoc></infoTributaria></factura>";
  }

  @Test
  void officialSampleViolatesWhatTheXsdDoesNotCheck() throws IOException {
    byte[] sample;
    try (var in = getClass().getClassLoader().getResourceAsStream(SchemaVersion.V2_1_0.sampleResource())) {
      sample = in.readAllBytes();
    }
    assertThat(InvoiceEnvelopeRules.check(sample, SchemaVersion.V2_1_0)).containsExactlyInAnyOrder(
        EnvelopeViolation.COD_DOC_MISMATCH, EnvelopeViolation.VERSION_MISMATCH, EnvelopeViolation.SCHEMA_LOCATION_PRESENT);
  }

  @Test
  void correctEnvelopePasses() {
    assertThat(InvoiceEnvelopeRules.check(bytes(envelope("id=\"comprobante\" version=\"2.1.0\"", "01")), SchemaVersion.V2_1_0)).isEmpty();
  }

  @Test
  void missingIdIsReported() {
    assertThat(InvoiceEnvelopeRules.check(bytes(envelope("version=\"2.1.0\"", "01")), SchemaVersion.V2_1_0))
        .containsExactly(EnvelopeViolation.ID_MISSING);
  }

  @Test
  void versionOfAnotherSchemaIsReported() {
    assertThat(InvoiceEnvelopeRules.check(bytes(envelope("id=\"comprobante\" version=\"1.1.0\"", "01")), SchemaVersion.V2_1_0))
        .containsExactly(EnvelopeViolation.VERSION_MISMATCH);
  }

  @Test
  void unreadableXmlThrows() {
    assertThatThrownBy(() -> InvoiceEnvelopeRules.check(bytes("<factura"), SchemaVersion.V2_1_0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
```

- [ ] **Step 2: Ejecutar y ver que falla**

Run (desde `backend/`): `./mvnw -B test -Dtest=InvoiceEnvelopeRulesTest`
Expected: error de compilación.

- [ ] **Step 3: Implementar `EnvelopeViolation` e `InvoiceEnvelopeRules`**

Usar un parser con `disallow-doctype-decl` igual que en Task 4.

- [ ] **Step 4: Ejecutar y ver que pasa**

Run: `./mvnw -B test -Dtest=InvoiceEnvelopeRulesTest`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/example/einvoice/xml backend/src/test/java/com/example/einvoice/xml/InvoiceEnvelopeRulesTest.java
git commit -m "feat(xml): reglas de comprobante fuera del XSD"
```

---

### Task 6: Clave de acceso

**Files:**
- Create: `backend/src/main/java/com/example/einvoice/sequence/AccessKey.java`, `AccessKeyParts.java`, `FiscalClock.java`, `NumericCodeGenerator.java`
- Test: `backend/src/test/java/com/example/einvoice/sequence/AccessKeyTest.java`, `AccessKeyPartsTest.java`

**Interfaces:**
- Produces:
  - `final class AccessKey` con `static String build(String base48)` y `static boolean valid(String key)`. El cuerpo está en el doc 06 y se copia tal cual.
  - `record AccessKeyParts(LocalDate issueDate, String documentType, String ruc, String environment, String establishment, String emissionPoint, int sequence, String numericCode, String emissionType)` con `String toBase48()` y `String toAccessKey()`. El constructor compacto lanza `IllegalArgumentException` si: `issueDate` es nulo; `documentType` no cumple `[0-9]{2}`; `ruc` no cumple `[0-9]{13}`; `environment` no es `"1"` ni `"2"`; `establishment` o `emissionPoint` no cumplen `[0-9]{3}`; `sequence` está fuera de 1..999999999; `numericCode` no cumple `[0-9]{8}`; `emissionType` no cumple `[0-9]`.
  - `final class FiscalClock` con `static final ZoneId ZONE = ZoneId.of("America/Guayaquil")` y `static LocalDate dateOf(Instant instant)`.
  - `@Component class NumericCodeGenerator` con `String next()`: 8 dígitos con ceros a la izquierda.

- [ ] **Step 1: Escribir los tests**

```java
class AccessKeyTest {
  @ParameterizedTest
  @CsvSource({
      "071020260117900123450011001002000000123123456781, 0710202601179001234500110010020000001231234567816",
      "071020260117900123450011001002000000006123456781, 0710202601179001234500110010020000000061234567811",
      "071020260117900123450011001002000000008123456781, 0710202601179001234500110010020000000081234567810"})
  void buildsDocumentedVectors(String base48, String expected) {
    assertThat(AccessKey.build(base48)).isEqualTo(expected);
    assertThat(AccessKey.valid(expected)).isTrue();
  }

  @Test
  void detectsAlteredDigit() {
    assertThat(AccessKey.valid("0710202601179001234500110010020000001231234567817")).isFalse();
    assertThat(AccessKey.valid("0710202601179001234500110010020000001241234567816")).isFalse();
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "07102026011790012345001100100200000012312345678", "07102026011790012345001100100200000012312345678A"})
  void rejectsBaseThatIsNot48Digits(String base48) {
    assertThatThrownBy(() -> AccessKey.build(base48)).isInstanceOf(IllegalArgumentException.class);
    assertThat(AccessKey.valid(base48)).isFalse();
  }
}

class AccessKeyPartsTest {
  static AccessKeyParts parts(int sequence) {
    return new AccessKeyParts(LocalDate.of(2026, 10, 7), "01", "1790012345001", "1", "001", "002", sequence, "12345678", "1");
  }

  @Test
  void buildsBaseAndKeyKeepingLeadingZeros() {
    assertThat(parts(123).toBase48()).isEqualTo("071020260117900123450011001002000000123123456781");
    assertThat(parts(123).toAccessKey()).isEqualTo("0710202601179001234500110010020000001231234567816");
  }

  @ParameterizedTest
  @ValueSource(ints = {0, -1, 1_000_000_000})
  void rejectsSequenceOutOfRange(int sequence) {
    assertThatThrownBy(() -> parts(sequence)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsMalformedComponents() {
    LocalDate date = LocalDate.of(2026, 10, 7);
    assertThatThrownBy(() -> new AccessKeyParts(null, "01", "1790012345001", "1", "001", "002", 1, "12345678", "1")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AccessKeyParts(date, "1", "1790012345001", "1", "001", "002", 1, "12345678", "1")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AccessKeyParts(date, "01", "179001234500", "1", "001", "002", 1, "12345678", "1")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AccessKeyParts(date, "01", "1790012345001", "3", "001", "002", 1, "12345678", "1")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AccessKeyParts(date, "01", "1790012345001", "1", "1", "002", 1, "12345678", "1")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new AccessKeyParts(date, "01", "1790012345001", "1", "001", "002", 1, "1234567", "1")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void fiscalDateIsEcuadorLocalDate() {
    assertThat(FiscalClock.dateOf(Instant.parse("2026-10-08T03:30:00Z"))).isEqualTo(LocalDate.of(2026, 10, 7));
    assertThat(FiscalClock.dateOf(Instant.parse("2026-10-08T05:00:00Z"))).isEqualTo(LocalDate.of(2026, 10, 8));
  }

  @RepeatedTest(20)
  void numericCodeHasEightDigits() {
    assertThat(new NumericCodeGenerator().next()).matches("[0-9]{8}");
  }
}
```

- [ ] **Step 2: Ejecutar y ver que fallan**

Run (desde `backend/`): `./mvnw -B test -Dtest='AccessKeyTest,AccessKeyPartsTest'`
Expected: error de compilación.

- [ ] **Step 3: Implementar las cuatro clases**

`AccessKey` se copia del doc 06 sin cambios. `AccessKeyParts.toBase48()` concatena en el orden de la tabla "Composición obligatoria" de ese documento, con la fecha en formato `ddMMyyyy` y el secuencial a 9 dígitos.

- [ ] **Step 4: Ejecutar y ver que pasan**

Run: `./mvnw -B test -Dtest='AccessKeyTest,AccessKeyPartsTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/example/einvoice/sequence backend/src/test/java/com/example/einvoice/sequence
git commit -m "feat(sequence): clave de acceso de 49 dígitos con módulo 11"
```

---

### Task 7: Infraestructura local, Flyway y `schema_registry`

**Files:**
- Create: `infra/docker-compose.yml`, `.env.example`, `backend/src/main/resources/db/migration/V1__schema_registry.sql`
- Modify: `backend/pom.xml`, `backend/src/main/resources/application.yml`
- Delete: `backend/src/test/java/com/example/einvoice/HealthEndpointTest.java` (lo reemplaza `InfrastructureIT`; sin base de datos el contexto ya no arranca)
- Test: `backend/src/test/java/com/example/einvoice/InfrastructureIT.java`

**Interfaces:**
- Produces: tabla `schema_registry(document_type, schema_version, resource_path, sha256, valid_from, valid_to)`; contexto Spring con `DataSource`, RabbitMQ y Redis; las migraciones del Plan 2 empiezan en `V2__`.

- [ ] **Step 1: Escribir el test de integración**

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "management.endpoint.health.show-components=always")
@Testcontainers
class InfrastructureIT {
  @Container @ServiceConnection
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");
  @Container @ServiceConnection
  static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:3-management");
  @Container @ServiceConnection(name = "redis")
  static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;

  @Test
  void healthReportsEveryDependencyUp() throws Exception {
    var response = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health")).build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"status\":\"UP\"", "\"db\"", "\"rabbit\"", "\"redis\"");
  }

  @Test
  void schemaRegistryMatchesPackagedXsd() throws Exception {
    var rows = jdbc.queryForList("SELECT resource_path, sha256 FROM schema_registry WHERE document_type = '01'");
    assertThat(rows).hasSize(4);
    for (var row : rows) {
      try (var in = getClass().getClassLoader().getResourceAsStream((String) row.get("resource_path"))) {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(in.readAllBytes());
        assertThat(HexFormat.of().formatHex(digest)).isEqualTo(row.get("sha256"));
      }
    }
  }
}
```

Las etiquetas de imagen del test son las mismas que queden en `infra/docker-compose.yml`. Si la versión de Testcontainers que gestiona Spring Boot cambió nombres de clase o artefacto, ajustar imports sin cambiar las aserciones.

- [ ] **Step 2: Ejecutar y ver que falla**

Run (desde `backend/`): `./mvnw -B verify -Dit.test=InfrastructureIT`
Expected: error de compilación por dependencias ausentes.

- [ ] **Step 3: Añadir dependencias y configuración**

En `pom.xml`: `spring-boot-starter-data-jpa`, `spring-boot-starter-amqp`, `spring-boot-starter-data-redis`, `flyway-core`, `flyway-database-postgresql`, `postgresql` (runtime); en test: `spring-boot-testcontainers` y los módulos `junit-jupiter`, `postgresql` y `rabbitmq` de Testcontainers.

Añadir a `application.yml`:

```yaml
spring:
  jpa:
    open-in-view: false
    hibernate:
      ddl-auto: validate
```

La conexión se toma de las variables estándar de Spring (`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `SPRING_RABBITMQ_*`, `SPRING_DATA_REDIS_*`); no escribir valores en el YAML.

- [ ] **Step 4: Escribir la migración**

```sql
CREATE TABLE schema_registry (
 document_type char(2) NOT NULL,
 schema_version varchar(12) NOT NULL,
 resource_path text NOT NULL,
 sha256 char(64) NOT NULL,
 valid_from date,
 valid_to date,
 PRIMARY KEY (document_type, schema_version)
);
INSERT INTO schema_registry (document_type, schema_version, resource_path, sha256) VALUES
 ('01','1.0.0','xsd/invoice/1.0.0/factura_V1.0.0.xsd','0c2d62f15c9ed03f5e5d3559dc556dd254396396247f3ea919e63473b4299442'),
 ('01','1.1.0','xsd/invoice/1.1.0/factura_V1.1.0.xsd','62db9bf0ecceb00ef2b7ed136e59224815e5e5e33c77efc6a0552001e052eb8b'),
 ('01','2.0.0','xsd/invoice/2.0.0/factura_V2.0.0.xsd','cf1bbd68f43d594f51f9983e8ca2d756e7dfc745ae63ec8ea6301c22d05a1479'),
 ('01','2.1.0','xsd/invoice/2.1.0/factura_V2.1.0.xsd','5f2c37bc1a58bb40e8bbbc366cabe05d5dc199598aeea1561137370f8bd4eace');
```

`valid_from` y `valid_to` quedan nulos hasta que la tarea N2 del plan maestro fije las vigencias.

- [ ] **Step 5: Ejecutar y ver que pasa**

Borrar `HealthEndpointTest.java`.
Run: `./mvnw -B verify`
Expected: `BUILD SUCCESS`; `InfrastructureIT` 2 tests. Requiere Docker en ejecución.

- [ ] **Step 6: Escribir `infra/docker-compose.yml` y `.env.example`**

Partir de la plantilla del doc 13 y añadir: healthcheck a `rabbitmq` (`rabbitmq-diagnostics -q ping`), `redis` (`redis-cli -a "$REDIS_PASSWORD" ping`) y `minio` (`mc ready local` o `curl -f http://localhost:9000/minio/health/live`); puertos publicados solo en `127.0.0.1` (`5432`, `5672`, `15672`, `6379`, `9000`, `9001`).

`.env.example` lista `POSTGRES_PASSWORD`, `RABBITMQ_USER`, `RABBITMQ_PASSWORD`, `REDIS_PASSWORD`, `MINIO_ROOT_USER`, `MINIO_ROOT_PASSWORD` y `MINIO_TAG`, cada una con el valor `change-me`.

- [ ] **Step 7: Verificar el arranque local**

```bash
cp .env.example .env
```

Editar `.env` con valores locales y luego:

```bash
docker compose --env-file .env -f infra/docker-compose.yml up -d
```

```bash
docker compose --env-file .env -f infra/docker-compose.yml ps
```

Expected: los cuatro servicios en estado `healthy`. `git status` no muestra `.env`.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat(infra): compose local, Flyway y schema_registry"
```

---

### Task 8: Integración continua

**Files:**
- Create: `.github/workflows/ci.yml`
- Modify: `docs/xsd-source-registry.csv` (columna `validation_status`), `README.md` (párrafo "Estado de validación")

**Interfaces:**
- Consumes: `./mvnw -B verify` (Tasks 1 a 7) y `scripts/verify_xsd_assets.py`.

- [ ] **Step 1: Escribir el workflow**

Disparadores `push` y `pull_request`. Dos jobs en `ubuntu-latest`:
- `backend`: `actions/checkout`, `actions/setup-java` (distribución `temurin`, versión `21`, caché `maven`), y `./mvnw -B verify` con `working-directory: backend`.
- `xsd-assets`: `actions/checkout` y `python3 scripts/verify_xsd_assets.py backend/src/main/resources/xsd/invoice`.

- [ ] **Step 2: Ejecutar localmente lo mismo que CI**

Run (desde `backend/`): `./mvnw -B verify`
Expected: `BUILD SUCCESS`.

Run (desde la raíz): `python scripts/verify_xsd_assets.py backend/src/main/resources/xsd/invoice`
Expected: termina con `Imports locales presentes.` y código de salida 0.

- [ ] **Step 3: Commit**

```bash
git add .github/workflows/ci.yml
git commit -m "ci: verify del backend y verificador de activos XSD"
```

- [ ] **Step 4: Publicar y comprobar CI**

Crear el repositorio remoto es una decisión del responsable del proyecto; no crearlo ni hacer push sin su confirmación. Con el remoto configurado:

```bash
git push -u origin main
```

```bash
gh run watch
```

Expected: ambos jobs en verde. Es la primera ejecución en Linux: confirma que `.gitattributes` conserva los hashes.

- [ ] **Step 5: Actualizar el registro y el README**

En `docs/xsd-source-registry.csv` cambiar `COMPILA_PRUEBA_MANUAL` por `COMPILA_CI`, `VALIDA_XSD_PRUEBA_MANUAL` por `VALIDA_XSD_CI` e `INVALIDO_XSD_PRUEBA_MANUAL` por `INVALIDO_XSD_CI`, y en las notas sustituir "pendiente test automatizado en CI" por el nombre del test. En `README.md`, sección de XSD, reemplazar la frase sobre la prueba manual por una que indique que la validación corre en CI. No usar `VALIDADO`: requiere además la tarea N1.

```bash
git add docs/xsd-source-registry.csv README.md
git commit -m "docs: validación XSD cubierta por CI"
```

---

## Fuera de este plan

- Frontend Next.js: Plan 7.
- Cliente S3 y comprobación de salud de MinIO: Plan 4, cuando se guarde el primer artefacto.
- Tablas de negocio (`tenants`, `taxpayers`, `document_sequences`…): Planes 2 y 3.
- Coherencia entre `claveAcceso` y el resto del XML: Plan 4.
- Contraste de hashes con el ZIP oficial del SRI y con W3C: tarea N1 del plan maestro.
