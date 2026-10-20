# 15 — Certificación y paso a producción

Estado real del sistema frente a lo que hace falta para operar con emisores de verdad, y las guías para dar cada paso que queda. Lo que aquí dice "comprobado" se ejecutó; lo que dice "pendiente" no se ha hecho, por mucho que esté preparado.

## Estado de los gates (doc 12)

| Gate | Estado al 2026-10-11 | Evidencia | Qué falta |
|---|---|---|---|
| **G1** Compilación y cobertura crítica | Cumplido en local | `./mvnw verify` sobre la versión final de este plan: pruebas unitarias y de integración contra PostgreSQL, RabbitMQ y Redis reales; cobertura de líneas de los paquetes críticos por encima del 90 % impuesta por el build (medida: secuenciales 100, facturación 99,3, certificados 95,8, SRI 94,8, firma 93,4, XML 91,1). Frontend: lint, tipos, pruebas y build | Que corra en CI por primera vez: el repositorio no tiene remoto |
| **G2** XSD y firma verificada aparte | Parcial | Los cuatro XSD compilan y validan sin red, con huella registrada; la firma XAdES-BES la comprueba un verificador separado del firmador (`XadesVerifier`) en cada comprobante | Contrastar las huellas con el ZIP oficial del SRI (N1) y, sobre todo, que el SRI acepte la firma: eso solo lo dice G3 |
| **G3** SRI de certificación por versión y tipo | **Abierto** | Factura 1.1.0 recorrida completa contra un SRI simulado con los ejemplos de la ficha, incluida la matriz de este documento | Un certificado real y un RUC habilitado en pruebas. Con ellos es un comando: ver "Certificar" |
| **G4** Seguridad y restauración | Parcial | Restauración ensayada con los scripts reales contra la base local (ver abajo) y probada en `RecoveryIT`; aislamiento entre empresas, custodia del certificado, inmutabilidad de artefactos, roles de base, límite de peticiones y política de contenido con pruebas propias | Prueba de penetración, restauración en el entorno definitivo, gestor de claves, revisión externa |
| **G5** Emisor habilitado para producción | **Abierto** | El mecanismo existe y está probado: un emisor no llega a producción sin evidencia | Ningún emisor se ha activado. Depende de G3 y de que el SRI habilite cada RUC |
| **G6** Checklist legal del proveedor | **Abierto** | — | Texto y fechas de la Resolución NAC-DGERCGC26-00000027, registro del proveedor, política de privacidad y contratos (N3, N5) |

## Matriz de certificación

Cada fila de la matriz del doc 12, con lo que la cubre hoy.

| Fila | Contra el SRI simulado | Contra el SRI real |
|---|---|---|
| RUC emisor registrado, firmante válido, puntos y tipos habilitados | No aplica | Pendiente: son los datos con los que se lanza la certificación |
| Factura a RUC, cédula, pasaporte y consumidor final; distintas tasas y pagos | `CertificationMatrixIT`: seis facturas (IVA 15 %, IVA 0 %, ambos, con descuento, con dos formas de pago), cada una autorizada, con su total fijado a mano, su XSD y su firma comprobados | Pendiente: `SriCertificationIT` emite las mismas seis |
| Campos adicionales | Solo el campo `RUC Proveedor`, cuando se configura (`InvoiceXmlBuilderTest`). La API no admite campos adicionales libres | Pendiente |
| Datos especiales | Descuentos e IVA, cubiertos. RIMPE emprendedor, con su leyenda; RIMPE negocio popular se rechaza al dar de alta el emisor. ICE, IRBPNR, reembolsos, exportación, retenciones y guías no forman parte de este alcance (Plan 9) | Pendiente |
| Un `RECIBIDA` y un `AUTORIZADO` por esquema | `ReceptionPipelineIT`, `AuthorizationPipelineIT`, `PipelineEndToEndIT` | Pendiente |
| Forzar `DEVUELTA`, `NO AUTORIZADO`, timeout y reproceso sin duplicar | `ReceptionPipelineIT` (devolución, cortes, reconciliación, `concurrentReconcilersResendOnce`), `AuthorizationPipelineIT` (rechazo, en procesamiento), `DueDocumentSchedulerIT` | Pendiente, a mano: no se fuerza un rechazo del SRI real desde una prueba automática |
| RUC del proveedor incluido cuando aplique | `InvoiceXmlBuilderTest` | Pendiente; qué exige la norma de 2026 depende de N3 |
| Descargar XML firmado, autorización y RIDE; comparar importes y clave | `DocumentArchiverIT`, `RideDataIT`, `RidePdfBuilderTest`, `AuthorizedXmlBuilderTest` | Pendiente |
| 100 facturas concurrentes, dos trabajadores, ningún secuencial repetido | `SequenceConcurrencyIT` | No aplica |

