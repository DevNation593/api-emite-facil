# Plan 3 — Catálogos, factura y secuenciales: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** que un usuario pueda registrar clientes y productos y emitir una factura que queda calculada, numerada, con clave de acceso y en estado `RESERVED`, sin duplicar secuenciales ni comprobantes.

**Architecture:** los catálogos tributarios son datos sembrados desde la ficha técnica, no constantes de código. `POST /invoices` hace todo en una transacción: valida, calcula con `BigDecimal`, reserva el secuencial con bloqueo de fila, genera la clave de acceso, guarda el documento con sus líneas y pagos, registra el evento de estado, la auditoría y el evento `outbox`. La idempotencia se apoya en una restricción única por `(tenant, Idempotency-Key)`. Nada se transmite todavía: firma y SRI son los Planes 4 y 5.

**Tech Stack:** Spring Boot 4.1.1, JPA para clientes y productos, `JdbcClient` para documentos y secuenciales, Flyway, PostgreSQL 16, MockMvc, Testcontainers.

**Spec:** [docs/01-product-requirements.md](../../01-product-requirements.md) (PR-04, PR-05), [docs/04-database-design.md](../../04-database-design.md), [docs/05-electronic-document-flow.md](../../05-electronic-document-flow.md), [docs/06-access-key-algorithm.md](../../06-access-key-algorithm.md), [docs/11-api-specification.md](../../11-api-specification.md), [docs/12-testing-certification.md](../../12-testing-certification.md). Fuente normativa: Ficha técnica de comprobantes electrónicos esquema off-line v2.34 (julio 2026), leída del portal del SRI el 2026-10-10. Contexto: [plan maestro](2026-10-10-plan-maestro.md).

## Datos tomados de la ficha técnica v2.34

- **Tabla 6, tipo de identificación:** `04` RUC, `05` cédula, `06` pasaporte, `07` venta a consumidor final (identificación `9999999999999`), `08` identificación del exterior.
- **Tabla 16, impuestos:** IVA `2`, ICE `3`, IRBPNR `5`.
- **Tabla 17, tarifa del IVA (código → porcentaje):** `0` → 0 %, `2` → 12 %, `3` → 14 %, `4` → 15 %, `5` → 5 %, `6` no objeto de impuesto, `7` exento de IVA, `8` IVA diferenciado (8 %, sector turístico por decreto), `10` → 13 %.
- **Tabla 24, formas de pago:** `01` sin utilización del sistema financiero y `15` compensación de deudas (desde 01/01/2013); `16` tarjeta de débito, `17` dinero electrónico, `18` tarjeta prepago, `19` tarjeta de crédito, `20` otros con utilización del sistema financiero, `21` endoso de títulos (desde 01/06/2016). Ninguna tiene fecha de fin.
- **9.10:** si el valor de una factura a consumidor final es mayor a 50 USD, hay que identificar al adquirente.
- **9.16, tabla 21:** el IVA se calcula sobre la suma de las bases de cada tarifa; el valor total es subtotal + ICE + IRBPNR + IVA + propina.
- **9.17:** los valores llevan como máximo dos decimales, salvo cantidad y precio unitario, que admiten hasta seis (versión 1.1.0 en adelante).
- **9.18:** los comprobantes se emiten en orden cronológico y secuencial, sin duplicar secuencia ni clave de acceso.

## Global Constraints

- Siguen vigentes las de los Planes 1 y 2: Java 21, paquete `com.example.einvoice`, base `/api/v1`, errores `problem+json` con `errorCode`, `tenantId` solo del contexto verificado, recurso ajeno = 404, alta con auditoría en la misma transacción, `./mvnw -B verify` verde y un commit por tarea.
- Dinero y cantidades: `BigDecimal` en Java, `numeric` en la base, **cadenas decimales** en JSON de entrada y de salida. Nunca `double` ni `float`.
- Redondeo `HALF_UP` a 2 decimales en importes; cantidad y precio unitario hasta 6 decimales.
- Los catálogos (identificación, IVA, formas de pago) se leen de tablas; ningún código ni porcentaje aparece como constante en el código de negocio.
- Secuencial, clave de acceso, documento, evento de estado, auditoría y `outbox` se confirman en una sola transacción o no se confirma ninguno.
- El `outbox` nunca lleva XML, certificados ni datos del cliente: solo identificadores.
- Las migraciones `V1` y `V2` no se editan; esta es `V3`.
- Cada tarea que añade rutas las añade también a `contracts/openapi.yaml` (lo exige `OpenApiContractIT`).
- Texto libre sin caracteres de control (`TextRules.NO_CONTROL_CHARS`).

