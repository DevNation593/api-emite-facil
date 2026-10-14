# Plan 4 — XML y firma: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** que una factura en `RESERVED` se convierta en un XML versión 1.1.0 válido contra su XSD y firmado en XAdES-BES con el certificado del emisor, guardado de forma inmutable y descargable, quedando en `SIGNED`.

**Architecture:** el documento congela al emitirse todos los datos del emisor que el XML necesita. Un generador puro escribe el XML con StAX; el XML se valida con el XSD y con las reglas que el XSD no cubre. El certificado `.p12` se guarda cifrado con cifrado de sobre (una clave de datos por certificado, envuelta con una clave maestra). La firma se construye sobre la API XMLDSig del JDK reproduciendo la estructura del Anexo 14 de la ficha, detrás de una interfaz `XmlSigner` sustituible. Un procesador lleva el documento por `GENERATED` y `SIGNED` dejando eventos de estado y artefactos con hash. Nada se envía al SRI: eso es el Plan 5, que además conectará el procesador al `outbox`.

**Tech Stack:** Spring Boot 4.1.1, StAX, `javax.xml.crypto.dsig` (JDK), AES-256-GCM (JCA), PKCS#12, Flyway, PostgreSQL 16, Testcontainers, `keytool` para certificados de prueba.

**Spec:** [docs/07-xades-signature.md](../../07-xades-signature.md), [docs/14-xsd-integration.md](../../14-xsd-integration.md), [docs/10-security.md](../../10-security.md), [docs/05-electronic-document-flow.md](../../05-electronic-document-flow.md), [docs/11-api-specification.md](../../11-api-specification.md), [docs/12-testing-certification.md](../../12-testing-certification.md), [docs/02-sri-compliance.md](../../02-sri-compliance.md) (C-02, C-04, C-11). Fuente normativa: Ficha técnica v2.34 (julio 2026), leída del portal del SRI el 2026-10-10. Contexto: [plan maestro](2026-10-10-plan-maestro.md).

## Datos tomados de la ficha técnica v2.34

- **Firma (sección 6, tabla 7):** estándar XAdES-BES, esquema 1.3.2 (`http://uri.etsi.org/01903/v1.3.2#`), codificación UTF-8, tipo *enveloped* (`http://www.w3.org/2000/09/xmldsig#enveloped-signature`). La firma cubre tres partes: todos los nodos del comprobante, el elemento `SignedProperties` y el certificado firmante incluido en `KeyInfo` (6.4, 6.5).
- **Algoritmo (6.8):** firmado RSA-SHA1, clave de 2048 bits, archivo de intercambio PKCS#12.
- **Estructura (Anexo 14, ejemplo de factura firmada):**
  - `ds:Signature` con `Id`, hijo del elemento raíz, prefijos `ds` (XMLDSig) y `etsi` (XAdES).
  - `ds:SignedInfo`: canonicalización `http://www.w3.org/TR/2001/REC-xml-c14n-20010315`, método `http://www.w3.org/2000/09/xmldsig#rsa-sha1`, y tres `ds:Reference`, todas con digest `http://www.w3.org/2000/09/xmldsig#sha1`:
    1. a `SignedProperties`, con `Type="http://uri.etsi.org/01903#SignedProperties"`;
    2. a `KeyInfo` (`URI="#Certificate…"`);
    3. al comprobante (`URI="#comprobante"`), con `Id` propio y la transformación *enveloped*.
  - `ds:SignatureValue` con `Id`.
  - `ds:KeyInfo` con `Id`, que contiene `ds:X509Data/ds:X509Certificate` y `ds:KeyValue/ds:RSAKeyValue`.
  - `ds:Object` con `etsi:QualifyingProperties Target="#<Id de la firma>"` → `etsi:SignedProperties` con `Id` → `etsi:SignedSignatureProperties` (`etsi:SigningTime`; `etsi:SigningCertificate/etsi:Cert` con `etsi:CertDigest` SHA-1 y `etsi:IssuerSerial`) y `etsi:SignedDataObjectProperties/etsi:DataObjectFormat ObjectReference="#<Id de la referencia 3>"` con `etsi:Description` `contenido comprobante` y `etsi:MimeType` `text/xml`.
