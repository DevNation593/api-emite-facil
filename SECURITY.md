# Política de seguridad — API de facturación electrónica

## Versiones con soporte

| Rama | Soporte |
|---|---|
| `main` | Sí: es lo que se despliega. |
| `qa`, `dev` | Se corrigen, pero no se publican avisos por separado. |
| Otras ramas | No. |

## Cómo informar de una vulnerabilidad

No abras un issue público ni un pull request que describa el fallo.

Infórmalo de forma privada desde la pestaña **Security** del repositorio, con **Report a vulnerability**. Incluye:

- Qué componente está afectado (endpoint, módulo, worker, migración).
- Los pasos para reproducirlo y qué consigue quien lo aprovecha.
- La rama o el commit en el que lo comprobaste.

Te responderemos con una primera valoración y te avisaremos cuando el arreglo esté desplegado.

### Lo que no hay que hacer al investigar

- Pruebas contra el SRI real, contra datos de clientes o contra una instalación que no sea tuya. Usa el entorno local de `docs/DEVELOPMENT.md` y el SRI simulado de `e2e/`.
- Pruebas de denegación de servicio o de fuerza bruta contra entornos compartidos.
- Usar certificados de firma electrónica que no sean tuyos.

## Modelo de amenazas

Esta sección es la guía de seguridad para revisar cambios, también las revisiones automáticas. El detalle de cada control está en [docs/10-security.md](docs/10-security.md).

### Lo que se protege

- **Firma electrónica de cada emisor** (`.p12` y su contraseña). Con ella se emiten comprobantes válidos ante el SRI a nombre del emisor.
- **Comprobantes emitidos**: XML firmado, autorización, mensajes del SRI y RIDE. Tienen valor fiscal y se conservan años.
- **Datos de cada empresa**: clientes, productos, importes, emisores y su configuración.
- **Credenciales**: contraseñas (Argon2id), tokens de acceso (JWT de 15 minutos), tokens de renovación, API keys y secretos de webhooks.
- **Numeración**: secuenciales y claves de acceso, que no pueden repetirse ni saltarse.

### Fronteras de confianza

- **Quien llama a la API**, sea una persona con token o un sistema con `X-Api-Key`. La empresa sale siempre de la credencial verificada; un `X-Tenant-Id` solo selecciona entre las empresas en las que la persona tiene membresía.
- **La aplicación web**, que llama a la API por su dirección interna y le pasa la IP del navegador en `X-Forwarded-For`. Solo vale la entrada en la posición `EINVOICE_TRUSTED_PROXY_HOPS` contando desde la derecha.
- **El SRI**: sus respuestas SOAP son entrada no confiable. Se validan, se guardan tal cual y nunca se inventan.
- **Receptores de webhooks**: direcciones escritas por los clientes. El worker solo llama a direcciones públicas por `https` y no lee el cuerpo de la respuesta.
- **RabbitMQ y el `outbox`**: el worker recupera el contexto del documento persistido, nunca de una cabecera del mensaje.

### Invariantes que un cambio no puede romper

1. Una empresa nunca ve ni toca datos de otra.
2. Ningún secreto (`.p12`, contraseña del certificado, clave maestra, tokens, API keys, secretos de webhooks) sale en respuestas, logs, auditoría, `outbox`, RabbitMQ ni métricas.
3. Lo firmado, autorizado o devuelto por el SRI no se modifica ni se borra: artefactos, `audit_events` y `state_events` son de solo inserción.
4. Un comprobante se emite una sola vez por `Idempotency-Key`, y cada secuencial se usa una sola vez.
5. Tras un timeout del SRI no se reemite ni se cambia la clave sin consultar antes la autorización.
6. Ningún XML con `DOCTYPE` se procesa, y la validación XSD no sale a la red.
7. Un emisor solo transmite a producción con la activación de su propietario y con el ambiente habilitado en `SRI_ENABLED_ENVIRONMENTS`.

### Límites aceptados

Son riesgos conocidos y documentados. No se informan como vulnerabilidad salvo que un cambio los amplíe:

- La clave maestra es una variable de entorno (`EINVOICE_MASTER_KEY`), no un KMS.
- Un token de acceso sigue valiendo hasta 15 minutos después de suspender la cuenta o cambiar la contraseña.
- Si Redis no responde, los límites de intentos y de peticiones no se aplican.
- No hay MFA ni lista de sesiones abiertas.
- Las direcciones de los webhooks se guardan en claro y van en los respaldos.
- Quien sea dueño de las tablas puede retirar los disparadores de solo inserción; en producción `infra/postgres/roles.sql` separa al dueño del usuario que opera.
