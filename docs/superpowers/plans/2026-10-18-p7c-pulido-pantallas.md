# Plan 7c — Pulido de las pantallas y uso en teléfono

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** corregir lo que la primera revisión de las pantallas encontró (doc 15, "Recorrido en un navegador") y que la aplicación se pueda usar en un teléfono: hoy, a 375 px de ancho, todas las pantallas principales se desplazan de lado (entre 80 y 426 px).

**Architecture:** cambios solo en el frontend: hoja de estilos, dos funciones de presentación en `src/lib/` y las pantallas que las usan. Cada punto con su prueba de componente; el teléfono, con una prueba con navegador que mide el desplazamiento lateral de cada pantalla principal.

**Tech Stack:** el de los Planes 7 y 7b; nada nuevo.

**Spec:** [doc 15](../../15-produccion.md) (tabla de "Recorrido en un navegador" y "Lo que sigue abierto": "mirar la aplicación en un móvil"), [frontend/README](../../../frontend/README.md) ("Solo se ha visto en Chromium y en tamaño de escritorio").

**Rama:** `p7c-pulido-pantallas`, salida de `dev`.

## Global Constraints

- **Nada cambia en lo que se envía a la API ni en lo que se guarda**: solo cómo se muestra. Importes y cantidades siguen siendo cadenas decimales; la presentación no usa `number` para ellos.
- Textos de interfaz en español; código en inglés. Selectores de las pruebas por rol y nombre accesible.
- Comandos: frontend `npm run lint && npm run typecheck && npm test && npm run build`; navegador `npm run e2e` en `/e2e` con la pila levantada.

## Decisiones de diseño

- **Cantidades y precios** se muestran sin ceros de más: la cantidad tal cual se escribiría (`2`, `1.5`), el precio unitario con al menos dos decimales (`12.50`, `0.125`). Los importes (subtotales, impuestos, totales) ya vienen con dos y no cambian.
- **La tarifa de IVA de un producto no viene elegida**: la lista empieza en "Elija la tarifa" y registrar sin elegirla lo impide con un mensaje. Elegir por defecto la primera del catálogo (0 %) hacía fácil registrar mal un producto, y elegir la general fijaría en el código una tarifa que el SRI cambia.
- **Los nombres de los catálogos** (tipos de identificación, formas de pago) se muestran con mayúscula solo al principio ("Cédula", "Sin utilización del sistema financiero"), sin cambiar el catálogo: la ficha los da en mayúsculas y sin tildes. La forma de pago ocupa todo el ancho del formulario.
- **Identificadores** (número de factura, identificación, punto de emisión) alineados a la izquierda como su encabezado, con cifras de ancho fijo; solo las cantidades e importes van a la derecha.
- **El titular del certificado** se muestra por su nombre común (`CN`); el nombre completo queda como ayuda debajo.
- **En un teléfono** la hoja puede encoger por debajo del ancho de su contenido; las tablas anchas se desplazan dentro de su caja (`.table-scroll`), nunca la página; las cadenas largas sin espacios (clave de acceso, direcciones) se parten.

## Review Focus

1. **Un precio con más de dos decimales** (`0.125`) → se muestra completo, no redondeado (Task 2).
2. **Una cantidad decimal** (`1.500000`) → `1.5`, no `2` ni `1.500000` (Task 2).
3. **Una forma de pago o tipo de identificación con siglas o tildes** → se respeta lo que no es mayúscula de la ficha; solo se baja el resto (Task 3).
4. **Un certificado sin `CN`** → se muestra el nombre completo, no un vacío (Task 4).
5. **Una tabla de muchas columnas en un teléfono** (detalle de la factura, intentos ante el SRI) → se desplaza dentro de su caja y la página no (Task 1).

---

### Task 1: Las pantallas caben en un teléfono

**Files:** Modify `frontend/src/app/globals.css` y las pantallas cuyas tablas no estén dentro de `.table-scroll`; Create `e2e/tests/movil.spec.ts`

