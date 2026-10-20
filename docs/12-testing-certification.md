# 12 — Estrategia de pruebas y certificación SRI

## Pirámide de pruebas
| Capa | Evidencia mínima |
|---|---|
| Unitarias | Módulo 11, 49 dígitos, moneda BigDecimal, fechas, reglas de IVA, secuencias |
| Integración | PostgreSQL Testcontainers, Flyway, RabbitMQ, Redis; almacenamiento S3 cuando se elija (doc 13) |
| Contrato | SOAP desde WSDL, schemas, códigos, errores, OpenAPI |
| Firma | Validación XAdES local independiente y fixtures SRI |
| E2E | Emitir -> validar -> firmar -> recibir -> autorizar -> guardar -> entregar |
| Resiliencia | Timeouts, duplicados, workers en paralelo, SRI caído, DLQ |
| Seguridad | Tenant crossing, IDOR, XXE, secretos, presigned URL |

## Matriz del ambiente certificación
- RUC emisor registrado para pruebas; firmante válido; puntos y tipos habilitados.
- Factura: identificación RUC/cédula/pasaporte/consumidor final donde aplique; distintas tasas y pagos; campos adicionales.
- Datos especiales (según alcance): descuentos, IVA, ICE/IRBPNR, reembolsos, RIMPE, retenciones, exportación, guías.
- Obtener al menos un `RECIBIDA` y un `AUTORIZADO` de cada esquema incluido en MVP.
- Forzar una `DEVUELTA`, un `NO AUTORIZADO`, timeout y reprocesamiento sin duplicar.
- Verificar que RUC proveedor se incluya cuando aplique y cumpla norma 2026.
- Descargar XML firmado, autorización y RIDE; comparar montos y clave con registro.
- Probar dos trabajadores emitiendo 100 facturas concurrentes al mismo punto: ningún secuencial repetido.

## Casos automatizados sugeridos
`AccessKeyTest`, `TaxRoundingTest`, `SequenceConcurrencyIT`, `InvoiceXsdIT`, `XadesSignatureIT`, `SriReceptionContractIT`, `AuthorizationReconcileIT`, `TenantIsolationIT`, `CertificateRotationIT`, `DeliveryRetryIT`, `RecoveryIT`.

## Gates
G1 compilación / cobertura crítica; G2 XSD + firma independiente; G3 SRI certificación por versión/tipo; G4 seguridad/restore; G5 emisor habilitado para producción; G6 checklist legal del proveedor SaaS aprobado.

**Estado de G3 (2026-10-11): abierto.** La integración con el SRI está implementada y probada solo contra un SRI simulado que reproduce los ejemplos del numeral 7.2.3 de la ficha (`SriStub`): recepción, devolución, autorización, rechazo, procesamiento, timeouts, cortes y respuestas ilegibles. Ningún comprobante se ha enviado a `celcer.sri.gob.ec`. Para cerrarlo hacen falta una firma electrónica real y un RUC habilitado en el ambiente de pruebas; con ellos, poner `SRI_ENABLED_ENVIRONMENTS=1`, emitir una factura y comprobar `RECIBIDA` y `AUTORIZADO`. Es también lo único que confirmará que el SRI acepta la firma (doc 07). Desde el Plan 8 ese paso es un comando (`./mvnw -B verify -Pcertification`, con el certificado y los datos del emisor en variables de entorno) que emite la matriz y deja la evidencia; la misma matriz corre siempre contra el SRI simulado (`CertificationMatrixIT`). Guía, matriz fila por fila y estado de todos los gates en el [doc 15](15-produccion.md).

**Aplicación web (2026-10-11).** Tiene 173 pruebas de componente y de servidor (sesión, inicio y cierre contra un proveedor de identidad de prueba, proxy, política de contenido, cálculo, pantallas contra una API simulada) y **ninguna prueba automatizada con navegador**. El recorrido completo se hizo a mano una vez contra la API real y un proveedor de identidad local: iniciar sesión, crear empresa y emisor, cargar un certificado de prueba, registrar cliente y producto, emitir dos facturas, reintentar, descargar el XML firmado y cerrar sesión. Ese recorrido encontró tres fallos que las pruebas de componente no veían, ya corregidos. No se pudo recorrer un comprobante autorizado, la descarga del RIDE ni el envío por correo (SRI y correo apagados), y el aspecto visual no se ha revisado a la vista. En el Plan 8 se repitió en parte, con el servidor de desarrollo y con la compilación de producción, para comprobar la política de contenido y el paso a producción; de nuevo sin mirar las pantallas. Automatizar ese recorrido con un navegador era trabajo pendiente antes de producción; lo hace el Plan 7b.