Los casos automatizados que sugiere el doc 12 existen con estos nombres: `AccessKeyTest`, `TaxRoundingTest`, `SequenceConcurrencyIT`, `InvoiceXsdIT`, `XadesSignatureIT`, `CertificateRotationIT`, `DeliveryRetryIT`, `RecoveryIT`. El contrato de recepción del SRI lo cubren `HttpSriGatewayTest`, `SriResponseParserTest` y `ReceptionPipelineIT`; la reconciliación tras un envío incierto, `ReceptionPipelineIT`; y el aislamiento entre empresas, `TenantContextIT` más un caso de "otra empresa recibe 404" en la prueba de cada recurso.

## Certificar contra el SRI de pruebas

Cuando existan una firma electrónica real y un RUC habilitado en el ambiente de pruebas:

1. Definir las variables, en la terminal y sin guardarlas en ningún archivo del repositorio:

   | Variable | Qué es |
   |---|---|
   | `SRI_CERT_P12_PATH` | Ruta del archivo `.p12` |
   | `SRI_CERT_P12_PASSWORD` | Su contraseña |
   | `SRI_CERT_RUC` | RUC del emisor, el del certificado |
   | `SRI_CERT_LEGAL_NAME`, `SRI_CERT_ADDRESS` | Razón social y dirección de la matriz, como constan en el RUC |
   | `SRI_CERT_TRADE_NAME`, `SRI_CERT_ESTABLISHMENT_ADDRESS` | Opcionales |
   | `SRI_CERT_ESTABLISHMENT`, `SRI_CERT_POINT` | Opcionales; por defecto `001` y `001` |
   | `SRI_CERT_ACCOUNTING_REQUIRED`, `SRI_CERT_RIMPE`, `SRI_CERT_SPECIAL_TAXPAYER`, `SRI_CERT_WITHHOLDING_AGENT` | Opcionales: `true`/`false`, `NONE`/`EMPRENDEDOR`, y los números de resolución |
   | `SRI_CERT_BUYER_CEDULA` | Opcional: una cédula válida para los dos casos a cédula; sin ella se omiten y la evidencia lo dice |
   | `SRI_CERT_START_SEQUENCE` | Opcional: primer secuencial. Por defecto, uno derivado de la fecha, para no repetir los de corridas anteriores |
   | `SRI_CERT_PROVIDER_RUC` | Opcional: RUC del proveedor del sistema, si debe ir en los comprobantes |

2. Desde la raíz de este repositorio, con Docker en marcha:

   ```bash
   ./mvnw -B verify -Pcertification
   ```

   Solo corre `SriCertificationIT`. **Transmite seis facturas reales al ambiente de pruebas del SRI** (`celcer.sri.gob.ec`); nunca a producción. Si falta alguna variable obligatoria no arranca nada y el informe dice cuáles faltan.

3. La evidencia queda en `target/certification/sri-pruebas/` (`evidence.json` y `evidence.md`): fecha, ambiente, versión de esquema y de ficha, versión del código y, por caso, clave de acceso, estado, huella del XML firmado, número y fecha de autorización y códigos de los mensajes del SRI. No lleva nombres ni identificaciones de adquirentes. Guárdela fuera de `target/`: un `clean` la borra.

4. Si algún caso no queda `AUTHORIZED`, la prueba falla y la evidencia se escribe igual: los códigos del SRI dicen por qué. Lo primero que este paso va a decir es si el SRI acepta la firma (doc 07).

Para ensayar el recorrido sin transmitir nada, con cualquier `.p12` de prueba: añadir `-Deinvoice.certification.simulated=true`. Usa el SRI simulado y deja la evidencia en `target/certification/ensayo/`. Se hizo el 2026-10-11 con un certificado generado para la ocasión: los seis casos quedaron autorizados.

## Activar un emisor en producción

Un emisor nace en el ambiente de pruebas. Pasa a producción cuando su propietario lo activa en **Emisor → Paso a producción**, y el sistema solo lo permite si:

- el emisor tiene un certificado de firma vigente;
- el SRI autorizó al menos un comprobante suyo en el ambiente de pruebas;
- el propietario declara que el SRI habilitó ese RUC para producción.

