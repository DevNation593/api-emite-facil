# Facturación electrónica Ecuador — API (backend-emite-facil)

API y worker del SaaS de facturación electrónica, más lo que es de todo el sistema: el contrato de la API (`contracts/openapi.yaml`), la infraestructura (`infra/`), la documentación (`docs/`) y las pruebas con navegador del recorrido completo (`e2e/`). La aplicación web está en [client-factura-facil](https://github.com/DevNation593/client-factura-facil).

Ramas: `dev` (integración: cada cambio llega por pull request), `qa` y `main`.

> **Estado: el sistema emite, firma, transmite, archiva y entrega facturas, con aplicación web, pero solo se ha probado contra un SRI simulado.** Los ocho primeros planes y el 8b del [plan maestro](docs/superpowers/plans/2026-10-10-plan-maestro.md) están implementados y probados en local: empresas con roles y aislamiento, emisores, clientes y productos, factura calculada y numerada, XML 1.1.0 firmado en XAdES-BES, envío y autorización ante el SRI con reintentos y reconciliación, RIDE y archivo inmutable, entrega por correo, aplicación web, y lo necesario para operar (paso a producción por emisor, métricas, respaldo y restauración, imágenes). **Ningún comprobante se ha enviado al SRI real**: falta una firma electrónica real y un RUC habilitado en pruebas, y la transmisión viene apagada. Qué está comprobado y qué no, gate por gate, en [docs/15-produccion.md](docs/15-produccion.md).

## Objetivo y alcance del MVP

- **Comprobante inicial:** factura electrónica (código SRI `01`). Otros tipos se implementarán por fases.
- **Producto:** SaaS multiempresa, con aislamiento de datos entre emisores, gestión de puntos de emisión y trazabilidad.
- **Arquitectura:** monolito modular Spring Boot + workers asíncronos de emisión, recepción y consulta del SRI.
- **Flujo:** factura → secuencial/clave de acceso → XML/XSD → firma XAdES-BES → recepción y autorización SRI → conservación XML/RIDE → notificación.

## Stack tecnológico

| Capa | Elección |
| --- | --- |
| Backend | **Java 21 + Spring Boot** |
| Base de datos | PostgreSQL |
| Persistencia | Spring Data JPA / Hibernate |
| Migraciones | Flyway |
| Colas | RabbitMQ |
| Caché | Redis |
| Archivos XML/PDF | S3 compatible o MinIO |
| Frontend | **Next.js + TypeScript** |
| Contenedores | Docker / Docker Compose |

## Índice de documentación (16 archivos)

| Documento | Contenido |
| --- | --- |
| [01-product-requirements.md](docs/01-product-requirements.md) | Requisitos funcionales, criterios de aceptación y alcance del MVP |
| [02-sri-compliance.md](docs/02-sri-compliance.md) | Obligaciones regulatorias y verificaciones de cumplimiento SRI |
| [03-software-architecture.md](docs/03-software-architecture.md) | Arquitectura, límites de módulos y dependencias |
| [04-database-design.md](docs/04-database-design.md) | Modelo PostgreSQL, integridad y transacciones |
| [05-electronic-document-flow.md](docs/05-electronic-document-flow.md) | Ciclo de vida de factura y comprobante |
| [06-access-key-algorithm.md](docs/06-access-key-algorithm.md) | Clave de acceso y secuenciales |
| [07-xades-signature.md](docs/07-xades-signature.md) | Firma XML, XAdES-BES y P12 |
| [08-sri-webservices.md](docs/08-sri-webservices.md) | SOAP, recepción y autorización |
| [09-error-handling.md](docs/09-error-handling.md) | Reintentos seguros, estados y conciliación |
| [10-security.md](docs/10-security.md) | Seguridad, claves y separación multiempresa |
| [11-api-specification.md](docs/11-api-specification.md) | Contratos HTTP y autenticación |
| [12-testing-certification.md](docs/12-testing-certification.md) | Validaciones y ambiente de certificación |
| [13-deployment.md](docs/13-deployment.md) | Operación y despliegue |
| [14-xsd-integration.md](docs/14-xsd-integration.md) | Activos XSD/XML de factura, resolución sin red y validación |
| [15-produccion.md](docs/15-produccion.md) | Estado de los gates, matriz de certificación y guías de paso a producción |
| [DEVELOPMENT.md](docs/DEVELOPMENT.md) | Guía maestra, reglas y definición de hecho por sprint |

El trabajo se ejecuta siguiendo el [plan maestro](docs/superpowers/plans/2026-10-10-plan-maestro.md), que divide el desarrollo en nueve planes; el primero está detallado tarea por tarea en [Plan 1 — Fundación](docs/superpowers/plans/2026-10-10-p1-fundacion.md).

## XSD y XML de factura incorporados (entregados por el usuario)

**Disponibles físicamente en el repositorio:** los XSD y XML de ejemplo para factura (`01`) en versiones **1.0.0, 1.1.0, 2.0.0 y 2.1.0**, y la dependencia `xmldsig-core-schema.xsd` junto a cada versión. Fueron aportados por el usuario el **10 de octubre de 2026**; no se ha verificado que sean idénticos a los del ZIP del SRI ni a la copia de W3C. Sus hashes SHA-256 están en el [registro de activos](docs/xsd-source-registry.csv).

Estructura: `src/main/resources/xsd/invoice/{version}/` con `factura_V{version}.xsd`, `factura_V{version}.xml` y `xmldsig-core-schema.xsd`. Ver [guía de integración](docs/14-xsd-integration.md).

**Estado de validación (prueba manual, JDK 23.0.2):** los cuatro XSD compilan sin acceso externo usando un resolver local. `xmldsig-core-schema.xsd` trae un `DOCTYPE` que apunta a un DTD en w3.org, así que sin ese resolver la compilación falla o descarga el DTD de internet; el [anexo 14](docs/14-xsd-integration.md) fija cómo resolverlo. La validación está automatizada (`InvoiceSchemasTest`, `InvoiceXsdIT`, `XsdAssetIntegrityTest`) y corre con `./mvnw verify` y en CI.

**XML de ejemplo:** los de 1.0.0, 1.1.0 y 2.1.0 pasan su XSD; el de 2.0.0 no (`incoTermFactura` vacío). Todos contienen valores ilustrativos (`codDoc=00`, `version=version0`, rutas `file:/C:/...`, clave en ceros) que el XSD no rechaza. No deben emitirse ni usarse como facturas válidas; las reglas que el XSD no cubre se validan en código.

## Próximo paso

1. Contrastar los hashes de los XSD/XML con el ZIP oficial del SRI y el de `xmldsig-core-schema.xsd` con W3C; actualizar el [registro de activos](docs/xsd-source-registry.csv).
2. Validar la ficha v2.34, el Anexo 26 (RUC del proveedor) y los escenarios tributarios aplicables; resolver las [preguntas abiertas](docs/DEVELOPMENT.md#8-preguntas-abiertas-antes-de-desarrollo-productivo).
3. Revisar las vulnerabilidades de las dependencias de Maven también fuera de los pull requests: la revisión de dependencias de GitHub corre en cada pull request (el repositorio es público), pero no vuelve a mirar las dependencias que ya están.
4. Conseguir una firma electrónica real y un RUC habilitado en el ambiente de pruebas del SRI (tarea N4). Con ellos, la certificación es un comando ([guía](docs/15-produccion.md#certificar-contra-el-sri-de-pruebas)) y dirá, antes que nada, si el SRI acepta la firma.
5. Revisar a la vista un RIDE de ejemplo (`target/ride-ejemplo.pdf` tras ejecutar las pruebas) y decidir el servidor de correo y el dominio remitente: la entrega por correo (Plan 6) está implementada pero apagada por defecto y solo se ha probado contra un servidor SMTP simulado.
6. Abrir la aplicación web y decidir si su aspecto es el que se quiere ([cómo arrancarla](docs/DEVELOPMENT.md)): el recorrido completo lo hacen pruebas con un navegador ([Plan 7b](e2e/README.md)), lo que su revisión encontró está corregido (Plan 7c) y cabe en un teléfono; falta verla en Firefox y Safari.
7. Elegir el proveedor de identidad de producción (el Keycloak del compose es solo para desarrollo; el que se elija debe poner el correo del usuario en el token de acceso, o no se podrán aceptar invitaciones), la nube, el registro de imágenes y el gestor de claves.
8. Cerrar lo que el Plan 8 dejó preparado pero no puede cerrar solo: los gates G3, G5 y G6 y el checklist de preproducción de [docs/15-produccion.md](docs/15-produccion.md).
9. Entregar los XSD oficiales de los demás comprobantes (nota de crédito, retención, guía), o autorizar su descarga del SRI: sin ellos el Plan 9 no puede empezar. El Plan 8b (equipo e invitaciones, reemisión de comprobantes rechazados y deuda de revisiones) y el Plan 6b (webhooks) están implementados.
10. Decidir si se encienden los avisos a sistemas de los clientes (`EINVOICE_WEBHOOKS_ENABLED`, apagado por defecto) y, si es así, limitar también en la red a dónde puede conectarse el worker ([doc 10](docs/10-security.md)).

## Fuentes oficiales de referencia

- [SRI — portal de facturación electrónica](https://www.sri.gob.ec/facturacion-electronica).
- [SRI — Ficha técnica de comprobantes electrónicos offline v2.34 (julio 2026, PDF)](https://www.sri.gob.ec/o/sri-portlet-biblioteca-alfresco-internet/descargar/f8d9bb36-5632-4f96-b463-b9265b55338c/FICHA%20TE%CC%81CNICA%20COMPROBANTES%20ELECTRO%CC%81NICOS%20ESQUEMA%20OFFLINE%20Versio%CC%81n%202.34.pdf).
- [SRI — paquete oficial XML y XSD de factura](https://www.sri.gob.ec/o/sri-portlet-biblioteca-alfresco-internet/descargar/05546998-6f29-4870-be3b-62650f312a6c/XML%20y%20XSD%20Factura.zip).
- [SRI — adhesión y herramientas de comprobantes](https://www.sri.gob.ec/herramienta-de-comprobantes-electronicos).
- [SRI — proveedores de sistemas de facturación electrónica (28 de julio de 2026)](https://www.sri.gob.ec/detalle-noticias?idnoticia=1318&marquesina=1), Resolución **NAC-DGERCGC26-00000027**.
- [W3C — XML Signature Schema](https://www.w3.org/TR/xmldsig-core/#sec-Schema), fuente técnica complementaria, **no** norma tributaria ecuatoriana.

Estas referencias enlazan a las fuentes publicadas; la disponibilidad de descarga y la vigencia exacta deben comprobarse al iniciar la implementación y antes de producción.

## Aviso jurídico y técnico

Esta documentación **no sustituye** las normas vigentes del SRI, la ficha técnica integral, los XSD originales, las resoluciones publicadas en Registro Oficial ni asesoría tributaria. La conformidad se establece implementando, probando en el ambiente de certificación y verificando las obligaciones aplicables. **Ningún entorno de producción debe utilizar certificados reales ni transmitir comprobantes antes de finalizar esas validaciones.**
