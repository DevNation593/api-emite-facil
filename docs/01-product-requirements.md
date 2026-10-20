# 01 — Requisitos de producto (PRD)

**Producto:** plataforma SaaS de emisión de comprobantes electrónicos para Ecuador. **Estado:** diseño inicial, octubre 2026. **Stack:** Java/Spring Boot; PostgreSQL/JPA/Flyway; RabbitMQ; Redis; S3/MinIO; Next.js; Docker.

## Problema, objetivo y usuarios
Emisores necesitan emitir, firmar, transmitir, verificar, entregar y conservar comprobantes de manera segura, auditable y multiempresa. Usuarios: propietario/administrador, facturador/cajero, contador/auditor, operador de soporte, integrador API.

## MVP — historias de usuario y criterios de aceptación
| ID | Historia | Criterio comprobable | Prioridad |
|---|---|---|---|
| PR-01 | Alta de empresa/tenant | RUC, razón social, ambiente y sucursales aislados | P0 |
| PR-02 | Alta de establecimientos y puntos de emisión | Combinaciones únicas y autorizadas por emisor | P0 |
| PR-03 | Certificado digital | P12 validado, cifrado, vigencia visible, nunca retornado | P0 |
| PR-04 | Clientes y artículos | CRUD, identificación válida y búsqueda paginada | P0 |
| PR-05 | Emisión de factura | Montos reproducibles, numeración segura, XML validado | P0 |
| PR-06 | Firma + recepción SRI | XAdES acorde perfil oficial, recepción registrada | P0 |
| PR-07 | Autorización SRI | Persistir estado, respuesta original, fecha, clave | P0 |
| PR-08 | Entrega | XML firmado/validado y RIDE por correo con trazabilidad | P0 |
| PR-09 | Descarga y consulta | Ver comprobante, XML, RIDE, eventos y mensajes | P0 |
| PR-10 | API externa | OAuth2/client credentials o tokens con scopes + idempotencia | P1 |
| PR-11 | Notas crédito/débito | Referencia al comprobante base, reglas específicas. **Implementado:** `POST /credit-notes` y `POST /debit-notes` sobre una factura de la plataforma (autorizada, del mismo emisor; con el comprador congelado de la factura) o sobre una factura escrita a mano; las de crédito no superan el saldo de la factura; ambas recorren firma, SRI, RIDE y correo como la factura | P1 |
| PR-12 | Retenciones, guías y liquidaciones | Motores y versiones de XML independientes | P2 |
| PR-13 | Suscripciones SaaS | Planes, cuotas, límites por tenant, facturación propia separada | P1 |

## Requisitos no funcionales
- Aislamiento tenant-by-tenant y prueba automatizada de acceso cruzado; tiempos SLA son **objetivos de producto**, no garantías del SRI.
- Disponibilidad objetivo 99.9% del servicio SaaS (excluye servicio externo SRI); restauración probada; accesibilidad y responsive.
- P95 API lectura inferior a 500 ms bajo carga objetivo definida en pruebas; emisión es asíncrona y no promete autorización instantánea.
- Documentos inmutables tras firmar; no eliminar registros fiscales por baja de suscripción; exportación y retención conforme obligación vigente.
- Cálculos monetarios con BigDecimal y escala/reglas por campo según XSD; nunca float/double.

## Fuera de alcance del MVP
Contabilidad general, nómina, conciliación bancaria, inventario avanzado, POS sin conexión, retenciones y guías completas, salvo planificación posterior.

## Roadmap
F0 normativa, catálogos, ambientes, fixtures y matriz XSD; F1 autenticación/multiempresa/catálogos; F2 factura/firma/SRI/RIDE/email; F3 robustez operativa y certificación; F4 notas (crédito y débito: hechas en la API) y anulaciones; F5 retenciones/guías/liquidaciones; F6 ERP, API partner, planes.

## Indicadores
% autorizados, ratio devueltos, tiempo de autorización, duplicados evitados, entrega XML exitosa, reintentos, expiraciones de certificados, fallos por tenant, costo por documento.

## Fuentes oficiales y control de cambios
- [SRI — Facturación electrónica](https://www.sri.gob.ec/facturacion-electronica): ficha técnica esquema off-line v2.34 (julio 2026), XSD y normativa.
- [SRI — registro de proveedores (28-07-2026)](https://www.sri.gob.ec/detalle-noticias?idnoticia=1318&marquesina=1): Resolución NAC-DGERCGC26-00000027.
- [SRI — adhesión y firma](https://www.sri.gob.ec/herramienta-de-comprobantes-electronicos).

> **Límite jurídico/técnico:** estos documentos son una especificación de ingeniería, no sustituyen los XSD oficiales, la ficha técnica integral, las resoluciones publicadas en Registro Oficial ni asesoría tributaria. Antes de pasar a producción, congelar versiones de normativa, XSD y catálogos, y ejecutar la matriz de conformidad con el ambiente de certificación del SRI. El detalle y fechas de entrada en vigor de cada reforma deben corroborarse en su texto normativo publicado.
