# 08 — Servicios SOAP oficiales del SRI

## Endpoints publicados (esquema Offline)
| Ambiente | Servicio | WSDL |
|---|---|---|
| Certificación | Recepción | `https://celcer.sri.gob.ec/comprobantes-electronicos-ws/RecepcionComprobantesOffline?wsdl` |
| Certificación | Autorización | `https://celcer.sri.gob.ec/comprobantes-electronicos-ws/AutorizacionComprobantesOffline?wsdl` |
| Producción | Recepción | `https://cel.sri.gob.ec/comprobantes-electronicos-ws/RecepcionComprobantesOffline?wsdl` |
| Producción | Autorización | `https://cel.sri.gob.ec/comprobantes-electronicos-ws/AutorizacionComprobantesOffline?wsdl` |

## Operaciones
- **Recepción**: `validarComprobante(xml)` envía XML firmado (bytes según WSDL); respuesta con estado general y comprobantes/mensajes. `RECIBIDA` indica recepción, **no** autorización.
- **Autorización**: `autorizacionComprobante(claveAccesoComprobante)` consulta estado; parsear respuesta y todas sus entradas, incluidas fecha, estado, autorización y mensajes.
- El contrato se cotejó con los dos WSDL oficiales el 2026-10-11 (ver "Implementación actual"). Si el SRI los cambia hay que volver a cotejar.

## Interfaz del cliente
```java
public interface SriGateway {
  ReceptionResult receive(SriEnvironment environment, byte[] signedXml);
  AuthorizationResult authorize(SriEnvironment environment, String accessKey);
}
```
`SriEnvironment` es una enumeración; la dirección sale de la configuración validada al arrancar, jamás de una URL enviada por un cliente de la API. TLS con el almacén de confianza del JDK; no se fijan certificados del servidor (la ficha, 7.1, advierte que pueden cambiar sin aviso). Tiempos de espera configurables y ninguna repetición automática a ciegas del envío a recepción.

## Normalización de respuestas
| Recepción | Acción |
|---|---|
| RECIBIDA | Guardar respuesta; iniciar consulta de autorización |
| DEVUELTA | Guardar todos los mensajes/códigos; clasificar subsanación |
| Sin respuesta/timeout | Resultado incierto: consultar por clave y aplicar política de reconciliación |

| Autorización | Acción |
|---|---|
| AUTORIZADO | Registrar autorización, bytes y metadatos; verificar correlación |
| NO AUTORIZADO | Persistir motivos íntegros, detener entrega como autorizado |
| En procesamiento / aún no existe | Reconsultar con backoff; no inventar resultado |

## Resiliencia
Backoff exponencial con jitter, límites de reintento, circuit breaker cuando corresponda, reconciliación diaria, dead-letter queue y operador. Timeout y rechazo funcional no son equivalentes. Algunas respuestas pueden contener múltiples autorizaciones; procesar por clave y fecha bajo reglas oficiales.

## Requisitos de configuración

| Variable | Por defecto | Para qué |
|---|---|---|
| `SRI_ENABLED_ENVIRONMENTS` | vacío | Ambientes en los que se transmite: vacío (nada), `1` (pruebas) o `1,2`. |
| `SRI_CONNECT_TIMEOUT`, `SRI_READ_TIMEOUT` | `10s`, `60s` | Tiempos de espera de cada llamada. |
| `SRI_AUTHORIZATION_DELAY` | `3s` | Espera entre `RECIBIDA` y la primera consulta de autorización (ficha, 7.4). |
| `SRI_RETRY_BACKOFF`, `SRI_RETRY_MAX` | `15s`, `40` | Espera inicial entre reintentos (se duplica hasta 1 hora) y número máximo. |
| `EINVOICE_WORKER_ENABLED` | `true` | Si esta instancia procesa comprobantes en segundo plano. |

Las direcciones de los servicios están en `application.yml` (`einvoice.sri.test.*` y `einvoice.sri.production.*`) con los valores oficiales. Al arrancar se comprueba que sean `https` y de `celcer.sri.gob.ec` o `cel.sri.gob.ec`; `einvoice.sri.allow-custom-endpoints=true` levanta esa comprobación y solo debe usarse con un SRI simulado.