- [x] **Step 1:** `movil.spec.ts` (375 × 812, táctil): comprobantes, detalle de un comprobante autorizado, nueva factura, clientes, productos, emisor, equipo e integraciones; en cada una, `scrollWidth - clientWidth` del documento es 0 y el título se ve. Captura de cada una. Verla fallar (hoy: 80 a 426 px).
- [x] **Step 2:** en la regla de pantallas estrechas, la columna de la hoja es `minmax(0, 1fr)`; las tablas sin caja de desplazamiento la reciben; `.facts dd` y los botones de archivos se parten o pasan de línea.
- [x] **Step 3:** compilar la web, reiniciar los servidores de prueba y ver la prueba en verde; mirar las ocho capturas.
- [x] **Step 4:** commit `fix(frontend): las pantallas caben en un teléfono`.

### Task 2: Cantidades y precios sin ceros de más

**Files:** Modify `frontend/src/lib/decimal.ts` (+ test), `frontend/src/app/(app)/documentos/[id]/page.tsx`, `facturas/nueva/page.tsx`, `productos/page.tsx` y sus pruebas.

**Interfaces — Produces:** `plainDecimal(value: string): string` (quita ceros finales y el punto si sobra), `formatUnitPrice(value: string): string` (como `plainDecimal`, con al menos dos decimales).

- [x] **Step 1:** pruebas: `plainDecimal("2.000000") === "2"`, `"1.500000" → "1.5"`, `"10" → "10"`, `"0.000000" → "0"`; `formatUnitPrice("12.500000") === "12.50"`, `"0.125000" → "0.125"`, `"7" → "7.00"`. Pantallas: el detalle muestra `2` y `12.50`; la lista de productos `12.50`; al elegir un producto en el editor, su precio aparece como `12.50`.
- [x] **Step 2:** implementarlas y usarlas; el `plain` local del detalle desaparece.
- [x] **Step 3:** commit `fix(frontend): cantidades y precios sin ceros de más`.

### Task 3: Catálogos legibles y la tarifa de IVA que no viene elegida

**Files:** Create `frontend/src/lib/catalogLabels.ts` (+ test); Modify `clientes/page.tsx`, `productos/page.tsx`, `facturas/nueva/page.tsx` y sus pruebas.

**Interfaces — Produces:** `catalogLabel(name: string): string`.

- [x] **Step 1:** pruebas: `catalogLabel("CÉDULA") === "Cédula"`, `"VENTA A CONSUMIDOR FINAL" → "Venta a consumidor final"`, `"SIN UTILIZACION DEL SISTEMA FINANCIERO" → "Sin utilización del sistema financiero"`, `"RUC" → "RUC"` (siglas conocidas: RUC), `"Exento de IVA" → "Exento de IVA"` (lo que ya no está todo en mayúsculas no se toca). Productos: la tarifa empieza en "Elija la tarifa" y registrar sin elegirla muestra "Elija la tarifa de IVA." sin llamar a la API. Editor: la forma de pago ocupa el ancho del formulario.
- [x] **Step 2:** implementar. Las tildes que la ficha omite se reponen solo para las palabras de los catálogos sembrados que las omiten (hoy solo `UTILIZACION`), en una tabla dentro de `catalogLabel`.
- [x] **Step 3:** commit `fix(frontend): catálogos legibles y la tarifa de IVA se elige`.

### Task 4: Identificadores a la izquierda y titular del certificado

**Files:** Modify `globals.css` (clase `code`), `documentos/page.tsx`, `clientes/page.tsx`, `configuracion/emisor/page.tsx`, `configuracion/certificado/page.tsx`, `src/lib/` (`commonName`) y pruebas.

**Interfaces — Produces:** `commonName(distinguishedName: string): string`.

- [x] **Step 1:** pruebas: `commonName("CN=Emisor de Pruebas,O=Pruebas,C=EC") === "Emisor de Pruebas"`, `"CN=PEREZ\\, JUAN,O=X" → "PEREZ, JUAN"`, `"O=Solo Organización,C=EC" → "O=Solo Organización,C=EC"`. La tabla del certificado muestra el nombre común y, debajo, el nombre completo.
- [x] **Step 2:** identificadores con clase `code` (cifras de ancho fijo, alineación a la izquierda) en vez de `num`.
- [x] **Step 3:** commit `fix(frontend): identificadores alineados y titular del certificado legible`.

### Task 5: Verificación y documentación

- [x] **Step 1:** frontend completo; suite con navegador completa; mirar las capturas de escritorio y de teléfono.
- [x] **Step 2:** doc 15 (la tabla de lo que había que pulir pasa a "corregido" y se añade el teléfono), frontend/README (límites), plan maestro (fila P7c). Commit `docs: pulido de las pantallas y uso en teléfono`.