## Decisiones de este plan

- **Tipo de documento:** solo factura (`01`), moneda solo `USD`, sin ICE, IRBPNR, propina, reembolsos ni exportación. Un impuesto por línea: IVA.
- **Roles:** emiten facturas y crean clientes `OWNER`, `ADMIN` y `BILLER`; productos y secuencial inicial, solo `OWNER` y `ADMIN`; `AUDITOR` solo lee.
- **Validación de identificaciones:** solo formato. RUC 13 dígitos, cédula 10 dígitos, consumidor final exactamente `9999999999999`, pasaporte e identificación del exterior de 3 a 20 caracteres alfanuméricos. El dígito verificador queda fuera.
- **Fecha de emisión:** por defecto hoy en Ecuador. No puede ser futura ni anterior a la del último comprobante de ese punto de emisión (orden cronológico, 9.18). No se fija un máximo de días hacia atrás porque la ficha no lo da.
- **Pagos:** obligatorios; su suma debe ser igual al total calculado por el servidor.
- **Secuencial:** un contador por punto de emisión, ambiente y tipo de documento. Empieza en 1 salvo que se fije antes de emitir.
- **Ambiente:** el `defaultEnvironment` del emisor.
- **Datos congelados:** el documento guarda copia de la identificación, nombre, dirección y correo del cliente, y de código, descripción y tarifa de cada línea; cambios posteriores en cliente o producto no lo alteran.
- **Versión de esquema:** el documento no la fija todavía; se decide al generar el XML (Plan 4, tarea N6).
- **`DRAFT`:** no se implementa; `POST /invoices` crea en `RESERVED` (doc 05).

## Review Focus

- **Dos peticiones a la vez sobre el mismo punto de emisión.** Esperado: secuenciales consecutivos y distintos, ninguna clave repetida. Test en Task 8.
- **Reintento con la misma `Idempotency-Key`.** Esperado: mismo documento y un solo secuencial consumido; con otro cuerpo, 409. Y dos envíos simultáneos con la misma clave producen un solo documento. Tests en Task 7.
- **Factura que falla una validación tardía (pagos que no cuadran, límite de consumidor final).** Esperado: no consume secuencial ni deja filas. Test en Task 6.
- **Importes con muchos decimales o en el borde del redondeo.** `0.005`, cantidades de 6 decimales, descuento igual al importe de la línea. Esperado: resultado reproducible y documentado. Tests en Task 4.
- **Id de otro tenant en el cuerpo de la factura** (emisor, cliente, producto). Esperado: 404 y nada creado. Test en Task 6.

## Estructura de archivos

```text
backend/src/main/resources/db/migration/V3__catalogs_billing.sql
backend/src/main/java/com/example/einvoice/
  tenancy/Role.java                      # + canBill()
  web/PageResponse.java                  # {items, page, size, total}
  web/Decimals.java                      # parseo y formato de cadenas decimales
  catalog/CatalogRepository.java         # lectura de identification_types, tax_rates, payment_methods
  catalog/CatalogController.java
  catalog/Customer.java, CustomerRepository.java, CustomerService.java, CustomerController.java
  catalog/Product.java, ProductRepository.java, ProductService.java, ProductController.java
  billing/InvoiceCalculator.java         # cálculo puro
  billing/InvoiceLineInput.java, InvoiceTotals.java, LineAmounts.java, TaxTotal.java
  billing/InvoiceService.java            # la transacción de emisión
  billing/InvoiceController.java, DocumentController.java
  billing/FiscalDocumentRepository.java  # JdbcClient
  billing/IdempotencyStore.java
  billing/OutboxWriter.java
  sequence/SequenceService.java, SequenceController.java
```

---

### Task 1: Esquema y catálogos

**Files:**
- Create: `V3__catalogs_billing.sql`, `catalog/CatalogRepository.java`, `catalog/CatalogController.java`
- Modify: `contracts/openapi.yaml`
- Test: `backend/src/test/java/com/example/einvoice/catalog/CatalogIT.java`, `BillingSchemaIT.java`

