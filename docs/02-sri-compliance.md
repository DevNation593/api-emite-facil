# 02 — Matriz normativa y cumplimiento SRI

## Fuentes base
La página del SRI publica **Ficha Técnica de Comprobantes Electrónicos Esquema Off-line v2.34, julio de 2026**, y los esquemas XSD/XML por documento. Los comprobantes del ambiente de certificación **no** tienen validez tributaria. La transición a producción requiere que el emisor cumpla las condiciones del SRI y efectúe las pruebas correspondientes.

## Comprobantes y esquemas publicados
| Código | Tipo | Versiones publicadas en portal del SRI |
|---|---|---|
| 01 | Factura | 1.0.0, 1.1.0, 2.0.0, 2.1.0 |
| 03 | Liquidación de compras | 1.0.0, 1.1.0 |
| 04 | Nota de crédito | 1.0.0, 1.1.0 |
| 05 | Nota de débito | 1.0.0 |
| 06 | Guía de remisión | 1.0.0, 1.1.0 |
| 07 | Comprobante de retención | 1.0.0, 2.0.0 |

El portal publica además, por separado, los esquemas XSD de notas de crédito y débito con el campo Devolución IVA (actualizado a diciembre 2020); revisarlos al abordar esos comprobantes.

**Importante:** versión publicada no significa elegible para toda transacción actual. Mantener `documentType + schemaVersion + validFrom/validTo + applicability`. Integrar anexos y versiones del SRI en pruebas reales.

## Obligaciones principales y pruebas de cumplimiento
| ID | Requisito | Implementación exigible/comprobación |
|---|---|---|
| C-01 | Emisor habilitado y certificado vigente | Checklist de habilitación SRI, ambiente, P12 y fechas |
| C-02 | XML conforme XSD del tipo/versión | XSD local versionado, validación antes de firma; `codDoc`, atributo `version` e `id` verificados en código porque el XSD no los restringe (doc 14) |
| C-03 | Clave de acceso de 49 dígitos | Módulo 11 y campos oficiales ver doc 06 |
| C-04 | Firma electrónica conforme ficha | XAdES-BES / perfil aplicable, validación criptográfica |
| C-05 | Transmisión inmediata según reglas vigentes | Job de envío al firmar, métrica de retraso, cola duradera |
| C-06 | Recepción y autorización diferenciadas | No tratar RECIBIDA como AUTORIZADO |
| C-07 | Entrega al receptor y archivo | XML firmado y RIDE; evidencia de envío/consulta |
| C-08 | Catálogos IVA, identificación, pagos, retenciones | Versionado con vigencia; vigilar reformas y excepciones |
| C-09 | Anulaciones y notas correctivas | Workflow separado con permisos y reglas vigentes |
| C-10 | Registro del proveedor SaaS | Establecimiento exclusivo con actividad J62021002 (NAC-DGERCGC26-00000027); ver abajo |
| C-11 | RUC del proveedor de software en info adicional | `campoAdicional` "RUC Proveedor" en cada comprobante; obligatorio para transmitir a producción |
| C-12 | Conservación y seguridad | Política legal vigente + archivo restaurable y auditable |

## Catálogos y reglas ya incorporados desde la ficha v2.34
Leídos del PDF oficial el 2026-10-10 y sembrados en la migración `V3__catalogs_billing.sql`:

| Fuente | Contenido | Dónde vive |
|---|---|---|
| Tabla 6 | Tipo de identificación: `04` RUC, `05` cédula, `06` pasaporte, `07` consumidor final (`9999999999999`), `08` identificación del exterior | `identification_types` |
| Tabla 16 | Impuestos: IVA `2`, ICE `3`, IRBPNR `5` | solo el IVA está en uso |
| Tabla 17 | Tarifa del IVA: `0` 0 %, `2` 12 %, `3` 14 %, `4` 15 %, `5` 5 %, `6` no objeto, `7` exento, `8` diferenciado (8 %), `10` 13 % | `tax_rates` |
| Tabla 24 | Formas de pago `01`, `15`, `16`, `17`, `18`, `19`, `20`, `21` con su fecha de inicio | `payment_methods` |
| 9.10 | Factura a consumidor final mayor a 50 USD exige identificar al adquirente | propiedad `einvoice.invoice.final-consumer-limit` |
| 9.16, 9.17 | IVA sobre la suma de bases por tarifa; dos decimales, seis en cantidad y precio | `InvoiceCalculator` |
| 9.18 | Emisión en orden cronológico y secuencial, sin duplicados | `document_sequences` y restricciones únicas |
| Sección 6, Anexo 14 | Firma XAdES-BES *enveloped*, RSA-SHA1, tres referencias | `XadesBesSigner`, `XadesVerifier` (doc 07) |
| Anexo 3 | Formato XML de factura versión 1.1.0 | `InvoiceXmlBuilder` |
| Anexos 21 y 22 | Leyendas `agenteRetencion` y `contribuyenteRimpe` | datos fiscales del emisor, congelados en el documento |
| Anexo 26 | Campo adicional `RUC Proveedor` | propiedad `einvoice.provider.ruc` |

La ficha da el porcentaje de cada código de IVA pero **no** qué código corresponde aplicar a cada producto ni desde cuándo: eso lo decide el emisor al registrar el producto. En consecuencia `tax_rates` no tiene fechas de vigencia y la plataforma **no impide** facturar hoy con un código histórico (por ejemplo `2`, 12 %); el requisito de "tasas efectivas por fecha" de DEVELOPMENT §3 sigue pendiente de una fuente normativa con esas fechas. De la ficha ya se tomaron los parámetros de firma (doc 07) y el campo del RUC del proveedor (doc 14); y los códigos de error y advertencia de su sección 11 (doc 08). El formato exigido a cada identificación (13 dígitos para RUC, 10 para cédula, etc.) es una decisión de la plataforma y no incluye dígito verificador.

## Registro de proveedor de software — Resolución NAC-DGERCGC26-00000027
Revisado el 2026-10-27 con la noticia del SRI y los resúmenes de varios despachos tributarios, que coinciden entre sí. **El texto íntegro del Registro Oficial no se ha leído**: lo que sigue sirve para actuar, pero el cómputo exacto de los plazos se confirma con él o con un asesor tributario.

- **Publicación:** firmada el 27-07-2026, publicada en el Quinto Suplemento del Registro Oficial n.º 335 del **28-07-2026**. Rige desde ese día y los plazos se cuentan desde él.
- **Sujetos (art. 2):** con fines de comercialización y domicilio o establecimiento permanente en Ecuador, (a) quien desarrolla un sistema para emitir, firmar, transmitir y conservar comprobantes electrónicos y es dueño de su código fuente, y (b) quien es propietario de licencias y código fuente de un sistema desarrollado por un tercero. Un sistema solo para facturación propia no entra.
- **Registro en el RUC (art. 3):** un establecimiento **exclusivo** con la actividad **J62021002** (categoría a, la de esta plataforma) o **J62021003** (categoría b). La J62021001 (desarrollo de software genérico) no cumple. Según los proveedores que ya lo hicieron, el trámite es presencial: el RUC en línea no ofrece esas actividades.
- **Listado público (art. 4):** el SRI publica los proveedores registrados desde **octubre de 2026** y lo actualiza en los diez primeros días hábiles de cada mes, con corte al último día del mes anterior.
- **Comprobantes (art. 5):** el emisor que usa el sistema o servicio de un proveedor incluye el RUC de este en la información adicional, según la ficha: `campoAdicional nombre="RUC Proveedor"` (Anexo 26).
- **Sanciones:** ninguna fuente cita una específica. La consecuencia visible es no figurar en el listado.