## Implementación actual
- **Contrato (WSDL leídos el 2026-10-11).** SOAP 1.1 document/literal, `soapAction` vacío, elementos hijos sin espacio de nombres. Recepción: `{http://ec.gob.sri.ws.recepcion}validarComprobante` con `xml` en Base64. Autorización: `{http://ec.gob.sri.ws.autorizacion}autorizacionComprobante` con `claveAccesoComprobante`.
- **Cliente.** `HttpSriGateway`, detrás de `SriGateway`, escrito sobre el cliente HTTP del JDK: **no** se generan clases desde el WSDL (son dos operaciones y la generación exigía añadir JAX-WS y versionar los WSDL). No sigue redirecciones, no reintenta por su cuenta, acota el tiempo total de cada llamada y lee como mucho 2 MB de respuesta. Nunca lanza por fallos de red: devuelve un resultado "sin respuesta válida".
- **Lectura de respuestas.** `SriResponseParser` usa el parser seguro (sin DOCTYPE ni acceso externo). Lo que no entiende —HTML de error, `Fault`, estado desconocido, autorización sin número o sin fecha— es un intento no concluyente, nunca un estado inventado. De varias autorizaciones manda la `AUTORIZADO`; si no hay, la no autorizada más reciente.
- **Estados de autorización.** `AUTORIZADO`; `NO AUTORIZADO` y `RECHAZADO` (el que usa el ejemplo de la ficha) cierran el comprobante como no autorizado; cualquier otro valor se trata como "en procesamiento" y se vuelve a consultar.
- **Reenvío.** Tras un envío incierto solo se reenvía si el SRI responde, **sobre esa misma clave de acceso**, que no tiene ninguna autorización; una respuesta vacía o sobre otra clave no cuenta.
- **Correlación.** Una autorización solo se acepta si `claveAccesoConsultada` es la clave del comprobante y `ambiente` es el suyo; si no, el intento queda como `MISMATCH` con la respuesta guardada.
- **Códigos de la sección 11 de la ficha con tratamiento propio.** 43 (clave de acceso registrada) y 70 (clave en procesamiento): el SRI ya tiene el comprobante, se pasa a consultar la autorización. Todo otro `DEVUELTA` —incluido 45, secuencial registrado— deja el comprobante en `RETURNED` con sus mensajes.
- **Reintentos.** La espera se duplica en cada intento (15 s, 30 s, 1 min… hasta 1 hora) con una variación de ±20 %. Con los valores por defecto (40 intentos) se insiste entre 26 y 38 horas, que cubre las 24 horas que la ficha da al SRI. Agotados, el comprobante deja de reintentarse solo, queda un evento `SRI_RETRIES_EXHAUSTED` en su historial y se reactiva con `POST /documents/{id}/retry`.
- **Evidencia.** Cada llamada queda en `sri_attempts` con su estado literal, código HTTP, mensajes y respuesta; la respuesta que autoriza se guarda como artefacto del comprobante.

## Lo que sigue pendiente
- **Certificación real.** No se ha hecho ninguna llamada a los servicios del SRI con un comprobante: falta una firma electrónica válida y un RUC habilitado en el ambiente de pruebas.
- **Circuit breaker.** No hay: si el SRI cae, cada comprobante reintenta con su propia espera creciente.
- **Lotes** (`autorizacionComprobanteLote`), consulta de validez y anulación.

## Fuentes oficiales y control de cambios
- [SRI — Facturación electrónica](https://www.sri.gob.ec/facturacion-electronica): ficha técnica esquema off-line v2.34 (julio 2026), XSD y normativa.
- [SRI — registro de proveedores (28-07-2026)](https://www.sri.gob.ec/detalle-noticias?idnoticia=1318&marquesina=1): Resolución NAC-DGERCGC26-00000027.
- [SRI — adhesión y firma](https://www.sri.gob.ec/herramienta-de-comprobantes-electronicos).

> **Límite jurídico/técnico:** estos documentos son una especificación de ingeniería, no sustituyen los XSD oficiales, la ficha técnica integral, las resoluciones publicadas en Registro Oficial ni asesoría tributaria. Antes de pasar a producción, congelar versiones de normativa, XSD y catálogos, y ejecutar la matriz de conformidad con el ambiente de certificación del SRI. El detalle y fechas de entrada en vigor de cada reforma deben corroborarse en su texto normativo publicado.
