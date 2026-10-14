# 07 — Certificados digitales y firma XML XAdES

## Meta
Generar un XML conforme XSD SRI y aplicar la firma **XAdES-BES enveloped** exigida por el perfil técnico aplicable. Una firma XMLDSig genérica (`SignedXml`, `javax.xml.crypto`) **no** garantiza por sí sola cumplimiento XAdES.

## Certificados
- Importar `.p12/.pfx` por canal TLS; validar password, alias con clave privada, cadena, `notBefore/notAfter`, uso de clave y relación con emisor si corresponde.
- Cifrar contenido del P12 mediante envelope encryption (KMS), almacenar en S3 privado; secret en gestor externo; jamás en columnas en claro, `.env` o logs.
- Mantener registro de fingerprint, vigencia, emisor/tenant, versión, activación/rotación y revocación cuando aplique.
- Limitar memoria de buffers y permisos IAM; política de separación de funciones y auditoría de firma.

## Pipeline
1. Canonicalizar datos de negocio y serializar XML por versión; namespaces y orden según XSD.
2. Validar el XML sin firma con XSD.
3. Leer certificado en worker aislado con permisos mínimos.
4. Incorporar `ds:Signature` y propiedades firmadas XAdES (por ejemplo `SignedProperties`) de acuerdo con el perfil y algoritmos **especificados por ficha SRI**.
5. Comprobar referencias y digest de documento/properties mediante verificación criptográfica independiente.
6. Validar XML firmado según estrategia de esquemas y consumir prueba en ambiente del SRI.
7. Guardar bytes UTF-8 firmados, hash y versión; no modificar whitespace/campos después de firmar.

## Algoritmos / compatibilidad
La ficha histórica del SRI prescribe parámetros concretos de firma (XAdES-BES, versión/perfil y algoritmos). La ingeniería **no debe imponer SHA-256 ni reemplazar SHA-1 automáticamente** sin comprobar aceptación del SRI; igualmente documentar riesgo criptográfico y aislarlo tras interfaz actualizable. La implementación debe partir del texto completo de la ficha v2.34 y un fixture aceptado por SRI.

```java
public interface XmlSigner {
  SignedXml sign(UnsignedXml xml, CertificateReference certificate, SignatureProfile profile);
  SignatureVerification verify(SignedXml xml);
}
public record SignatureProfile(String xadesVersion, String digestAlgorithm, String signatureAlgorithm) {}
```

## Casos de prueba obligatorios
P12 erróneo, expirado, sin clave privada, distinto firmante, alias múltiple, XML manipulado después de firma, firma duplicada, namespace inconsistente, caracter UTF-8 especial, documento grande, firma verificada localmente pero rechazada por SRI, rotación concurrente.

**Gate:** `SRI_CERT_ACCEPTED_FIXTURE=true` para cada tipo/versión antes de producción.

## Perfil exigido por la ficha técnica v2.34 (leído el 2026-10-10)
- **Sección 6, tabla 7:** XAdES-BES, esquema 1.3.2 (`http://uri.etsi.org/01903/v1.3.2#`), UTF-8, firma *enveloped*.
- **6.4 y 6.5:** la firma cubre tres cosas: el comprobante, el elemento `SignedProperties` y el `KeyInfo` con el certificado firmante.
- **6.8:** algoritmo de firmado RSA-SHA1, clave de 2048 bits, archivo PKCS#12.
- **Anexo 14:** canonicalización C14N inclusiva (`REC-xml-c14n-20010315`); tres `ds:Reference` con digest SHA-1 (a `SignedProperties` con `Type="http://uri.etsi.org/01903#SignedProperties"`, a `KeyInfo` y a `#comprobante` con la transformación *enveloped*); `KeyInfo` con `X509Certificate` y `RSAKeyValue`; `SigningTime`, `SigningCertificate` (digest SHA-1 del certificado, emisor y número de serie) y `DataObjectFormat` con descripción `contenido comprobante` y tipo `text/xml`.