| Obligación | Plazo | Vence |
|---|---|---|
| Registrar el establecimiento en el RUC | 30 días (las fuentes no aclaran si son hábiles) | 27-08-2026 si son calendario; hacia el 09-09-2026 si son hábiles |
| RUC del proveedor en cada comprobante | 60 días: calendario según la disposición transitoria y el Anexo 26; la noticia del SRI dice hábiles | 26-09-2026 si son calendario; hacia el 21-10-2026 si son hábiles |

Al 2026-10-27 los dos plazos están vencidos con cualquier cómputo. Esta plataforma todavía no ha emitido ningún comprobante real, así que lo pendiente es el registro del establecimiento: hecho antes del 31 de octubre, el proveedor aparecería en el listado de noviembre.

**Cómo lo cumple la plataforma.** El RUC del proveedor sale de `EINVOICE_PROVIDER_RUC` (`einvoice.provider.ruc`), queda congelado en cada comprobante al emitirse y va en el XML y en el RIDE. Con la transmisión a producción abierta (`SRI_ENABLED_ENVIRONMENTS` con `2`) es obligatorio: la aplicación no arranca sin él (`ProviderRucTest`). En pruebas sigue siendo opcional.

**Sistemas que facturan a través de la API.** El emisor es el cliente de la plataforma, y el RUC que va en su comprobante es el de esta plataforma, aunque la petición la haga otro sistema (por ejemplo, un SaaS clínico que integra la API). Si ese sistema pertenece a otro RUC y vende la facturación como parte de su servicio, podría quedar en la categoría (b): es una consulta para el asesor tributario, no una decisión de la plataforma.

## Habilitación del emisor
1. Verificar RUC y obligaciones tributarias; 2. disponer de firma electrónica de entidad certificadora habilitada; 3. adherirse/solicitar acceso a comprobantes electrónicos en SRI en línea; 4. configurar el ambiente de certificación; 5. probar XML/firma/envío/autorizar; 6. habilitar ambiente de producción y controlar accesos. No asumir que registrar un SaaS habilita automáticamente a cada emisor.

## Cambios regulatorios que demandan seguimiento
- Reformas de anulaciones y notas de crédito, especialmente Resoluciones NAC-DGERCGC25-00000014 y NAC-DGERCGC25-00000017: verificar tipo de comprobante, ventana, aceptación del receptor y causales.
- Porcentaje IVA, códigos tributarios y condiciones RIMPE: son datos efectivos por fecha, sujeto y operación, **no constantes de código**.
- Excepciones de guías, exportación, combustibles, reembolsos, notas y retenciones deben modelarse por documento.
- La plataforma permite importación de XSD y catálogos oficiales con hash, revisión y despliegue controlado.

## Evidencia de auditoría
Guardar entradas estructuradas, versión del esquema, hash SHA-256, firma, XML transmitido, respuesta SOAP íntegra, estado, hora UTC, intentos, mensajes, actor, comprobante relacionado, notificación y evidencia de conservación.

## Fuentes oficiales y control de cambios
- [SRI — Facturación electrónica](https://www.sri.gob.ec/facturacion-electronica): ficha técnica esquema off-line v2.34 (julio 2026), XSD y normativa.
- [SRI — registro de proveedores (28-07-2026)](https://www.sri.gob.ec/detalle-noticias?idnoticia=1318&marquesina=1): Resolución NAC-DGERCGC26-00000027.
- [SRI — adhesión y firma](https://www.sri.gob.ec/herramienta-de-comprobantes-electronicos).

> **Límite jurídico/técnico:** estos documentos son una especificación de ingeniería, no sustituyen los XSD oficiales, la ficha técnica integral, las resoluciones publicadas en Registro Oficial ni asesoría tributaria. Antes de pasar a producción, congelar versiones de normativa, XSD y catálogos, y ejecutar la matriz de conformidad con el ambiente de certificación del SRI. El detalle y fechas de entrada en vigor de cada reforma deben corroborarse en su texto normativo publicado.