**Interfaces:**
- Produces:
  - Tablas `identification_types(code, name)`, `payment_methods(code, name, valid_from, valid_to)`, `tax_rates(tax_code, rate_code, description, rate, PRIMARY KEY (tax_code, rate_code))`, sembradas con los datos de la ficha.
  - Tablas `customers`, `products`, `document_sequences`, `fiscal_documents`, `fiscal_document_lines`, `fiscal_document_payments`, `state_events`, `idempotency_records`, `outbox_events`; y `UNIQUE (tenant_id, id)` en `emission_points`.
  - `CatalogRepository`: `Optional<BigDecimal> ivaRate(String rateCode)`, `boolean paymentMethodValidOn(String code, LocalDate date)`, `boolean identificationTypeExists(String code)` y los tres listados.
- API (cualquier usuario autenticado): `GET /api/v1/catalogs/identification-types`, `GET /api/v1/catalogs/payment-methods`, `GET /api/v1/catalogs/tax-rates` → listas `{code, name}`; las tarifas `{taxCode, rateCode, description, rate}` con `rate` como cadena.

- [ ] **Step 1: Escribir los tests**

`CatalogIT`: `identificationTypesMatchFicha` (exactamente `04, 05, 06, 07, 08`); `paymentMethodsMatchFicha` (exactamente `01, 15, 16, 17, 18, 19, 20, 21`); `ivaRatesMatchFicha` (pares código → tarifa: `0→0.00, 2→12.00, 3→14.00, 4→15.00, 5→5.00, 6→0.00, 7→0.00, 8→8.00, 10→13.00`); `catalogsRequireAuthentication` (401 sin token); `paymentMethodValidityFollowsDates` (`16` no es válida el 2016-05-31 y sí el 2016-06-01).

`BillingSchemaIT` (inserciones directas): `customerIdentificationIsUniquePerTenant`; `productSkuIsUniquePerTenant`; `documentCannotReferenceCustomerOfAnotherTenant`; `sequenceAndAccessKeyAreUnique` (mismo punto, ambiente, tipo y secuencial dos veces falla; misma clave dos veces falla); `idempotencyKeyIsUniquePerTenant`.

- [ ] **Step 2: Ejecutar y ver que fallan** — `./mvnw -B verify '-Dit.test=CatalogIT,BillingSchemaIT'`.
- [ ] **Step 3: Escribir la migración, el repositorio, el controlador y el contrato.**

Columnas que las tareas siguientes dan por hechas:

```sql
CREATE TABLE document_sequences (
 tenant_id uuid NOT NULL,
 emission_point_id uuid NOT NULL,
 environment varchar(1) NOT NULL CHECK (environment IN ('1','2')),
 document_type varchar(2) NOT NULL,
 next_value bigint NOT NULL DEFAULT 1 CHECK (next_value BETWEEN 1 AND 1000000000),
 last_issue_date date,
 PRIMARY KEY (emission_point_id, environment, document_type),
 FOREIGN KEY (tenant_id, emission_point_id) REFERENCES emission_points (tenant_id, id)
);
CREATE TABLE fiscal_documents (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenants(id),
 taxpayer_id uuid NOT NULL,
 emission_point_id uuid NOT NULL,
 customer_id uuid NOT NULL,
 document_type varchar(2) NOT NULL,
 schema_version varchar(12),
 environment varchar(1) NOT NULL CHECK (environment IN ('1','2')),
 issuer_ruc varchar(13) NOT NULL,
 establishment_code varchar(3) NOT NULL,
 emission_point_code varchar(3) NOT NULL,
 sequence integer NOT NULL CHECK (sequence BETWEEN 1 AND 999999999),
 access_key varchar(49) NOT NULL UNIQUE CHECK (access_key ~ '^[0-9]{49}$'),
 state varchar(40) NOT NULL,
 issue_date date NOT NULL,
 currency varchar(3) NOT NULL,
 customer_id_type varchar(2) NOT NULL,
 customer_id_number varchar(20) NOT NULL,
 customer_name text NOT NULL,
 customer_address text,
 customer_email text,
 subtotal numeric(14,2) NOT NULL,
 total_discount numeric(14,2) NOT NULL,
 total_tax numeric(14,2) NOT NULL,
 total numeric(14,2) NOT NULL,
 created_by uuid NOT NULL REFERENCES users(id),
 created_at timestamptz NOT NULL DEFAULT now(),
 version bigint NOT NULL DEFAULT 0,
 FOREIGN KEY (tenant_id, taxpayer_id) REFERENCES taxpayers (tenant_id, id),
 FOREIGN KEY (tenant_id, emission_point_id) REFERENCES emission_points (tenant_id, id),
 FOREIGN KEY (tenant_id, customer_id) REFERENCES customers (tenant_id, id),
 UNIQUE (emission_point_id, environment, document_type, sequence),
 UNIQUE (tenant_id, id)
);
```

