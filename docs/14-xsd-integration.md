# 14 — Integración verificable de XML/XSD de factura

## Estado de activos (2026-10-10)

Los XSD y XML de ejemplo de factura (`01`) en versiones `1.0.0`, `1.1.0`, `2.0.0` y `2.1.0`, y la dependencia `xmldsig-core-schema.xsd`, están físicamente en el repositorio. Fueron aportados por el usuario; **no se ha contrastado su hash contra el ZIP del SRI ni contra w3.org**. Hashes y estado por archivo en [xsd-source-registry.csv](xsd-source-registry.csv).

- Fuente oficial: [paquete XML y XSD Factura](https://www.sri.gob.ec/o/sri-portlet-biblioteca-alfresco-internet/descargar/05546998-6f29-4870-be3b-62650f312a6c/XML%20y%20XSD%20Factura.zip); el portal del SRI publica las versiones `1.0.0`, `1.1.0`, `2.0.0`, `2.1.0` (actualizado a febrero 2022).
- Fuente normativa: [Ficha técnica 2.34 (julio 2026)](https://www.sri.gob.ec/o/sri-portlet-biblioteca-alfresco-internet/descargar/f8d9bb36-5632-4f96-b463-b9265b55338c/FICHA%20TE%CC%81CNICA%20COMPROBANTES%20ELECTRO%CC%81NICOS%20ESQUEMA%20OFFLINE%20Versio%CC%81n%202.34.pdf).
- Dependencia: `xmldsig-core-schema.xsd` ([especificación W3C XML Signature](https://www.w3.org/TR/xmldsig-core/#sec-Schema)), cabecera `Revision 1.2, 2013/04/16`, `targetNamespace` `http://www.w3.org/2000/09/xmldsig#`.

## Estructura

```text
src/main/resources/xsd/invoice/
  1.0.0/  factura_V1.0.0.xsd  factura_V1.0.0.xml  xmldsig-core-schema.xsd
  1.1.0/  factura_V1.1.0.xsd  factura_V1.1.0.xml  xmldsig-core-schema.xsd
  2.0.0/  factura_V2.0.0.xsd  factura_V2.0.0.xml  xmldsig-core-schema.xsd
  2.1.0/  factura_V2.1.0.xsd  factura_V2.1.0.xml  xmldsig-core-schema.xsd
```

Cada XSD de factura importa `xmldsig-core-schema.xsd` con ruta relativa, por eso hay una copia byte a byte idéntica junto a cada versión. No editar los XSD originales; si hiciera falta una copia derivada, identificarla claramente y acompañarla del hash del original.

## Resolución de dependencias sin red (decisión)

`xmldsig-core-schema.xsd` declara un `DOCTYPE` que referencia `http://www.w3.org/2001/XMLSchema.dtd` (identificador público `-//W3C//DTD XMLSchema 200102//EN`). Consecuencias comprobadas con JDK 23.0.2:

| Configuración de `SchemaFactory` | Resultado |
|---|---|
| `ACCESS_EXTERNAL_DTD=""` sin resolver | Los cuatro XSD **no compilan** (acceso `http` al DTD denegado) |
| Sin restricciones | Compilan, pero **descargando el DTD desde w3.org** (prohibido en producción) |
| `ACCESS_EXTERNAL_DTD=""`, `ACCESS_EXTERNAL_SCHEMA=""` y resolver local | Los cuatro compilan sin ningún acceso externo |

La implementación debe usar la tercera opción. El `LSResourceResolver` es una allowlist cerrada con exactamente dos entradas y rechaza cualquier otro recurso:

1. Tipo `http://www.w3.org/2001/XMLSchema` con `systemId` `xmldsig-core-schema.xsd`: devolver el recurso empacado de la misma versión.
2. Tipo `http://www.w3.org/TR/REC-xml` con `publicId` `-//W3C//DTD XMLSchema 200102//EN`: devolver un subconjunto externo vacío. El subconjunto interno del propio archivo ya declara lo que el esquema necesita, así que el original se conserva sin modificar.

## Resultado de validación (2026-10-10)

| Versión | XSD | XML de ejemplo |
|---|---|---|
| 1.0.0 | compila | válido |
| 1.1.0 | compila | válido |
| 2.0.0 | compila | **inválido**: `incoTermFactura` vacío viola `minLength=1` |
| 2.1.0 | compila | válido |

Estos resultados están ahora cubiertos por pruebas automatizadas (`InvoiceSchemasTest`, `InvoiceXsdIT`, `XsdAssetIntegrityTest`) que corren con `./mvnw verify`. El estado en el registro sigue en `*_PRUEBA_MANUAL` hasta la primera ejecución en CI.

## Lo que el XSD no valida

Los XML de ejemplo traen `codDoc=00`, `version="version0"`, clave de acceso en ceros y `xsi:noNamespaceSchemaLocation` con una ruta `file:/C:/...`, y aun así tres de ellos pasan el XSD: el tipo `codDoc` solo exige dos dígitos y el atributo `version` no tiene restricción. Por tanto, **además** del XSD, el generador y el validador deben comprobar en código:

- `codDoc` igual al código SRI del tipo de documento (`01` para factura).
- Atributo `version` igual a la versión del esquema usada (`1.0.0`, `1.1.0`, `2.0.0`, `2.1.0`).
- Atributo `id="comprobante"` presente (el XSD lo admite ausente; la firma lo referencia).
- `claveAcceso` válida (doc 06) y coherente con `fechaEmision`, `ruc`, `ambiente`, `estab`, `ptoEmi`, `secuencial` y `tipoEmision` del mismo XML.
- Ausencia de `DOCTYPE` y de `schemaLocation`/`noNamespaceSchemaLocation` en el XML generado o recibido.

Los XML de ejemplo son plantillas ilustrativas: no usarlos como fixtures de factura válida ni emitirlos.

## Secuencia segura de integración

1. Descargar el ZIP **directamente del SRI**, registrar fecha y SHA-256 del ZIP, y contrastar los hashes de los archivos ya incorporados. Si difieren, sustituir por los oficiales y actualizar el registro.
2. Contrastar `xmldsig-core-schema.xsd` con la copia publicada por W3C y registrar el resultado.
3. Ejecutar `python scripts/verify_xsd_assets.py src/main/resources/xsd/invoice` para diagnosticar dependencias, inventario y hashes. El script **no sustituye** la compilación real con `SchemaFactory`.
4. Implementar el resolver local descrito arriba y bloquear entidades externas/XXE.
5. Añadir pruebas Java parametrizadas por las cuatro versiones (`InvoiceXsdIT`) y las comprobaciones de la sección anterior.
6. Conservar como evidencia: versión, fuente, hash, fecha, resultado del parser, archivo XML probado y resultado de validación.

## Registro de procedencia

Mantener [xsd-source-registry.csv](xsd-source-registry.csv). Estados: `PENDIENTE`; `COMPILA_PRUEBA_MANUAL`, `VALIDA_XSD_PRUEBA_MANUAL` e `INVALIDO_XSD_PRUEBA_MANUAL` mientras la evidencia sea manual; los mismos con sufijo `_CI` cuando la cubra un test automatizado; y `VALIDADO` solo cuando además el origen oficial esté contrastado.

## Criterio de aceptación

- Los cuatro XSD compilan en CI con todas sus dependencias y sin acceso externo.
- Los XML de ejemplo aplicables pasan la validación y la excepción de 2.0.0 queda cubierta por un test que la espera.
- No hay descargas HTTP en producción para resolver imports ni DTD.
- Hashes contrastados con el paquete oficial del SRI y con W3C.
- Tests de esquemas y firma se ejecutan en CI.
- XML firmado conforme al perfil XAdES-BES del SRI; validar XSD por sí solo no acredita una firma válida.

## Versión que se emite y discrepancias con la ficha
- **Versión 1.1.0.** Es la que la ficha (Anexo 3) define para cantidad y precio unitario con seis decimales. Las versiones 2.0.0 y 2.1.0 añaden rubros de terceros y la factura sustitutiva de guía de remisión, que no se usan. `InvoiceXmlBuilder` escribe el XML y un test lo compara byte a byte con el esperado y lo valida contra el XSD.
- **RUC del emisor.** El tipo `numeroRuc` del XSD es `[0-9]{10}001`: el alta de emisores exige ese formato y la base lo impone con una restricción.
- **Leyenda de negocio popular.** La ficha (Anexo 22) admite `CONTRIBUYENTE NEGOCIO POPULAR - RÉGIMEN RIMPE`, pero el `factura_V1.1.0.xsd` del repositorio solo acepta `CONTRIBUYENTE RÉGIMEN RIMPE`. Sus facturas quedarían en `XSD_INVALID` después de consumir un secuencial, así que el alta de un emisor con `rimpeRegime=NEGOCIO_POPULAR` se rechaza con `422 RIMPE_REGIME_UNSUPPORTED`. Se habilitará cuando se contraste el XSD con el paquete oficial del SRI (tarea N1).
- **Clave de acceso.** `InvoiceEnvelopeRules` comprueba además que `claveAcceso` sea válida y coincida con fecha, tipo, RUC, ambiente, establecimiento, punto, secuencial y tipo de emisión del mismo XML.
- **Tamaño.** Un XML de más de 320 KB (límite de envío individual de la ficha) se rechaza al generarlo.

## Fuentes oficiales y control de cambios
- [SRI — Facturación electrónica](https://www.sri.gob.ec/facturacion-electronica): ficha técnica esquema off-line v2.34 (julio 2026), XSD y normativa.
- [W3C — XML Signature Schema](https://www.w3.org/TR/xmldsig-core/#sec-Schema): fuente técnica complementaria, no norma tributaria.

> **Límite jurídico/técnico:** estos documentos son una especificación de ingeniería, no sustituyen los XSD oficiales, la ficha técnica integral, las resoluciones publicadas en Registro Oficial ni asesoría tributaria. Antes de pasar a producción, congelar versiones de normativa, XSD y catálogos, y ejecutar la matriz de conformidad con el ambiente de certificación del SRI. El detalle y fechas de entrada en vigor de cada reforma deben corroborarse en su texto normativo publicado.