- **Formato de factura versión 1.1.0 (Anexo 3):** es la versión que admite 6 decimales en cantidad y precio unitario. Fecha `dd/mm/aaaa`; moneda `DOLAR`; `propina` obligatoria; `pagos/pago` con `formaPago` y `total`. Las versiones 2.0.0 y 2.1.0 añaden rubros de terceros y la factura sustitutiva de guía de remisión, que no se usan aquí.
- **Agente de retención (Anexo 21):** etiqueta `agenteRetencion`, numérica, hasta 8 caracteres: número de la resolución sin ceros a la izquierda.
- **RIMPE (Anexo 22):** etiqueta `contribuyenteRimpe` con el texto `CONTRIBUYENTE RÉGIMEN RIMPE` o `CONTRIBUYENTE NEGOCIO POPULAR - RÉGIMEN RIMPE`.
- **RUC del proveedor (Anexo 26):** `infoAdicional/campoAdicional` con atributo `nombre="RUC Proveedor"` y el RUC como contenido. La ficha dice que es obligatorio a los 60 días **calendario** de la publicación; la noticia del SRI decía días hábiles.
- **Tamaño (sección 7):** el envío individual admite como máximo 320 KB por comprobante.

## Global Constraints

- Siguen vigentes las de los Planes 1 a 3 (Java 21, `com.example.einvoice`, errores `problem+json`, `tenantId` del contexto, recurso ajeno = 404, auditoría en la misma transacción, importes como cadenas, `./mvnw -B verify` verde y un commit por tarea).
- Los parámetros de firma son los de la ficha: RSA-SHA1, digest SHA-1, C14N inclusiva. No se sustituyen por otros.
- El `.p12` y su contraseña nunca se guardan ni se registran en claro, nunca salen por la API, nunca van al `outbox` ni a `audit_events`.
- Un XML firmado es inmutable: los artefactos se insertan y no se actualizan; se guarda su SHA-256.
- El XML se genera a partir de los datos congelados en el documento, no de las tablas vivas de emisor, cliente o producto.
- Parser XML con acceso externo bloqueado y sin `DOCTYPE`, como en el Plan 1.
- Las migraciones `V1` a `V3` no se editan; esta es `V4`.
- Cada tarea que añade rutas las añade a `contracts/openapi.yaml`.

## Decisiones de este plan

- **Versión de esquema:** factura **1.1.0**, la que admite los 6 decimales que la plataforma ya maneja.
- **Biblioteca de firma:** ninguna externa. La firma se construye con la API XMLDSig del JDK más los elementos XAdES escritos según el Anexo 14. Queda detrás de `XmlSigner` para poder cambiarla si el SRI rechaza el resultado.
- **Aceptación por el SRI:** este plan **no** la acredita. La firma se verifica criptográficamente y contra la estructura de la ficha con certificados de prueba generados localmente; el gate `SRI_CERT_ACCEPTED_FIXTURE` sigue abierto hasta probar con un certificado real en el ambiente de certificación (Plan 5).
- **Custodia del certificado:** cifrado de sobre AES-256-GCM. La clave maestra llega por la variable `EINVOICE_MASTER_KEY` (32 bytes en Base64) y la aplicación no arranca sin ella. Es un sustituto local de un KMS: en producción hay que reemplazar el proveedor de la clave maestra. El `.p12` cifrado se guarda en PostgreSQL.
- **Almacén de artefactos:** tabla en PostgreSQL detrás de `ArtifactStore`, porque el almacenamiento de objetos sigue sin decidirse (doc 13).
- **Selección de la clave en el `.p12`:** se usa la entrada con clave privada cuyo certificado declare uso `digitalSignature`; si ninguna lo declara y hay una sola entrada, esa. Más de una candidata se rechaza.
- **Relación certificado–RUC:** no se comprueba; cada entidad certificadora guarda el RUC en una extensión propia.
- **Datos fiscales del emisor nuevos:** obligado a llevar contabilidad, código de contribuyente especial, resolución de agente de retención y régimen RIMPE; todos opcionales al dar de alta el emisor.
- **RUC del proveedor:** propiedad `einvoice.provider.ruc`. Si está definida, toda factura lleva el campo adicional `RUC Proveedor`.
- **Disparo del procesamiento:** el procesador es un servicio; este plan no lo conecta a la emisión. Lo hará el Plan 5 al consumir el `outbox`.

## Review Focus