El resto (`customers`, `products`, líneas, pagos, `state_events`, `idempotency_records (tenant_id, key, request_hash, document_id, created_at, PRIMARY KEY (tenant_id, key))`, `outbox_events (id, tenant_id, aggregate_id, event_type, payload jsonb, created_at, dispatched_at)`) sigue el doc 04 con `tenant_id` y claves foráneas compuestas hacia su padre.

- [ ] **Step 4: Ejecutar y ver que pasan** — `./mvnw -B verify`.
- [ ] **Step 5: Commit** — `feat(catalog): esquema de facturación y catálogos de la ficha técnica`

---

### Task 2: Clientes

**Files:**
- Create: `web/PageResponse.java`, `catalog/Customer.java`, `CustomerRepository.java`, `CustomerService.java`, `CustomerController.java`
- Modify: `tenancy/Role.java`, `tenancy/TenantAccess.java` (añade `requireBilling()`), `contracts/openapi.yaml`, `support/AbstractIntegrationTest.java` (helper `createCustomer`)
- Test: `catalog/CustomerApiIT.java`

**Interfaces:**
- Produces: `Role.canBill()` (`OWNER`, `ADMIN`, `BILLER`); `TenantAccess.requireBilling()` (403 `ROLE_FORBIDDEN`); `record PageResponse<T>(List<T> items, int page, int size, long total)`; `CustomerService.get(TenantAccess, UUID): Customer`.
- API:
  - `POST /api/v1/customers` cuerpo `{"idType","idNumber","name","email"?,"address"?,"phone"?}` → `201`. `name` 1 a 300; `address` hasta 300; `phone` hasta 30; `email` válido hasta 254. `idType` debe existir en el catálogo (`422 IDENTIFICATION_TYPE_UNKNOWN`). Formato de `idNumber` según el tipo (`422 IDENTIFICATION_INVALID`). Duplicado en el tenant → `409 CUSTOMER_ALREADY_EXISTS`. Auditoría `CUSTOMER_CREATED` sin datos personales en `metadata`.
  - `GET /api/v1/customers?page=0&size=20&q=` → `PageResponse`; `q` busca en nombre (sin distinguir mayúsculas) o identificación (prefijo). `size` máximo 100, por defecto 20; fuera de rango → `400`.
  - `GET /api/v1/customers/{id}` → `200` o `404`.

- [ ] **Step 1: Escribir `CustomerApiIT`** — `billerCreatesCustomer`; `auditorCannotCreate`; `rejectsUnknownIdentificationType` (`09`); `validatesIdentificationByType` (RUC de 12, cédula de 9, consumidor final distinto de trece nueves, pasaporte de 2 caracteres o con símbolos → 422; válidos de cada tipo → 201); `duplicateIdentificationIsConflict`; `sameIdentificationInAnotherTenantIsAllowed`; `searchesAndPaginates` (25 clientes: página 0 trae 20 y `total` 25; `q` por nombre y por prefijo de identificación); `rejectsPageSizeOutOfRange` (`size=0`, `size=101`, `page=-1`); `customerOfAnotherTenantIsNotFound`; `auditMetadataHasNoPersonalData`.
- [ ] **Step 2: Ejecutar y ver que falla.**
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Ejecutar y ver que pasa** — `./mvnw -B verify`.
- [ ] **Step 5: Commit** — `feat(catalog): clientes con identificación validada por tipo`

---

### Task 3: Productos

**Files:**
- Create: `web/Decimals.java`, `catalog/Product.java`, `ProductRepository.java`, `ProductService.java`, `ProductController.java`
- Modify: `contracts/openapi.yaml`, `support/AbstractIntegrationTest.java` (helper `createProduct`)
- Test: `catalog/ProductApiIT.java`, `web/DecimalsTest.java`

**Interfaces:**
- Produces:
  - `Decimals.parse(String value, int maxIntegerDigits, int maxFractionDigits): BigDecimal` — acepta solo `[0-9]+(\.[0-9]+)?`, lanza `IllegalArgumentException` en cualquier otro caso (signos, exponentes, comas, espacios, exceso de dígitos); `Decimals.format(BigDecimal value, int scale): String` — notación plana con exactamente `scale` decimales.
  - `ProductService.get(TenantAccess, UUID): Product` con `getSku()`, `getName()`, `getUnitPrice()`, `getIvaRateCode()`.