Queda registrado quién y cuándo. La base de datos impide que un emisor esté en producción sin esa activación, y el envío vuelve a comprobarla. La activación no se deshace desde la aplicación.

Aparte, la plataforma solo transmite a producción si `SRI_ENABLED_ENVIRONMENTS` incluye `2`. Es la decisión del proveedor: mientras no lo incluya, los comprobantes de producción de cualquier emisor esperan firmados, y la pantalla lo avisa. No ponga `2` antes de cerrar G3.

No hay un rol de operador de la plataforma que apruebe cada activación: si el proveedor quiere aprobarlas una a una, es un cambio pendiente (un rol y una operación sobre el mismo registro).

## Respaldo y restauración

- `infra/backup/backup.sh <directorio>` hace un volcado de PostgreSQL y escribe su huella SHA-256. `infra/backup/restore.sh <volcado> <base>` comprueba la huella, se niega a escribir sobre una base que ya tenga tablas, restaura e informa de comprobantes, artefactos, artefactos cuya huella no coincide, disparadores de inmutabilidad y migraciones.
- El volcado contiene los certificados cifrados y todos los comprobantes: debe guardarse cifrado y con acceso restringido. **Sin la clave maestra (`EINVOICE_MASTER_KEY`) los certificados restaurados no se pueden usar**: respáldela aparte, nunca junto al volcado.
- Ensayo del 2026-10-11, contra la base de desarrollo local (PostgreSQL 16, con las 9 migraciones) hacia una base temporal: 2 comprobantes y 4 artefactos en el origen; restaurados 2 y 4, ninguna huella distinta, los 2 disparadores presentes, 9 migraciones; en la restaurada la base siguió rechazando borrar un artefacto y poner un emisor en producción sin activación. Una segunda restauración sobre la misma base fue rechazada y, en un ensayo anterior, también un volcado alterado en un byte. Se hizo con un solo usuario de base. `RecoveryIT` repite esto en cada build y comprueba además que la numeración continúa sin repetir secuenciales.
- **Con los dos usuarios de producción el orden importa**: crear la base vacía, ejecutar `infra/postgres/roles.sql` sobre ella y restaurar con `PGUSER=einvoice_migrator`. Quien restaura queda como dueño de las tablas: restaurando como superusuario, el usuario que migra no podría aplicar migraciones y la aplicación no arrancaría. `DatabaseRolesIT` lo comprueba en ese orden.
- La restauración es todo o nada: si falla a medias (por ejemplo, el volcado nombra un rol que el servidor no tiene), la base queda vacía y se repite tras corregir la causa.
- Los scripts no cifran el volcado: cífrelo donde lo guarde. Lo escriben legible solo para quien lo hace, pasan la contraseña por el entorno y `restore.sh` termina con error si algún artefacto restaurado no corresponde a su huella o faltan los disparadores de inmutabilidad (lo deja restaurado para poder examinarlo).
- Lo que no está decidido: frecuencia, retención, recuperación a un punto en el tiempo y dónde se guardan los volcados. Dependen del proveedor de nube (doc 13). Un ensayo local no es una restauración en el entorno real.
- El worker recorre el archivo cada 15 minutos por lotes de 2000 artefactos (hasta 192 000 al día; `einvoice.integrity.*`) y recalcula la huella de cada uno, primero los nunca verificados. Si alguno no coincide, lo registra y la métrica `einvoice_artifacts_corrupt` deja de valer cero. `einvoice_artifacts_unverified` y `einvoice_artifacts_unverified_oldest_seconds` dicen cuánto espera su primera verificación: si crecen, las pasadas no dan abasto.

## Métricas, alertas y registros

- **Métricas** para Prometheus en `/actuator/prometheus`, solo con la ficha de `EINVOICE_METRICS_TOKEN` (al menos 24 caracteres) como `Authorization: Bearer`. Sin ficha configurada no existen. Dicen cuántos comprobantes esperan o están detenidos en cada estado y desde cuándo, lo pendiente en el outbox y en las colas (incluidas las de mensajes muertos), entregas sin enviar, certificados por caducar, artefactos alterados y la duración y resultado de cada llamada al SRI. No identifican empresas ni personas.
- **Avisos a sistemas de clientes (webhooks):** `einvoice_webhooks{status}` cuenta los pendientes, en envío y fallidos; `einvoice_webhooks_disabled`, las suscripciones que se apagaron solas; y `einvoice_queue_messages` incluye su cola y su cola de mensajes muertos.
- **Alertas** de partida en `infra/observability/prometheus-alerts.yml`. Una prueba impide que nombren una métrica que no existe. Los umbrales son un punto de inicio.
- **Sondas** `/actuator/health/liveness` y `/actuator/health/readiness`, sin credenciales.
- **Registros**: cada línea de la API lleva el identificador de correlación de su petición; con `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` salen en JSON. El servidor web escribe una línea JSON por evento que un operador necesita ver (inicio de sesión fallido, sesión que no se pudo renovar, API caída o lenta), sin tokens ni datos de la petición.
- No hay todavía un Prometheus ni un panel desplegados, ni objetivos de servicio acordados.

