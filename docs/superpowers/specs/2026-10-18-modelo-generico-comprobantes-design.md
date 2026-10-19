# Modelo genérico de comprobante — diseño

Fecha: 2026-10-18 · Rama: `p12b-modelo-comprobantes` (solo `api-emite-facil`), salida de
`p12-xsd-comprobantes` (PR #13). Parte 2 de 6 del trabajo acordado con Stiwar para emitir liquidación de
compra (03), nota de crédito (04), nota de débito (05), guía de remisión (06) y comprobante de retención
(07).

## Objetivo

Que la emisión deje de suponer que todo comprobante es una factura. Hoy la reserva del secuencial, la
generación del XML, el RIDE, el correo de entrega y la descarga están cableados a la factura, y
`fiscal_documents` exige un cliente y totales con impuestos. Al terminar esta parte, cada tipo de
comprobante se añade como una pieza (`DocumentKind`) sin tocar la emisión común, y la factura ya pasa por
ese camino.

Esta parte **no habilita ningún tipo nuevo**. Cada tipo llega en su propio PR (parte 3), con su endpoint,
su contenido, su XML, su RIDE y su pantalla.

## Decisiones tomadas

| Tema | Decisión |
|---|---|
| Documento modificado (notas de crédito y débito) | Ambos: enlace opcional a una factura de la plataforma (la nota de crédito no puede superar lo que queda de ella) o número y fecha escritos a mano |
| Terceros | Un solo catálogo: `customers` sirve como comprador, proveedor, sujeto retenido, transportista o destinatario; la web lo llamará «Clientes y proveedores» (parte 3) |
| Opcionales de los XSD que entran (parte 3) | Reembolsos (liquidación y retención), retención de dividendos, pagos al exterior |
| Opcionales que quedan fuera | Compra de banano, compensaciones, máquina fiscal |
| Versiones que se emitirán | Factura 1.1.0 (sin cambios), liquidación 1.1.0, nota de crédito 1.1.0, nota de débito 1.0.0, guía 1.1.0, retención 2.0.0 |
| Almacenamiento | Sobre común relacional en `fiscal_documents` + contenido de cada tipo congelado en `content jsonb`; la factura conserva sus tablas |
| Validación del XML | Se genera y valida durante la emisión, para todos los tipos incluida la factura: un XML inválido responde 422 y no consume secuencial |
| Nombres en el contrato | `customer` se conserva (significa «receptor»); renombrarlo es un cambio aparte coordinado con la web |

Fuera de alcance: endpoints `POST` de los tipos nuevos, su contenido y su RIDE (parte 3); etiquetas de
la web; migrar las tablas de la factura a `content`.

## 1. Arquitectura

### `DocumentKind`: un tipo de comprobante como pieza

Interfaz en el paquete `billing` con una implementación por tipo habilitado:

```java
public interface DocumentKind<C> {
	DocumentType type();                       // 01, 03, 04, 05, 06 o 07
	DocumentSchema schema();                   // la versión del XSD que se emite
	Class<C> contentType();                    // record del contenido del tipo
	String displayName();                      // «Factura», «Nota de crédito»… (correo, PDF, descarga)
	String fileStem();                         // «factura», «nota-credito»… (nombre del archivo)
	String auditReserved();                    // INVOICE_RESERVED, CREDIT_NOTE_RESERVED…
	void saveContent(UUID tenantId, UUID documentId, C content);   // por omisión: columna content
	C loadContent(UUID tenantId, UUID documentId);                 // por omisión: columna content
	byte[] buildXml(DocumentEnvelope envelope, C content);
	byte[] buildRide(RideEnvelope ride, C content);
}
```

`saveContent` y `loadContent` tienen una implementación por omisión que escribe y lee la columna
`content` con Jackson. La factura la sustituye: su contenido es `InvoiceBody` (líneas, impuestos por
tarifa y pagos) y lo guarda y lee de sus tablas de siempre.

- `DocumentEnvelope` es el sobre congelado común: ambiente, emisor (razón social, nombre comercial, RUC,
  dirección matriz y de establecimiento, contabilidad, contribuyente especial, agente de retención,
  régimen RIMPE), clave de acceso, tipo, establecimiento, punto, secuencial, fecha de emisión, receptor
  (tipo e identificación, nombre, dirección, correo), totales (pueden ser nulos), documento modificado
  (puede ser nulo) y RUC del proveedor del sistema. Es `InvoiceXmlData` sin las líneas, los impuestos y
  los pagos.
- `RideEnvelope` añade al sobre el número y la fecha de autorización.
- La factura (`InvoiceKind`) junta el sobre y su `InvoiceBody` en el `InvoiceXmlData` de hoy y delega
  en `InvoiceXmlBuilder` y `RidePdfBuilder` sin cambiarlos. Su XML sigue saliendo byte a
  byte igual (`InvoiceXmlBuilderTest` ya lo compara).
- `DocumentKinds` (componente) reúne las implementaciones por `DocumentType`; `find(type)` devuelve un
  `Optional`. Arrancar con dos implementaciones del mismo tipo es un error de configuración.

### Emisión común: `DocumentIssuer`

Hoy `InvoiceService.issue` hace todo. Lo que no depende del tipo pasa a `DocumentIssuer.issue(access,
idempotencyKey, requestHash, IssueRequest, Preparation)`:

1. Idempotencia (`IdempotencyStore`) y reemisión (`requireReplaceable`, sin cambios).
2. Emisor, punto de emisión habilitado, moneda USD, fecha de emisión no futura.
3. El tipo prepara su parte (ver abajo) **antes** de reservar, como hoy.
4. Reserva del secuencial y clave de acceso con el código del tipo.
5. Generación y validación del XML con el secuencial y la clave reales (sección 3); si falla, 422 y la
   transacción se revierte.
6. Inserción del sobre (y de las tablas propias de la factura, o de `content`), evento de estado
   `RESERVED`, auditoría con `auditReserved()`, outbox `DOCUMENT_RESERVED`, enlace de reemisión,
   programación de respaldo y cierre de la idempotencia.

Cada tipo aporta un `Preparation`: el receptor (un registro del catálogo de clientes y proveedores), los
totales (o nulo), el documento modificado (o nulo) y el contenido (para la factura, su `InvoiceBody`). `InvoiceService` queda con lo propio de la
factura (productos, cálculo, pagos, límite de consumidor final) y llama a `DocumentIssuer`.
`POST /invoices` no cambia por fuera.

### Worker

`DocumentProcessor.generate` busca el `DocumentKind` por `document_type`, lee el sobre y el contenido,
genera el XML con `buildXml`, lo valida contra `kind.schema()` y las reglas del sobre, y guarda
`schema_version` con la versión del tipo. La firma, el envío al SRI, los reintentos, la entrega y los
webhooks no cambian; el RIDE y el correo toman `buildRide`, `displayName` y `fileStem` del tipo.

## 2. Datos (migración V18)

La rama `dev` ya tiene `V17__catalog_soft_deletion.sql`; esta es `V18__generic_documents.sql`.

```sql
ALTER TABLE fiscal_documents
 ADD CONSTRAINT fiscal_documents_type_check CHECK (document_type IN ('01','03','04','05','06','07')),
 ALTER COLUMN subtotal DROP NOT NULL,
 ALTER COLUMN total_discount DROP NOT NULL,
 ALTER COLUMN total_tax DROP NOT NULL,
 ALTER COLUMN total DROP NOT NULL,
 ADD COLUMN content jsonb,
 ADD COLUMN modified_document_id uuid,
 ADD COLUMN modified_doc_code varchar(2) CHECK (modified_doc_code ~ '^[0-9]{2}$'),
 ADD COLUMN modified_doc_number varchar(17) CHECK (modified_doc_number ~ '^[0-9]{3}-[0-9]{3}-[0-9]{9}$'),
 ADD COLUMN modified_doc_date date;
```

Más las restricciones:

- Solo la guía de remisión va sin total: `CHECK ((document_type = '06') = (total IS NULL))`.
- La factura no lleva `content` y los demás sí: `CHECK ((document_type = '01') = (content IS NULL))`.
- El documento modificado va completo o no va: `modified_doc_code`, `modified_doc_number` y
  `modified_doc_date` son los tres nulos o los tres no nulos; `modified_document_id` solo puede existir
  si los tres existen.
- `FOREIGN KEY (tenant_id, modified_document_id) REFERENCES fiscal_documents (tenant_id, id)` y
  `CHECK (modified_document_id <> id)`.
- Índice parcial `idx_fiscal_documents_modified (modified_document_id) WHERE modified_document_id IS NOT
  NULL`, para sumar las notas de crédito de una factura.

Las columnas `customer_*` conservan su nombre y pasan a significar «receptor»: comprador en factura y
notas, proveedor en la liquidación, sujeto retenido en la retención, transportista en la guía. Un
comentario en la migración y el doc 04 lo dicen. `customer_id` sigue siendo obligatorio: con un solo
catálogo de terceros, todo receptor sale de él. En la retención, `total` es el total retenido.

`content` lo escribe Jackson desde el record del tipo y no se modifica: no hay `UPDATE` sobre esa
columna en el repositorio. Sus campos van en camelCase y los importes como cadenas decimales, para no
perder escala.

Nada cambia en `fiscal_document_lines`, `fiscal_document_taxes`, `fiscal_document_payments`,
`document_artifacts`, `sri_attempts`, `deliveries` ni en las tablas de webhooks: cuelgan del documento y
valen para cualquier tipo.

### Outbox y auditoría

- La emisión publica `DOCUMENT_RESERVED` para todos los tipos; `MessagingConfig.routingKey` ya lo manda
  a la cola de emisión. Los `INVOICE_RESERVED` que queden en el outbox o en la cola durante el despliegue
  se siguen procesando igual: el worker no mira el nombre del evento.
- La auditoría usa `auditReserved()`: la factura sigue con `INVOICE_RESERVED`, así el historial no
  cambia de nombre.

### API

- `GET /documents` admite `documentType` (`01`…`07`, opcional); uno desconocido responde 400. El resumen
  y la vista ya llevan `documentType`.
- La descarga de artefactos nombra el archivo con `fileStem()` (hoy: `factura-` o `comprobante-`).
- Un total nulo (guía de remisión) sale como `null` en el resumen, en la vista y en el cuerpo de los
  webhooks; los subtotales nulos, igual.
- Contrato (`contracts/openapi.yaml`): el parámetro nuevo y el enum de `documentType`. La web no
  necesita cambios; actualiza su copia del contrato cuando le toque.

## 3. Validación del XML al emitir

Dentro de la transacción de la emisión, después de reservar el secuencial y calcular la clave de acceso,
`DocumentIssuer` arma el sobre como quedará guardado, llama a `kind.buildXml`, y comprueba el tamaño
máximo (320 KB), el XSD de `kind.schema()` y las reglas del sobre. Si algo falla:

- responde `422 DOCUMENT_XML_INVALID` con los mensajes (truncados a 500 caracteres, como el motivo de
  `XSD_INVALID`);
- la transacción se revierte: no queda documento, el contador no avanza y la clave de idempotencia queda
  libre para reintentar con datos corregidos.

El XML de esta comprobación no se guarda: el worker lo vuelve a generar desde los datos congelados, igual
que hoy, y sale idéntico porque depende solo de ellos. La comprobación vive en una clase
(`DocumentXmlCheck`) que usan tanto `DocumentIssuer` como `DocumentProcessor`.

### Reglas del sobre generalizadas

`InvoiceEnvelopeRules` pasa a `EnvelopeRules.check(xml, DocumentSchema)`: elemento raíz del tipo
(`factura`, `liquidacionCompra`, `notaCredito`, `notaDebito`, `guiaRemision`, `comprobanteRetencion`),
`id="comprobante"`, `version` igual a la del esquema, `codDoc` igual al del tipo, clave de acceso válida y
coherente con el resto de `infoTributaria`, y sin `DOCTYPE` ni `schemaLocation`. El nombre del elemento
raíz lo da `DocumentType`.

## 4. Errores

| Situación | Resultado |
|---|---|
| XML inválido al emitir | 422 `DOCUMENT_XML_INVALID`, sin documento ni secuencial consumido |
| XML inválido al regenerarlo en el worker (cambió una regla entre la emisión y el proceso) | `XSD_INVALID`, como hoy |
| Documento de un tipo sin `DocumentKind` | `XSD_INVALID` con motivo `DOCUMENT_TYPE_NOT_SUPPORTED` |
| `content` que no se puede leer | Error en el registro y `XSD_INVALID` con motivo `CONTENT_UNREADABLE` |
| `documentType` desconocido en el filtro | 400 |

## 5. Pruebas

- Todas las pruebas actuales de la factura pasan sin cambios en lo que comprueban: XML byte a byte, firma,
  SRI simulado, entrega, webhooks, reemisión, e2e.
- `DocumentIssuerIT`: un comprobante cuyo XML no pasa el XSD responde 422, `document_sequences` no
  avanza, no queda fila en `fiscal_documents` ni en el outbox, y la misma clave de idempotencia funciona
  después. Si no hay una factura alcanzable desde la API que la validación Java deje pasar y el XSD
  rechace, se usa un `DocumentKind` de prueba (en `src/test`) cuyo XML es inválido.
- `GenericDocumentsMigrationIT`: los `CHECK` de V18 (tipos admitidos, solo la guía sin total, `content`
  según el tipo, documento modificado completo o ausente) y la clave foránea del
  documento modificado dentro del mismo tenant.
- `DocumentKindsTest`: la factura está registrada; un tipo sin implementación devuelve vacío; dos
  implementaciones del mismo tipo fallan al construir el registro.
- `EnvelopeRulesTest`: con los XML de ejemplo de cada tipo, el elemento raíz y el `codDoc` correctos se
  aceptan y los incorrectos se rechazan.
- `DocumentProcessingIT`: un documento con tipo sin `DocumentKind` termina en `XSD_INVALID` con su
  motivo.
- `OpenApiContractIT` y una prueba del filtro `documentType` en la API de documentos.

## 6. Documentación

- Doc 04: columnas nuevas, significado de `customer_*` como receptor, `content`.
- Doc 05: la validación del XML pasa a la emisión; `DOCUMENT_RESERVED`.
- Doc 11: filtro `documentType`, error `DOCUMENT_XML_INVALID`.
- Doc 14: qué versión emite cada tipo y que la habilitación de cada uno llega con su PR.