- **Texto con caracteres especiales.** Razón social o descripción con `&`, `<`, comillas, tildes, `ñ` o emoji. Esperado: XML bien formado, válido y firma verificable. Tests en Tasks 2 y 5.
- **XML alterado después de firmar.** Cambiar un importe, la `SignedProperties` o el certificado de `KeyInfo`. Esperado: la verificación falla en los tres casos. Test en Task 5.
- **`.p12` problemático.** Contraseña errónea, caducado, aún no vigente, sin clave privada, con dos claves de firma, que no es un PKCS#12. Esperado: rechazo con un código propio y nada guardado. Test en Task 4.
- **Fuga del secreto.** Esperado: ni el `.p12` ni la contraseña aparecen en respuestas, logs, auditoría ni en la base sin cifrar. Test en Task 4.
- **Procesar dos veces o en paralelo el mismo documento.** Esperado: un solo XML firmado; la segunda llamada no cambia nada. Test en Task 6.

## Estructura de archivos

```text
backend/src/main/resources/db/migration/V4__issuer_snapshot_certificates_artifacts.sql
backend/src/main/java/com/example/einvoice/
  taxpayer/Taxpayer.java, TaxpayerService.java, TaxpayerController.java   # datos fiscales nuevos
  billing/InvoiceService.java, FiscalDocumentRepository.java               # congela datos del emisor
  xml/InvoiceXmlData.java            # todo lo que el XML necesita, ya congelado
  xml/InvoiceXmlBuilder.java         # StAX, factura 1.1.0
  xml/InvoiceEnvelopeRules.java      # + coherencia de la clave de acceso
  storage/ArtifactStore.java, ArtifactKind.java, ArtifactController.java
  certificate/SecretCipher.java, AesGcmSecretCipher.java
  certificate/Pkcs12Inspector.java   # lee y valida un .p12
  certificate/CertificateService.java, CertificateController.java, SigningMaterial.java
  signature/XmlSigner.java, XadesBesSigner.java, XadesVerifier.java, SignatureVerification.java
  orchestration/DocumentProcessor.java
```

---

### Task 1: Datos fiscales del emisor, congelados en el documento

**Files:**
- Create: `V4__issuer_snapshot_certificates_artifacts.sql` (en esta tarea solo las columnas de `taxpayers` y `fiscal_documents`; las Tasks 3 y 4 añaden sus tablas al mismo archivo, aún sin fusionar)
- Modify: `taxpayer/Taxpayer.java`, `TaxpayerService.java`, `TaxpayerController.java`, `billing/InvoiceService.java`, `billing/FiscalDocumentRepository.java`, `application.yml`, `contracts/openapi.yaml`
- Test: `taxpayer/TaxpayerFiscalDataIT.java`, `billing/IssuerSnapshotIT.java`

**Interfaces:**
- Produces:
  - `taxpayers`: `accounting_required boolean NOT NULL DEFAULT false`, `special_taxpayer_code varchar(13)` (`[A-Za-z0-9]{3,13}`), `withholding_agent_code varchar(8)` (`[1-9][0-9]{0,7}`), `rimpe_regime varchar(20) NOT NULL DEFAULT 'NONE'` (`NONE`, `EMPRENDEDOR`, `NEGOCIO_POPULAR`).
  - `fiscal_documents`: `issuer_legal_name`, `issuer_trade_name`, `issuer_head_office_address`, `establishment_address`, `issuer_accounting_required`, `issuer_special_taxpayer_code`, `issuer_withholding_agent_code`, `issuer_rimpe_regime`, `provider_ruc`. Las filas existentes se rellenan desde `taxpayers` y `establishments` antes de poner `NOT NULL`.
  - `FiscalDocumentRepository.xmlData(UUID tenantId, UUID documentId): Optional<InvoiceXmlData>` (el record se define aquí y lo consume la Task 2).
- API: `POST /taxpayers` acepta `accountingRequired`, `specialTaxpayerCode`, `withholdingAgentCode`, `rimpeRegime`; las respuestas de emisor los devuelven.
- Configuración: `einvoice.provider.ruc` (vacío por defecto; si se define debe ser de 13 dígitos o la aplicación no arranca).