## Recorrido en un navegador y revisión de las pantallas

Desde el 2026-10-13 el recorrido completo lo hacen pruebas automáticas con un navegador ([e2e/README.md](../e2e/README.md), detalle en el [doc 12](12-testing-certification.md)): de la pantalla de alta al correo que recibe el cliente, contra un SRI simulado. Es la primera vez que se ve funcionar junto lo que antes solo se había probado por partes: un comprobante `AUTORIZADO` en pantalla, la descarga del RIDE, el correo, la reemisión, las invitaciones entre dos personas y los avisos.

Lo que encontró y ya está corregido:

- En el editor de facturas la fila del impuesto decía solo "15%", sin la palabra IVA. Las pruebas de componente no lo veían porque su catálogo simulado no era el real.

Las capturas de cada pantalla se revisaron a la vista por primera vez (las guarda el informe de las pruebas). Lo que se vio para pulir se corrigió en el Plan 7c (2026-10-13):

| Pantalla | Qué se veía | Ahora |
|---|---|---|
| Editor de factura, detalle del comprobante, productos | Cantidades y precios con seis decimales (`2.000000`, `12.500000`) | `2` y `12.50`, sin redondear nunca |
| Editor de factura | La forma de pago no cabía en su lista y se cortaba | Ocupa el ancho del formulario |
| Productos | Al registrar, la tarifa de IVA elegida de antemano era 0 % (la primera del catálogo) | No viene elegida: hay que elegirla |
| Clientes, editor | Tipos de identificación y formas de pago en mayúsculas, tal como la ficha | Con mayúscula solo al principio, siglas intactas y la tilde que falta en el catálogo |
| Comprobantes, clientes, emisor | Número de factura, identificación y punto de emisión a la derecha, bajo un encabezado a la izquierda | A la izquierda, con cifras de ancho fijo |
| Certificado | El titular como viene en el certificado (`CN=…,O=…,C=EC`) | Su nombre común, con el nombre completo debajo |

**En un teléfono** (375 px de ancho) todas las pantallas principales se desplazaban de lado, de 80 a 426 px: la hoja no podía encoger por debajo del ancho de su contenido, y los textos para lectores de pantalla escapaban de las cajas de desplazamiento de las tablas. Corregido en el Plan 7c; una prueba con navegador mide el desplazamiento de ocho pantallas en un teléfono y guarda su captura. Las tablas anchas (detalle de la factura, intentos ante el SRI) se desplazan dentro de su caja.

Lo que estas pruebas no dicen: cómo se ve en Firefox o Safari, ni si el SRI real acepta lo que se emite.

## Desplegar