SHA-1 es débil, pero es lo que el SRI exige; no se sustituye por decisión propia. Si el SRI cambia el perfil, basta otra implementación de `XmlSigner`.

## Implementación actual
- **Firma:** `XadesBesSigner` construye la firma sobre la API XMLDSig del JDK y escribe los elementos XAdES y el `KeyInfo` reproduciendo el Anexo 14. No usa una biblioteca XAdES externa. Queda detrás de la interfaz `XmlSigner`.
- **Verificación:** `XadesVerifier` exige una sola firma hija de la raíz, exactamente las tres referencias del perfil con sus algoritmos, que el certificado de `KeyInfo` sea el declarado en `SigningCertificate`, y que firma y digests sean criptográficamente válidos. Nada pasa a `SIGNED` sin superar esa verificación y volver a validar contra el XSD. No comprueba cadena de confianza ni revocación.
- **Lectura del `.p12`:** `Pkcs12Inspector` usa la entrada con clave privada cuyo certificado declara el uso `digitalSignature` (los certificados ecuatorianos suelen traer además una clave de cifrado); si ninguna lo declara y hay una sola, esa. Rechaza: no legible o contraseña errónea, sin clave privada, más de una candidata, clave que no sea RSA de al menos 2048 bits, clave privada que no corresponde a su certificado (se comprueba firmando y verificando unos bytes), caducado y aún no vigente.
- **Custodia:** el `.p12` y su contraseña se guardan cifrados en la tabla `certificates` con cifrado de sobre AES-256-GCM (una clave de datos por secreto, envuelta con la clave maestra y ligada a la empresa, al emisor, al certificado y al campo). Se descifran solo en memoria al firmar. Hay un certificado activo por emisor; al cargar otro, el anterior queda como reemplazado. La vigencia se vuelve a comprobar en cada firma.
- **Fallos al firmar:** el documento queda en `SIGNATURE_FAILED` conservando su XML sin firmar, y el motivo se guarda en el historial de estados: el código de rechazo del certificado (`CERTIFICATE_MISSING`, `CERTIFICATE_EXPIRED`, …), `CERTIFICATE_UNDECRYPTABLE` si el certificado guardado no se puede descifrar (clave maestra distinta o registro alterado), `SIGNING_ERROR` ante un fallo inesperado del firmador, o `SIGNATURE_NOT_VERIFIABLE` si la firma producida no supera la verificación. Reintentar con el mismo motivo no añade eventos; con uno distinto, sí.

## Lo que sigue pendiente
- **Aceptación del SRI.** La firma se ha verificado con certificados de prueba autofirmados generados con `keytool`. El gate `SRI_CERT_ACCEPTED_FIXTURE` sigue abierto hasta autorizar un comprobante en el ambiente de certificación con un certificado real.
- **KMS.** La clave maestra llega por la variable `EINVOICE_MASTER_KEY`; es un sustituto local. En producción debe venir de un gestor de claves, y falta la rotación.
- **Relación certificado–RUC**, cadena de confianza, revocación (OCSP/CRL) y aviso de vencimiento.

## Fuentes oficiales y control de cambios
- [SRI — Facturación electrónica](https://www.sri.gob.ec/facturacion-electronica): ficha técnica esquema off-line v2.34 (julio 2026), XSD y normativa.
- [SRI — registro de proveedores (28-07-2026)](https://www.sri.gob.ec/detalle-noticias?idnoticia=1318&marquesina=1): Resolución NAC-DGERCGC26-00000027.
- [SRI — adhesión y firma](https://www.sri.gob.ec/herramienta-de-comprobantes-electronicos).

> **Límite jurídico/técnico:** estos documentos son una especificación de ingeniería, no sustituyen los XSD oficiales, la ficha técnica integral, las resoluciones publicadas en Registro Oficial ni asesoría tributaria. Antes de pasar a producción, congelar versiones de normativa, XSD y catálogos, y ejecutar la matriz de conformidad con el ambiente de certificación del SRI. El detalle y fechas de entrada en vigor de cada reforma deben corroborarse en su texto normativo publicado.