- [ ] **Step 1: Escribir los tests** — `TaxpayerFiscalDataIT`: `defaultsWhenOmitted` (`false`, nulos, `NONE`); `acceptsAndReturnsFiscalData`; `rejectsInvalidFiscalData` (código de contribuyente especial de 2 caracteres o con símbolos, agente de retención con ceros a la izquierda, con letras o de 9 dígitos, régimen desconocido). `IssuerSnapshotIT`: `documentFreezesIssuerData` (tras `UPDATE` directo de razón social, direcciones y régimen del emisor y de la dirección del establecimiento, `xmlData` devuelve los valores con los que se emitió); `providerRucIsFrozenWhenConfigured`.
- [ ] **Step 2: Ejecutar y ver que fallan.**
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Ejecutar y ver que pasan** — `./mvnw -B verify`.
- [ ] **Step 5: Commit** — `feat(taxpayer): datos fiscales del emisor congelados en el documento`

---

### Task 2: Generador del XML de factura 1.1.0

**Files:**
- Create: `xml/InvoiceXmlData.java` (si no quedó en Task 1), `xml/InvoiceXmlBuilder.java`
- Modify: `xml/InvoiceEnvelopeRules.java`, `xml/EnvelopeViolation.java`
- Test: `xml/InvoiceXmlBuilderTest.java`, `xml/InvoiceEnvelopeRulesTest.java`

**Interfaces:**
- Produces:
  - `InvoiceXmlBuilder.build(InvoiceXmlData data): byte[]` — XML UTF-8 sin espacios entre elementos, con declaración `<?xml version="1.0" encoding="UTF-8"?>`, raíz `<factura id="comprobante" version="1.1.0">`, elementos en el orden del XSD. Omite los opcionales sin valor. `tipoEmision` `1`, `propina` `0.00`, `moneda` `DOLAR`.
  - `InvoiceXmlBuilder.VERSION = SchemaVersion.V1_1_0`.
  - `EnvelopeViolation` gana `ACCESS_KEY_INVALID` y `ACCESS_KEY_MISMATCH`; `InvoiceEnvelopeRules.check` las reporta cuando `claveAcceso` no supera `AccessKey.valid` o no coincide con `fechaEmision`, `codDoc`, `ruc`, `ambiente`, `estab`, `ptoEmi`, `secuencial` y `tipoEmision` del mismo XML.
- Formato: importes con 2 decimales, cantidad y precio con 6, tarifa con 2, fecha `dd/MM/yyyy`, secuencial de 9 dígitos.

- [ ] **Step 1: Escribir los tests** — `InvoiceXmlBuilderTest`: `producesExpectedXml` (comparación exacta con un XML esperado para un documento fijo de dos líneas, dos tarifas y dos pagos); `resultIsValidAgainstXsdAndRules` (`XsdValidator` sin errores, `InvoiceEnvelopeRules` vacío); `omitsOptionalElementsWithoutValue` (sin nombre comercial, dirección de establecimiento ni dirección del comprador); `includesFiscalLegendsInXsdOrder` (`contribuyenteEspecial`, `obligadoContabilidad` `SI`, `agenteRetencion`, `contribuyenteRimpe`) y sigue siendo válido; `addsProviderRucAsAdditionalField`; `escapesSpecialCharacters` (`&`, `<`, `"`, `ñ`, tildes y un emoji en razón social y descripción: bien formado, válido y recuperable al leerlo); `sameInputGivesSameBytes`; `staysUnderSizeLimitFor500Lines` (menos de 320 KB). `InvoiceEnvelopeRulesTest` (añadir): `detectsInvalidAccessKey`; `detectsAccessKeyThatDoesNotMatchTheDocument` (cambiar el secuencial, el RUC o la fecha del XML).
- [ ] **Step 2: Ejecutar y ver que fallan.**
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Ejecutar y ver que pasan.**
- [ ] **Step 5: Commit** — `feat(xml): generador de factura 1.1.0 y coherencia de la clave de acceso`

---

### Task 3: Almacén de artefactos

**Files:**
- Modify: `V4__…sql` (tabla `document_artifacts`), `contracts/openapi.yaml`
- Create: `storage/ArtifactKind.java`, `ArtifactStore.java`, `ArtifactController.java`
- Test: `storage/ArtifactStoreIT.java`