- **Imágenes**: `docker build --platform linux/amd64 -t einvoice-api .` y lo mismo con `einvoice-web` en el repositorio de la web. Se despliegan en Google Cloud Run (API, worker y web como tres servicios; cómo, en el doc 13). Ninguna corre como root. La de la API sirve también de worker: `EINVOICE_WORKER_ENABLED=false` en las instancias que solo atienden la API y `true` en al menos una. La imagen de la API lleva dentro su inventario de dependencias (SBOM).
- **Referencia de staging**: `infra/docker-compose.stack.yml` levanta API, worker y web sobre la infraestructura del compose; solo la web publica puerto. `infra/stack-smoke.sh` comprueba que todo está en pie. Comprobado el 2026-10-11 en local, salvo el inicio de sesión: necesita un proveedor de identidad con una dirección que valga igual para el navegador y para los contenedores.
- **Base de datos**: en producción, dos usuarios. `infra/postgres/roles.sql` crea el que migra (dueño de las tablas) y el de la aplicación, que solo lee y escribe filas y no puede retirar las defensas de la base. La aplicación usa el de la aplicación en `DATABASE_URL` y el que migra con `SPRING_FLYWAY_USER`/`SPRING_FLYWAY_PASSWORD`.
- **Variables**: doc 13 y `.env.example`. La web exige `https` en `APP_BASE_URL` fuera de la propia máquina.
- **CI**: cada repositorio tiene el suyo. En este (`.github/workflows/ci.yml`): pruebas, cobertura, SBOM, verificación de los XSD, las pruebas con navegador del recorrido completo (con la web clonada de su repositorio), y construcción de la imagen. En el de [la web](https://github.com/DevNation593/client-factura-facil): lint, tipos, pruebas, auditoría de dependencias, SBOM, imagen y que su copia del contrato sea la de este repositorio. No se usa Code Security de GitHub, así que las dependencias de Maven no tienen comprobación de vulnerabilidades en CI (las de npm sí, con `npm audit`). No publica imágenes ni despliega: la subida a Artifact Registry y el despliegue en Cloud Run se hacen a mano (doc 13).

## Checklist de preproducción (doc 10)

| Punto | Estado | Responsable |
|---|---|---|
| Prueba de penetración de la API y del aislamiento entre empresas | Pendiente. Hay pruebas automáticas de aislamiento, no una prueba externa | Responsable del producto |
| Simulación de robo de credenciales | Pendiente. Mitigaciones presentes: tokens solo en cookies cifradas, certificado cifrado y ligado a su emisor, usuario de base sin permisos de dueño | Responsable del producto |
| Rotación del certificado `.p12` | Comprobada (`CertificateRotationIT`). La rotación de la clave maestra no tiene procedimiento | Equipo técnico |
| Restauración total | Ensayada en local; pendiente en el entorno definitivo | Equipo técnico |
| Revisión de registros | Preparada (correlación, JSON, eventos del servidor web); pendiente dónde se recogen y quién los mira | Equipo técnico |
| Límites de peticiones | Por usuario, en la API (1200 por minuto, 300 de escritura; configurables). Si Redis no contesta en medio segundo (`REDIS_TIMEOUT`) las peticiones pasan sin contarse y se le deja descansar 10 segundos. Lo que llega sin autenticar lo debe frenar el proxy de entrada, que no existe aún | Equipo técnico |
| Análisis de dependencias | En CI: auditoría de las dependencias de la web (`npm audit`) y SBOM de las dos. Las dependencias Java no tienen análisis de vulnerabilidades: no se usa Code Security de GitHub | Equipo técnico |
| Política de privacidad, consentimientos y contratos de encargado | Pendiente | Responsable del producto |

## Lo que sigue abierto

- Certificado real y RUC habilitado (G3), y con ellos la primera activación de un emisor (G5).
- Requisitos legales del proveedor (G6).
- Servidor de correo de producción: sin él no se puede confirmar el correo de una cuenta ni recuperar una contraseña olvidada (doc 10).
- Un proxy de entrada con TLS delante de la web y de la API (los sistemas externos llaman a la API directamente). En la dirección pública de la API debe reemplazar `X-Forwarded-For`, no añadirse a él, porque la API limita por su primer valor, y conviene que publique solo `/api/v1/**` sin `/api/v1/auth/**`. La web llama a la API por su dirección interna, no a través de ese proxy (pone ella la IP del navegador en `X-Forwarded-For`; por el proxy, todos los ingresos compartirían un cupo), y el puerto interno de la API solo debe alcanzarlo la web y el proxy.
- Nube, registro de imágenes, gestor de claves y almacenamiento de objetos; con ellos, despliegue real, respaldos programados y recogida de métricas y registros.
- Mirar la aplicación en Firefox y Safari: las pruebas con navegador usan solo Chromium (en escritorio y en el ancho de un teléfono).
- Un enlace directo a un comprobante no dice de qué empresa es: quien trabaja en varias y lo abre con otra empresa activa ve "No se encontró ese comprobante en esta empresa".
- Los XSD oficiales de los demás comprobantes (nota de crédito, retención, guía): sin ellos el Plan 9 no puede empezar.
- Confirmar en la ficha qué pasa con el secuencial de un comprobante rechazado: hoy se reemite con número nuevo y el rechazado no se reutiliza (doc 05).
- Transferir la propiedad de una empresa.
- Webhooks: decidir si se encienden, y con qué salida a internet para el worker. El receptor ya puede consultar el detalle de un comprobante con una API key (Plan 11). El aviso de prueba, la purga del historial y el reparto entre empresas están hechos (Plan 6c).