- API:
  - `POST /api/v1/products` cuerpo `{"sku","name","unitPrice","ivaRateCode"}` → `201` con `unitPrice` a 6 decimales. `sku` `[A-Za-z0-9._-]{1,25}`; `name` 1 a 300; `unitPrice` cadena decimal con hasta 12 enteros y 6 decimales; `ivaRateCode` debe existir en `tax_rates` para el impuesto `2` (`422 TAX_RATE_UNKNOWN`). Requiere rol que escribe. Duplicado → `409 PRODUCT_SKU_TAKEN`. Auditoría `PRODUCT_CREATED`.
  - `GET /api/v1/products?page=&size=&q=` (busca en nombre o `sku`) y `GET /api/v1/products/{id}`.

- [ ] **Step 1: Escribir los tests** — `DecimalsTest`: acepta `"0"`, `"10.50"`, `"0.000001"`; rechaza `"-1"`, `"+1"`, `"1e3"`, `"1,5"`, `" 1"`, `"1."`, `".5"`, `""`, `null`, 13 enteros, 7 decimales; `format(new BigDecimal("2.5"), 2)` es `"2.50"`; `format(new BigDecimal("1E+3"), 2)` es `"1000.00"`. `ProductApiIT`: `adminCreatesProduct` (precio `"10.5"` se devuelve `"10.500000"`); `billerCannotCreateProduct`; `rejectsInvalidPrice` (número JSON en vez de cadena, negativo, 7 decimales); `rejectsUnknownIvaRate` (`"9"`); `duplicateSkuIsConflict`; `searchesAndPaginates`; `productOfAnotherTenantIsNotFound`.
- [ ] **Step 2: Ejecutar y ver que falla.**
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Ejecutar y ver que pasa** — `./mvnw -B verify`.
- [ ] **Step 5: Commit** — `feat(catalog): productos con precio decimal y tarifa de IVA del catálogo`

---

### Task 4: Cálculo de la factura

**Files:**
- Create: `billing/InvoiceLineInput.java`, `LineAmounts.java`, `TaxTotal.java`, `InvoiceTotals.java`, `InvoiceCalculator.java`
- Test: `backend/src/test/java/com/example/einvoice/billing/TaxRoundingTest.java`

**Interfaces:**
- Produces (todo sin Spring ni base de datos):
  - `record InvoiceLineInput(BigDecimal quantity, BigDecimal unitPrice, BigDecimal discount, String ivaRateCode, BigDecimal ivaRate)`.
  - `record LineAmounts(BigDecimal subtotal, BigDecimal taxAmount)`.
  - `record TaxTotal(String taxCode, String rateCode, BigDecimal rate, BigDecimal base, BigDecimal amount)`.
  - `record InvoiceTotals(List<LineAmounts> lines, BigDecimal subtotal, BigDecimal totalDiscount, List<TaxTotal> taxes, BigDecimal totalTax, BigDecimal total)`.
  - `InvoiceCalculator.calculate(List<InvoiceLineInput> lines): InvoiceTotals`.
- Reglas: subtotal de línea = `(cantidad × precio − descuento)` redondeado a 2; impuesto de línea = subtotal × tarifa / 100 redondeado a 2; por cada `rateCode`, base = suma de subtotales y valor = base × tarifa / 100 redondeado a 2 (tabla 21 de la ficha); `totalTax` = suma de los valores por tarifa; `total` = subtotal + `totalTax`. Los grupos salen ordenados por `rateCode` numérico. Lanza `IllegalArgumentException` si no hay líneas, la cantidad no es positiva, el precio o el descuento son negativos, o el descuento supera `cantidad × precio`.

- [ ] **Step 1: Escribir `TaxRoundingTest`**

| Test | Entrada | Esperado |
|---|---|---|
| `singleLineAtFifteenPercent` | 2 × 10.00, descuento 0, código `4` (15) | subtotal `20.00`, IVA `3.00`, total `23.00` |
| `ivaIsComputedOnTheGroupBaseNotPerLine` | tres líneas de 1 × 0.03 al 15 % | impuesto por línea `0.00` cada una; grupo: base `0.09`, valor `0.01`; total `0.10` |
| `halfCentRoundsUp` | 1 × 0.005 al 0 % | subtotal `0.01` |
| `sixDecimalQuantityAndPrice` | 1.234567 × 9.876543 al 12 % | subtotal `12.19`, IVA `1.46`, total `13.65` |
| `discountReducesTheBase` | 3 × 10.00, descuento 5.00, 15 % | subtotal `25.00`, `totalDiscount` `5.00`, IVA `3.75`, total `28.75` |
| `discountEqualToLineAmountGivesZero` | 1 × 10.00, descuento 10.00 | subtotal `0.00`, total `0.00` |
| `mixedRatesAreGroupedAndOrdered` | líneas con códigos `4`, `0`, `4`, `10` | grupos en orden `0`, `4`, `10`, cada uno con su base y valor |
| `zeroRatedCodesProduceNoTax` | códigos `0`, `6`, `7` | `totalTax` `0.00` |
| `rejectsInvalidLines` | lista vacía; cantidad 0; precio negativo; descuento negativo; descuento mayor que el importe | `IllegalArgumentException` |
| `resultsAlwaysHaveTwoDecimals` | cualquier caso anterior | todas las escalas de importes son 2 |