**Interfaces:**
- Produces: `enum ArtifactKind { XML_UNSIGNED, XML_SIGNED }`; `ArtifactStore.save(UUID tenantId, UUID documentId, ArtifactKind kind, byte[] content): String` (devuelve el SHA-256; `MANDATORY`; falla si ya existe ese tipo para el documento) y `find(UUID tenantId, UUID documentId, ArtifactKind kind): Optional<StoredArtifact>` con `record StoredArtifact(byte[] content, String sha256)`.
- Tabla: `document_artifacts(id, tenant_id, document_id, kind, sha256, content bytea, created_at, UNIQUE (document_id, kind))` con clave foránea compuesta al documento.
- API: `GET /api/v1/documents/{id}/artifacts/{kind}` (`kind` = `xml-unsigned` o `xml-signed`) → `200` `application/xml` con cabecera `ETag` igual al SHA-256 entre comillas; `404` si el documento es ajeno o el artefacto aún no existe; `400` si el tipo no existe.

- [ ] **Step 1: Escribir `ArtifactStoreIT`** — `storesAndReturnsExactBytes`; `hashIsSha256OfContent`; `secondSaveOfSameKindFails`; `artifactOfAnotherTenantIsNotFound`; `downloadReturnsXmlWithEtag`; `unknownKindIsBadRequest`; `missingArtifactIsNotFound`.
- [ ] **Step 2 a 4:** fallar, implementar, pasar.
- [ ] **Step 5: Commit** — `feat(storage): artefactos inmutables con hash`

---

### Task 4: Certificados

**Files:**
- Modify: `V4__…sql` (tabla `certificates`), `application.yml`, `contracts/openapi.yaml`, `support/AbstractIntegrationTest.java` (propiedad `EINVOICE_MASTER_KEY` de prueba)
- Create: `certificate/SecretCipher.java`, `AesGcmSecretCipher.java`, `Pkcs12Inspector.java`, `SigningMaterial.java`, `CertificateService.java`, `CertificateController.java`; test `support/TestCertificates.java` (genera `.p12` con `keytool`)
- Test: `certificate/AesGcmSecretCipherTest.java`, `Pkcs12InspectorTest.java`, `CertificateApiIT.java`

