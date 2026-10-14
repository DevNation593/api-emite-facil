# 06 — Clave de acceso (49 dígitos) y módulo 11

## Composición obligatoria
| Campo | Tamaño | Ejemplo sintético |
|---|---:|---|
| Fecha emisión ddMMyyyy | 8 | 07102026 |
| Tipo comprobante | 2 | 01 |
| RUC emisor | 13 | 1790012345001 (ilustrativo, no RUC validado) |
| Ambiente | 1 | 1 certificación / 2 producción |
| Establecimiento | 3 | 001 |
| Punto emisión | 3 | 002 |
| Secuencial | 9 | 000000123 |
| Código numérico | 8 | 12345678 |
| Tipo emisión | 1 | 1 (normal; verificar catálogo ficha vigente) |
| Dígito verificador | 1 | cálculo módulo 11 |

8+2+13+1+3+3+9+8+1 = 48; agregar DV produce 49. Conservar ceros iniciales: todo es `String`. Código numérico de 8 dígitos generado con `SecureRandom` y persistido una sola vez.

## Algoritmo
Multiplicar cada dígito de los **48 dígitos base** de derecha a izquierda por factores cíclicos `2,3,4,5,6,7`; sumar; `11 - (suma % 11)`; si resultado es 11 => 0; si resultado es 10 => 1; de lo contrario usar resultado. Validar implementación con ejemplos oficiales y pruebas de vectores conocidos antes de certificación.

```java
public final class AccessKey {
  private AccessKey() {}
  public static String build(String base48) {
    if (base48 == null || !base48.matches("[0-9]{48}"))
      throw new IllegalArgumentException("Se requieren exactamente 48 dígitos");
    int sum = 0, factor = 2;
    for (int i = base48.length() - 1; i >= 0; i--) {
      sum += (base48.charAt(i) - '0') * factor;
      factor = factor == 7 ? 2 : factor + 1;
    }
    int dv = 11 - (sum % 11);
    if (dv == 11) dv = 0;
    else if (dv == 10) dv = 1;
    return base48 + dv;
  }
  public static boolean valid(String key) {
    return key != null && key.matches("[0-9]{49}")
        && build(key.substring(0,48)).equals(key);
  }
}
```

## Vectores de prueba sintéticos
Calculados con el algoritmo anterior sobre el ejemplo de la tabla (ambiente 1), variando solo el secuencial. **No son claves emitidas ni vectores oficiales del SRI**: sirven para fijar la implementación y cubren los dos casos especiales del dígito verificador. Antes de certificar, añadir claves reales autorizadas en el ambiente de certificación.

| Secuencial | Suma | `11 - (suma % 11)` | DV | Clave de 49 dígitos |
|---|---:|---:|---:|---|
| 000000123 | 467 | 6 | 6 | `0710202601179001234500110010020000001231234567816` |
| 000000006 | 463 | 10 | 1 | `0710202601179001234500110010020000000061234567811` |
| 000000008 | 473 | 11 | 0 | `0710202601179001234500110010020000000081234567810` |

## Reglas de concurrencia
- Reservar secuencial fiscal en transacción (doc 04) y generar clave dentro de esa transacción, antes de publicar a RabbitMQ.
- Restricciones de unicidad global de `access_key` y compuesta por ámbito fiscal.
- No confundir `ambiente` ni establecer `fecha emisión` desde `created_at` UTC sin convertir a zona fiscal Ecuador (`America/Guayaquil`).
- No reutilizar clave entre ambientes. No regenerar por timeout SOAP.
- Validar fecha real, tipo de documento, RUC de 13 caracteres, códigos habilitados y secuencial <= 999999999 antes de formar la cadena.

## Fuentes oficiales y control de cambios
- [SRI — Facturación electrónica](https://www.sri.gob.ec/facturacion-electronica): ficha técnica esquema off-line v2.34 (julio 2026), XSD y normativa.
- [SRI — registro de proveedores (28-07-2026)](https://www.sri.gob.ec/detalle-noticias?idnoticia=1318&marquesina=1): Resolución NAC-DGERCGC26-00000027.
- [SRI — adhesión y firma](https://www.sri.gob.ec/herramienta-de-comprobantes-electronicos).

> **Límite jurídico/técnico:** estos documentos son una especificación de ingeniería, no sustituyen los XSD oficiales, la ficha técnica integral, las resoluciones publicadas en Registro Oficial ni asesoría tributaria. Antes de pasar a producción, congelar versiones de normativa, XSD y catálogos, y ejecutar la matriz de conformidad con el ambiente de certificación del SRI. El detalle y fechas de entrada en vigor de cada reforma deben corroborarse en su texto normativo publicado.
