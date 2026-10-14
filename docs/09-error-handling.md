# 09 — Gestión de errores y resiliencia

## Tipos de fallo
| Clase | Ejemplos | ¿Reintentar? |
|---|---|---|
| Validación | IVA inválido, campo XML obligatorio omitido | No; corregir borrador |
| Certificado | P12 incorrecto, expirado | No automático |
| Firma | Digest mal generado, propiedades XAdES incorrectas | No automático hasta corrección |
| SRI funcional | DEVUELTA / NO AUTORIZADO | Según código y ficha; intervención o subsanación |
| SRI temporal | HTTP 5xx, timeout, red cortada | Sí **tras consultar estado** |
| Persistencia | Deadlock, inconsistencia secuencial | Retentar transacción bajo política y con idempotencia |
| Entrega | SMTP temporal, rebote | Sí con cola propia; sin tocar autorización SRI |

## Estrategia RabbitMQ

Implementado: exchange `fiscal.events` (topic), cola `document.issue` y su cola de mensajes muertos `document.issue.dlq` (por el exchange `fiscal.dlx`). Un relevo publica el `outbox` con confirmación del broker y solo entonces marca el evento como despachado; un evento puede llegar repetido y el worker lo tolera, porque decide según el estado en que encuentra el documento.

- **Mensaje:** `eventId`, `tenantId`, `documentId` y `eventType`. Nunca el `.p12`, el XML ni datos del cliente.
- **Reintentos ante el SRI:** no usan colas diferidas. El pipeline anota en el documento cuándo toca el siguiente intento (`next_attempt_at`) y un planificador lo devuelve a la cola al vencer. El mismo mecanismo reconcilia los envíos que se quedaron sin resultado, así que no hay una cola `sri.reconcile` aparte.
- **Fallo del worker:** una excepción inesperada se reintenta tres veces con espera creciente y después el mensaje pasa a `document.issue.dlq`. El documento no se pierde: sigue en su estado, el planificador lo devuelve a la cola al vencer su plazo (5 minutos para una factura recién emitida) y también se puede reactivar con `POST /documents/{id}/retry`. Si el fallo persiste, cada vuelta deja otro mensaje en la DLQ: esa cola hay que vigilarla. Nadie consume la DLQ: es para el operador.
- **Varias instancias:** el relevo y el planificador toman filas con `FOR UPDATE SKIP LOCKED`, y el pipeline bloquea el documento en cada paso. `EINVOICE_WORKER_ENABLED=false` deja una instancia solo como API.
- **Entrega:** cola `document.deliver` con su `document.deliver.dlq`. El relevo encamina por tipo de evento: `DOCUMENT_AUTHORIZED` y los `DELIVERY_*` van a la de entrega; el resto, a `document.issue`. Los reintentos de correo siguen el mismo esquema que los del SRI: `deliveries.next_attempt_at` y un planificador que los devuelve a la cola. Un fallo de entrega nunca cambia el estado fiscal, y tampoco manda el mensaje a la DLQ: queda anotado en la entrega (`last_error`) y se reintenta o se da por fallida. A `document.deliver.dlq` solo llega lo que no se pudo ni empezar (un mensaje ilegible, o un fallo al archivar el comprobante).
- **Red de seguridad del archivo:** un comprobante `AUTHORIZED` queda programado hasta que tiene su RIDE; si el evento de autorización se pierde, se vuelve a emitir a los 5 minutos.
- **Pendiente:** alertas sobre las dos DLQ.

## Regla crítica: envío incierto
Si `validarComprobante` tarda en responder o la conexión cae **después** de transmitir, no hay certeza de si recibió. Consultar `autorizacionComprobante(clave)` y reconciliar antes de decidir reenvío. Evitar crear una segunda factura o segunda clave por error de red. Código de procesamiento (p.ej. código 70 donde aplique) se maneja consultando y esperando, no rehaciendo el comprobante.

## Respuestas API
Usar `application/problem+json` con `type`, `title`, `status`, `detail` sanitizado, `instance`, `correlationId`, `errorCode`. `400` malformado, `401` sin sesión, `403` prohibido, `404` ocultar tenants ajenos, `409` conflicto idempotencia/secuencia, `422` regla fiscal, `429` rate limit, `503` falla dependencias. La API puede confirmar **aceptación para procesamiento**, no autorización fiscal hasta que el estado cambie.

## Observabilidad
Métricas colas y DLQ, lag, edad de comprobantes pendientes, latencia SRI, ratio de documentos devueltos, agotamiento de secuenciales, certificado por expirar y correo no entregado. Alertar si documento supera umbral operacional de transmisión definido por normas vigentes.

## Runbook resumido

1. Encontrar el comprobante (`GET /documents/{id}`): estado, clave de acceso y, si la hay, autorización.
2. Leer sus intentos ante el SRI y los mensajes devueltos: `GET /documents/{id}/sri-attempts`.
3. Si quedó sin firmar (`SIGNATURE_FAILED`), revisar el certificado del emisor (`GET /certificates?taxpayerId=`).
4. Verificar XML, hash y ambiente con los artefactos (`GET /documents/{id}/artifacts/xml-signed`).
5. Reactivarlo con `POST /documents/{id}/retry` si está en `SIGNATURE_FAILED`, `SIGNED`, `SENDING` o `RECEIVED`. El reintento nunca reenvía a ciegas: un envío incierto se reconcilia antes consultando al SRI.
6. `RETURNED` y `NOT_AUTHORIZED` no se reintentan tal cual: el comprobante hay que corregirlo. No se edita ni se reenvía con su clave; se emite otro que lo reemplaza ("Corregir y emitir de nuevo" en su pantalla, o `replacesDocumentId` al emitir), y ambos quedan enlazados. Lo mismo vale para `XSD_INVALID`, que tampoco admite reintento. Solo se reemplaza un comprobante en uno de esos tres estados, una sola vez y con otro del mismo emisor (`409 REPLACED_DOCUMENT_NOT_REJECTED`, `DOCUMENT_ALREADY_REPLACED`, `REPLACED_DOCUMENT_OTHER_ISSUER`).
7. Revisar la cola `document.issue.dlq` si un comprobante no avanza y no tiene intentos recientes, y `document.deliver.dlq` si está autorizado pero sin RIDE.
8. Si el cliente no recibió el correo: `GET /documents/{id}/deliveries` muestra estado, intentos y último error; `POST /documents/{id}/deliveries` lo envía de nuevo, a la misma o a otra dirección.
9. Cerrar el incidente con su registro en auditoría.

## Fuentes oficiales y control de cambios
- [SRI — Facturación electrónica](https://www.sri.gob.ec/facturacion-electronica): ficha técnica esquema off-line v2.34 (julio 2026), XSD y normativa.
- [SRI — registro de proveedores (28-07-2026)](https://www.sri.gob.ec/detalle-noticias?idnoticia=1318&marquesina=1): Resolución NAC-DGERCGC26-00000027.
- [SRI — adhesión y firma](https://www.sri.gob.ec/herramienta-de-comprobantes-electronicos).

> **Límite jurídico/técnico:** estos documentos son una especificación de ingeniería, no sustituyen los XSD oficiales, la ficha técnica integral, las resoluciones publicadas en Registro Oficial ni asesoría tributaria. Antes de pasar a producción, congelar versiones de normativa, XSD y catálogos, y ejecutar la matriz de conformidad con el ambiente de certificación del SRI. El detalle y fechas de entrada en vigor de cada reforma deben corroborarse en su texto normativo publicado.