**Interfaces:**
- Produces:
  - `SecretCipher.encrypt(byte[] plaintext, byte[] context): byte[]` y `decrypt(byte[] ciphertext, byte[] context): byte[]`. `AesGcmSecretCipher`: clave de datos aleatoria de 256 bits por mensaje, envuelta con la clave maestra; el `context` va como datos autenticados, de modo que un cifrado no se puede mover a otro certificado.
  - `Pkcs12Inspector.inspect(byte[] p12, char[] password, Instant now): SigningMaterial`; lanza `CertificateRejectedException(String errorCode)` con `CERTIFICATE_UNREADABLE` (no es PKCS#12 o contraseña errónea), `CERTIFICATE_NO_PRIVATE_KEY`, `CERTIFICATE_AMBIGUOUS`, `CERTIFICATE_EXPIRED`, `CERTIFICATE_NOT_YET_VALID`, `CERTIFICATE_KEY_UNSUPPORTED` (no RSA o menos de 2048 bits).
  - `record SigningMaterial(PrivateKey privateKey, X509Certificate certificate)`.
  - `CertificateService.signingMaterial(UUID tenantId, UUID taxpayerId, Instant now): SigningMaterial` — descifra el certificado activo; lanza `CertificateRejectedException` (`CERTIFICATE_MISSING` si no hay) y vuelve a comprobar la vigencia.
- Tabla `certificates(id, tenant_id, taxpayer_id, subject, issuer, serial_number, fingerprint_sha256, valid_from, valid_to, status, encrypted_p12 bytea, encrypted_password bytea, created_by, created_at)`; un solo `ACTIVE` por emisor (índice único parcial); al subir otro, el anterior pasa a `REPLACED`.
- API:
  - `POST /api/v1/certificates` cuerpo `{"taxpayerId","p12" (Base64),"password"}` → `201` `{"id","taxpayerId","subject","issuer","serialNumber","fingerprintSha256","validFrom","validTo","status"}`. Rol que escribe. `p12` de hasta 64 KB decodificado. Rechazos de `Pkcs12Inspector` → `422` con su `errorCode`. Auditoría `CERTIFICATE_UPLOADED` con la huella.
  - `GET /api/v1/certificates?taxpayerId=` → lista de metadatos (activo y reemplazados), nunca el contenido.
- Configuración: `einvoice.crypto.master-key: ${EINVOICE_MASTER_KEY}`; debe decodificar a 32 bytes o la aplicación no arranca.

- [ ] **Step 1: Escribir los tests** — `AesGcmSecretCipherTest`: `roundTrips`; `sameInputEncryptsDifferently`; `tamperedCiphertextFails`; `wrongContextFails`; `wrongMasterKeyFails`; `rejectsMasterKeyOfWrongLength`. `Pkcs12InspectorTest` (con `TestCertificates`): `readsValidCertificate`; `rejectsWrongPassword`; `rejectsGarbage`; `rejectsExpired`; `rejectsNotYetValid`; `rejectsStoreWithoutPrivateKey`; `rejectsTwoSigningKeys`; `rejectsShortKey` (RSA 1024). `CertificateApiIT`: `ownerUploadsCertificate`; `billerCannotUpload`; `secondUploadReplacesTheFirst`; `rejectedCertificatesStoreNothing` (un caso por código); `secretNeverLeavesTheServer` (ni la respuesta, ni `audit_events`, ni los logs capturados contienen la contraseña ni el Base64 del `.p12`, y las columnas cifradas no contienen los bytes originales); `certificateOfAnotherTenantIsNotFound`; `signingMaterialIsRecoverable` (el servicio devuelve una clave con la que se puede firmar y verificar unos bytes).
- [ ] **Step 2 a 4:** fallar, implementar, pasar.
- [ ] **Step 5: Commit** — `feat(certificate): carga y custodia cifrada del certificado de firma`

---

### Task 5: Firma XAdES-BES

**Files:**
- Create: `signature/XmlSigner.java`, `XadesBesSigner.java`, `XadesVerifier.java`, `SignatureVerification.java`
- Test: `signature/XadesSignatureIT.java`

**Interfaces:**
- Consumes: `SigningMaterial`, `InvoiceXmlBuilder`, `XsdValidator`.
- Produces:
  - `interface XmlSigner { byte[] sign(byte[] unsignedXml, SigningMaterial material, Instant signingTime); }`. `XadesBesSigner` lo implementa según "Datos tomados de la ficha" y rechaza con `IllegalArgumentException` un XML que ya tenga `ds:Signature` o cuya raíz no tenga `id="comprobante"`.
  - `record SignatureVerification(boolean valid, List<String> errors)`; `XadesVerifier.verify(byte[] signedXml): SignatureVerification` comprueba: una sola firma, hija de la raíz; exactamente las tres referencias esperadas y ninguna otra transformación; algoritmos de la ficha; el certificado de `KeyInfo` coincide con el `CertDigest` e `IssuerSerial` de `SigningCertificate`; firma y digests criptográficamente válidos con la clave pública de ese certificado.

- [ ] **Step 1: Escribir `XadesSignatureIT`** — `signatureHasTheStructureOfAnnex14` (algoritmos, tres referencias con sus `URI`/`Type`/transformación, `KeyInfo` con certificado y `RSAKeyValue`, `QualifyingProperties` con `Target`, `SigningTime`, `CertDigest`, `IssuerSerial`, `DataObjectFormat` con descripción y tipo MIME, prefijos `ds` y `etsi`); `signedInvoiceVerifies`; `signedInvoiceIsStillValidAgainstXsd`; `signingDoesNotChangeTheInvoiceContent` (quitando `ds:Signature`, los bytes canónicos del comprobante son los de antes); `tamperedAmountFailsVerification`; `tamperedSignedPropertiesFailsVerification`; `swappedCertificateFailsVerification`; `signatureByAnotherKeyFailsVerification`; `specialCharactersSurviveSigning`; `refusesToSignTwice`; `refusesDocumentWithoutComprobanteId`; `unsignedDocumentDoesNotVerify`; `largeInvoiceSignsAndVerifies` (500 líneas).
- [ ] **Step 2 a 4:** fallar, implementar, pasar.
- [ ] **Step 5: Commit** — `feat(signature): firma XAdES-BES según el Anexo 14 de la ficha`

---

### Task 6: Procesamiento hasta `SIGNED`

**Files:**
- Create: `orchestration/DocumentProcessor.java`
- Modify: `billing/FiscalDocumentRepository.java` (transición de estado con control de versión)
- Test: `orchestration/DocumentProcessingIT.java`

**Interfaces:**
- Consumes: `FiscalDocumentRepository.xmlData`, `InvoiceXmlBuilder`, `XsdValidator`, `InvoiceEnvelopeRules`, `CertificateService.signingMaterial`, `XmlSigner`, `XadesVerifier`, `ArtifactStore`.
- Produces: `DocumentProcessor.generateAndSign(UUID tenantId, UUID documentId): String` (estado resultante), transaccional. Desde `RESERVED`: genera y valida; si no es válido deja `XSD_INVALID` con el motivo; si lo es guarda `XML_UNSIGNED`, pasa a `GENERATED`, firma, verifica, guarda `XML_SIGNED`, fija `schema_version` y pasa a `SIGNED`. Sin certificado utilizable o si la verificación falla deja `SIGNATURE_FAILED` con el código del motivo. Cada cambio escribe `state_events`. Un documento que ya está en `SIGNED` o posterior se devuelve sin tocar. Desde `GENERATED` o `SIGNATURE_FAILED` reintenta solo la firma.
- `FiscalDocumentRepository.transition(UUID tenantId, UUID documentId, String from, String to, String reason): boolean` — `UPDATE … WHERE state = :from`, incrementa `version` y registra el evento; `false` si otro proceso ya lo movió.

- [ ] **Step 1: Escribir `DocumentProcessingIT`** — `reservedInvoiceBecomesSigned` (estado `SIGNED`, `schema_version` `1.1.0`, eventos `RESERVED→GENERATED→SIGNED`, dos artefactos, el firmado verifica y valida contra el XSD, y su clave de acceso es la del documento); `signedXmlIsDownloadable`; `processingTwiceChangesNothing`; `concurrentProcessingProducesOneSignedArtifact` (4 hilos); `withoutCertificateEndsInSignatureFailedAndCanBeRetried` (queda `SIGNATURE_FAILED` con motivo `CERTIFICATE_MISSING` y `XML_UNSIGNED` guardado; tras subir el certificado, otra llamada lo deja en `SIGNED`); `expiredCertificateEndsInSignatureFailed`; `documentOfAnotherTenantIsNotFound`; `invalidXmlEndsInXsdInvalid` (documento con un dato congelado que el XSD rechaza, por ejemplo una razón social de más de 300 caracteres puesta por `UPDATE` directo).
- [ ] **Step 2 a 4:** fallar, implementar, pasar.
- [ ] **Step 5: Commit** — `feat(orchestration): generación y firma de la factura hasta SIGNED`

---

### Task 7: Documentación

**Files:**
- Modify: `docs/07-xades-signature.md`, `docs/14-xsd-integration.md`, `docs/10-security.md`, `docs/05-electronic-document-flow.md`, `docs/11-api-specification.md`, `docs/13-deployment.md`, `docs/DEVELOPMENT.md`, `docs/02-sri-compliance.md`, `README.md`, `docs/superpowers/plans/2026-10-10-plan-maestro.md`

- [ ] **Step 1:** doc 07: perfil exacto de la ficha, decisión de implementar sobre el JDK, selección de clave del `.p12`, custodia real y lo que falta (KMS, relación con el RUC, aceptación del SRI). Doc 14: versión 1.1.0 elegida y la discrepancia del XSD con la leyenda de negocio popular. Doc 10: clave maestra y sus límites. Docs 05 y 11: estados nuevos, rutas de certificados y artefactos. Docs 13 y DEVELOPMENT: `EINVOICE_MASTER_KEY` y `einvoice.provider.ruc`. Doc 02: Anexos 21, 22 y 26 incorporados y la discrepancia de plazo (calendario frente a hábiles). README y plan maestro: estado.
- [ ] **Step 2: Ejecutar** — `./mvnw -B verify`.
- [ ] **Step 3: Commit** — `docs: XML, firma y custodia de certificados`

---

## Fuera de este plan

- Envío al SRI, publicador del `outbox`, reintentos y reconciliación (Plan 5).
- KMS y almacenamiento de objetos reales; rotación de la clave maestra; revocación (OCSP/CRL) y validación de la cadena de confianza del certificado.
- Comprobar que el certificado pertenece al RUC del emisor.
- Aviso de certificados por vencer; baja de certificados.
- Información adicional libre, detalles adicionales, guía de remisión, ICE e IRBPNR en el XML.
- RIDE y entrega por correo (Plan 6).