- [ ] **Step 2: Ejecutar y ver que falla** — `./mvnw -B test -Dtest=TaxRoundingTest`.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Ejecutar y ver que pasa.**
- [ ] **Step 5: Commit** — `feat(billing): cálculo de factura con BigDecimal y IVA por tarifa`

---

### Task 5: Secuenciales

**Files:**
- Create: `sequence/SequenceService.java`, `sequence/SequenceController.java`
- Modify: `contracts/openapi.yaml`
- Test: `backend/src/test/java/com/example/einvoice/sequence/SequenceServiceIT.java`, `SequenceApiIT.java`

**Interfaces:**
- Produces: `SequenceService.reserve(UUID tenantId, UUID emissionPointId, String environment, String documentType, LocalDate issueDate): int` con `@Transactional(propagation = MANDATORY)`. Crea la fila si no existe, la bloquea con `FOR UPDATE`, valida y avanza. Lanza `ApiException` `422 SEQUENCE_EXHAUSTED` si `next_value` es 1000000000 y `422 INVALID_ISSUE_DATE` si `issueDate` es anterior a `last_issue_date`.
- API: `PUT /api/v1/emission-points/{id}/sequence` cuerpo `{"environment","documentType","nextValue"}` → `200` `{"emissionPointId","environment","documentType","nextValue"}`. Rol que escribe. `documentType` solo `01`; `nextValue` 1 a 999999999. Si ya existe un comprobante para ese punto, ambiente y tipo → `409 SEQUENCE_ALREADY_USED`. Auditoría `SEQUENCE_SET`.

- [ ] **Step 1: Escribir los tests** — `SequenceServiceIT` (llama al servicio dentro de `TransactionTemplate`): `startsAtOneAndIncrements`; `countersAreIndependentPerEnvironmentAndPoint`; `rollbackReleasesTheNumber` (reservar y revertir; la siguiente reserva confirma el mismo número); `concurrentReservationsNeverRepeat` (8 hilos × 25 reservas → 200 valores distintos, exactamente 1..200); `rejectsEarlierIssueDate`; `exhaustedSequenceIsRejected` (fila con `next_value = 1000000000`); `requiresAnOpenTransaction`. `SequenceApiIT`: `ownerSetsInitialSequence` (luego la primera reserva devuelve ese valor); `billerCannotSetSequence`; `rejectsOutOfRangeAndUnknownType`; `emissionPointOfAnotherTenantIsNotFound`.
- [ ] **Step 2: Ejecutar y ver que falla.**
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Ejecutar y ver que pasa** — `./mvnw -B verify`.
- [ ] **Step 5: Commit** — `feat(sequence): reserva de secuenciales con bloqueo de fila`

---

### Task 6: Emisión de factura

**Files:**
- Create: `billing/FiscalDocumentRepository.java`, `OutboxWriter.java`, `InvoiceService.java`, `InvoiceController.java`, `DocumentController.java`
- Modify: `contracts/openapi.yaml`, `TenantIsolationIT.java`, `support/AbstractIntegrationTest.java` (helpers `invoiceJson`, `issueInvoice`)
- Test: `billing/InvoiceApiIT.java`