**Pruebas con navegador (2026-10-13).** El paquete `e2e/` abre la aplicación en Chromium (Playwright) contra el jar de la API y la compilación de producción de la web, con PostgreSQL, RabbitMQ, Redis, Keycloak y un servidor SMTP de captura propios, y un SRI simulado que se guioniza por RUC ([cómo ejecutarlas](../e2e/README.md)). Es la fila "E2E" de la pirámide, por primera vez de la pantalla al correo: 16 pruebas recorren el alta de una empresa (emisor, punto de emisión, certificado, cliente y producto), la emisión desde un borrador hasta `AUTORIZADO`, la descarga del XML autorizado y del RIDE, el correo al cliente, la devolución y el rechazo con su reemisión enlazada, un SRI que no contesta y la reconciliación sin duplicar, la invitación con dos usuarios y los permisos de un facturador, los avisos firmados (con rotación del secreto y apagado) y la sesión (renovación tras caducar la ficha de acceso, enlace directo y cierre); una más comprueba que las pantallas principales caben en un teléfono. Corren en CI en cada cambio. Lo que no cubren: el SRI real (G3 sigue abierto), Firefox y Safari, el recorrido completo en un teléfono, el paso a producción de un emisor y la comparación de capturas.

**Cuentas propias (2026-10-14, Plan 10).** La API lleva las cuentas: `AuthApiIT` recorre registro, confirmación del correo (con el SMTP simulado), ingreso, renovación con rotación y el cierre de la sesión al reusar un token, salida, recuperación y cambio de contraseña, bloqueo por intentos y que la respuesta no revele si un correo tiene cuenta; `AuthRateLimitIT`, el límite por IP; `AuthPropertiesTest`, `PasswordsTest` y `AccessTokensTest`, las piezas sueltas. Las pruebas con navegador ya no usan Keycloak: `auth.setup.ts` registra al usuario desde la pantalla de registro, cada prueba entra con su propia sesión y `cuenta.spec.ts` sigue los enlaces que captura Mailpit. Las invitaciones, el equipo (pantalla y endpoints `/members`) y sus pruebas se quitaron.

## Evidencias
Guardar resultados sin datos personales sensibles, fecha, ambiente, versión de XSD/ficha, hash XML, captura de códigos SRI y reporte de fallos. Certificación es del proceso/ambiente habilitado por SRI; no prometer certificado de homologación universal si no existe como tal.


## Precondición: XSD disponibles y verificables

Los XSD de cada comprobante están en `src/main/resources/xsd/{tipo}/{versión}/` y `xmldsig-core-schema.xsd`, una sola vez, en `src/main/resources/xsd/common/`; seguir el [doc 14](14-xsd-integration.md). Pruebas mínimas derivadas de ese documento:

- `XsdAssetIntegrityTest`: cada archivo del [registro de activos](xsd-source-registry.csv) existe y su SHA-256 coincide, no hay XSD ni XML sin registrar y `xmldsig-core-schema.xsd` existe una sola vez.
- `DocumentSchemasTest`: cada tipo y versión compila con `ACCESS_EXTERNAL_DTD=""` y `ACCESS_EXTERNAL_SCHEMA=""` usando solo el resolver local; el resolver rechaza cualquier recurso fuera de su allowlist (`ClasspathSchemaResolverTest`).
- XML de ejemplo (`InvoiceXsdIT`, `DocumentSamplesXsdIT`): todos pasan su XSD salvo el de factura 2.0.0, que falla por `incoTermFactura` vacío y el test espera ese fallo.
- Reglas fuera del XSD: `codDoc=00`, `version="version0"` o `id` ausente se rechazan en código aunque el XSD los acepte.
- XML con `DOCTYPE` o entidad externa se rechaza antes de validar.

## Fuentes oficiales y control de cambios
- [SRI — Facturación electrónica](https://www.sri.gob.ec/facturacion-electronica): ficha técnica esquema off-line v2.34 (julio 2026), XSD y normativa.
- [SRI — registro de proveedores (28-07-2026)](https://www.sri.gob.ec/detalle-noticias?idnoticia=1318&marquesina=1): Resolución NAC-DGERCGC26-00000027.
- [SRI — adhesión y firma](https://www.sri.gob.ec/herramienta-de-comprobantes-electronicos).

> **Límite jurídico/técnico:** estos documentos son una especificación de ingeniería, no sustituyen los XSD oficiales, la ficha técnica integral, las resoluciones publicadas en Registro Oficial ni asesoría tributaria. Antes de pasar a producción, congelar versiones de normativa, XSD y catálogos, y ejecutar la matriz de conformidad con el ambiente de certificación del SRI. El detalle y fechas de entrada en vigor de cada reforma deben corroborarse en su texto normativo publicado.