**Interfaces:**
- Consumes: `InvoiceCalculator`, `SequenceService.reserve`, `AccessKeyParts`, `NumericCodeGenerator`, `FiscalClock`, `CatalogRepository`, `CustomerService.get`, `ProductService.get`, `TaxpayerService.get`.
- API:
  - `POST /api/v1/invoices` con cabecera `Idempotency-Key` y cuerpo `{"taxpayerId","establishmentCode","emissionPointCode","customerId","issueDate"?,"currency"?,"lines":[{"productId","quantity","unitPrice"?,"discount"?}],"payments":[{"methodCode","amount"}]}` → `202` `{"id","state":"RESERVED","statusUrl":"/api/v1/documents/{id}"}`. Rol que factura. De 1 a 500 líneas y de 1 a 20 pagos. `unitPrice` por defecto el del producto; `discount` por defecto `0.00`.
  - `GET /api/v1/documents/{id}` → documento completo: `id`, `documentType`, `state`, `environment`, `taxpayerId`, `number` (`001-002-000000123`), `sequence` (9 dígitos), `accessKey`, `issueDate`, `currency`, `customer {idType, idNumber, name}`, `subtotal`, `totalDiscount`, `taxes [{taxCode, rateCode, rate, base, amount}]`, `totalTax`, `total`, `lines [...]`, `payments [...]`. Importes como cadenas.
  - `GET /api/v1/documents?page=&size=` → `PageResponse` de resúmenes ordenados por creación descendente.
- Errores: emisor, establecimiento, punto, cliente o producto inexistente o ajeno → `404`. `422` con `errorCode`: `UNSUPPORTED_CURRENCY`, `INVALID_ISSUE_DATE` (futura o anterior al último comprobante del punto), `EMISSION_POINT_DISABLED`, `INVALID_LINE` (reglas del calculador), `PAYMENT_METHOD_UNKNOWN`, `PAYMENTS_MISMATCH`, `FINAL_CONSUMER_LIMIT_EXCEEDED` (cliente `07` y total mayor a `50.00`), `SEQUENCE_EXHAUSTED`.
- Efectos en la misma transacción: fila en `fiscal_documents` con sus líneas y pagos, `state_events` (`NULL → RESERVED`), `audit_events` (`INVOICE_RESERVED`), `outbox_events` (`event_type = 'INVOICE_RESERVED'`, `payload = {"documentId": "..."}`).

- [ ] **Step 1: Escribir `InvoiceApiIT`**

| Test | Esperado |
|---|---|
| `billerIssuesInvoice` | 202; `GET` del documento: estado `RESERVED`, `number` `001-001-000000001`, `total` `23.00` para 2 × 10.00 al 15 %, clave de 49 dígitos válida según `AccessKey.valid` y coherente con fecha, RUC, ambiente, códigos y secuencial |
| `secondInvoiceGetsNextSequence` | secuenciales 1 y 2, claves distintas |
| `writesStateEventAuditAndOutbox` | una fila de cada uno; el `payload` del outbox solo contiene `documentId` |
| `auditorCannotIssue` | 403 `ROLE_FORBIDDEN` |
| `requiresIdempotencyKey` | sin cabecera → 400 `IDEMPOTENCY_KEY_REQUIRED`; con espacios o más de 100 caracteres → 400 |
| `usesProductPriceWhenLineOmitsIt` y `lineCanOverridePriceAndDiscount` | importes correspondientes |
| `snapshotSurvivesLaterChanges` | tras `UPDATE` directo del nombre del cliente y del precio del producto, el documento devuelve los valores originales |
| `rejectsFutureIssueDate`, `rejectsIssueDateBeforeLastDocument` | 422 `INVALID_ISSUE_DATE` |
| `rejectsUnsupportedCurrency` | `EUR` → 422 |
| `rejectsUnknownPaymentMethod` | `99` → 422 |
| `rejectsPaymentsThatDoNotAddUp` | 422 `PAYMENTS_MISMATCH`; **`next_value` del contador no cambia y no hay documento** |
| `finalConsumerAboveFiftyIsRejected` | total `50.01` → 422; total `50.00` → 202 |
| `rejectsInvalidLines` | cantidad `0`, descuento mayor que el importe, sin líneas, 501 líneas |
| `unknownReferencesAreNotFound` | emisor, código de establecimiento, código de punto, cliente y producto inexistentes → 404 |
| `disabledEmissionPointIsRejected` | `UPDATE emission_points SET enabled = false` → 422 |
| `listsDocumentsOfTheTenant` | paginado, más reciente primero |

`TenantIsolationIT` (añadir): `cannotIssueWithForeignTaxpayerCustomerOrProduct` (tres peticiones, cada una 404 y sin documento nuevo); `documentOfAnotherTenantIsNotFound`.

- [ ] **Step 2: Ejecutar y ver que falla.**
- [ ] **Step 3: Implementar.** Orden dentro de la transacción: rol → referencias (404) → fecha y moneda → cálculo → pagos y límite de consumidor final → reserva de secuencial → clave de acceso → inserciones. Toda validación que pueda fallar va antes de reservar.
- [ ] **Step 4: Ejecutar y ver que pasa** — `./mvnw -B verify`.
- [ ] **Step 5: Commit** — `feat(billing): emisión de factura hasta RESERVED con outbox`

---

### Task 7: Idempotencia

**Files:**
- Create: `billing/IdempotencyStore.java`
- Modify: `billing/InvoiceService.java`
- Test: `billing/InvoiceIdempotencyIT.java`

**Interfaces:**
- Produces: `IdempotencyStore.begin(UUID tenantId, String key, String requestHash): Optional<UUID>` (`MANDATORY`): inserta `(tenant, key, hash)`; si ya existía con el mismo hash devuelve su `document_id`; si existía con otro hash lanza `409 IDEMPOTENCY_KEY_REUSED`. `complete(UUID tenantId, String key, UUID documentId)`.
- El hash es SHA-256 del cuerpo ya interpretado y serializado de forma canónica, de modo que el orden de las claves o los espacios del JSON original no lo cambian.

- [ ] **Step 1: Escribir `InvoiceIdempotencyIT`**

| Test | Esperado |
|---|---|
| `sameKeyAndBodyReturnsTheSameDocument` | dos 202 con el mismo `id`; un solo documento; el contador avanzó una vez |
| `reorderedJsonIsTheSameRequest` | mismo `id` con las claves del cuerpo en otro orden |
| `sameKeyWithDifferentBodyIsConflict` | 409 `IDEMPOTENCY_KEY_REUSED`; un solo documento |
| `sameKeyInAnotherTenantIsIndependent` | dos documentos distintos |
| `failedRequestDoesNotBurnTheKey` | primer envío 422; segundo con la misma clave y cuerpo corregido → 202 |
| `concurrentRequestsWithTheSameKeyCreateOneDocument` | 8 hilos, 10 rondas: en cada ronda todos reciben 202 con el mismo `id` y hay un solo documento |

- [ ] **Step 2: Ejecutar y ver que falla** — el segundo envío crea otro documento.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4: Ejecutar y ver que pasa** — `./mvnw -B verify`.
- [ ] **Step 5: Commit** — `feat(billing): idempotencia de la emisión por Idempotency-Key`

---

### Task 8: Concurrencia de extremo a extremo y documentación

**Files:**
- Modify: `docs/04-database-design.md`, `docs/05-electronic-document-flow.md`, `docs/11-api-specification.md`, `docs/02-sri-compliance.md`, `README.md`, `docs/superpowers/plans/2026-10-10-plan-maestro.md`
- Test: `backend/src/test/java/com/example/einvoice/billing/SequenceConcurrencyIT.java`

- [ ] **Step 1: Escribir `SequenceConcurrencyIT`** — 4 hilos emiten 100 facturas en total sobre el mismo punto de emisión, cada una con su propia `Idempotency-Key`. Esperado: 100 respuestas 202; secuenciales exactamente 1..100; 100 claves de acceso distintas y todas válidas; 100 eventos en `outbox_events`; `next_value` = 101.
- [ ] **Step 2: Ejecutar** — `./mvnw -B verify -Dit.test=SequenceConcurrencyIT`. Si falla, el defecto está en Tasks 5 a 7 y se corrige allí con su propio test.
- [ ] **Step 3: Comprobar que el test detecta el fallo** — quitar temporalmente `FOR UPDATE` de `SequenceService` y verlo fallar; restaurar.
- [ ] **Step 4: Actualizar la documentación**

- Doc 04: tablas reales de `V3`, contador por punto de emisión, datos congelados en el documento.
- Doc 05: qué deja `POST /invoices` en la base y qué falta (publicador del outbox, XML, firma, SRI).
- Doc 11: rutas implementadas, roles, códigos de error, cadenas decimales, regla de fecha y de consumidor final.
- Doc 02: los catálogos sembrados y su origen (ficha v2.34, tablas 6, 16, 17 y 24); el límite de 50 USD.
- README y plan maestro: estado del Plan 3 y tarea N2 parcialmente resuelta.

- [ ] **Step 5: Commit** — `test(billing): concurrencia de secuenciales de extremo a extremo y documentación`

---

## Fuera de este plan

- ICE, IRBPNR, propina, reembolsos, exportación, subsidios, información adicional y campos de RIMPE o agente de retención.
- Edición y baja de clientes y productos; borradores (`DRAFT`); anulación.
- Publicador del outbox hacia RabbitMQ, XML, firma y SRI (Planes 4 y 5).
- Validación del dígito verificador de RUC y cédula; consulta del catastro del SRI.
- Límite de días hacia atrás para la fecha de emisión.
