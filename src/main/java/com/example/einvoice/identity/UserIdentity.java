package com.example.einvoice.identity;

import java.util.UUID;

/**
 * Usuario autenticado de la petición, tal como está ahora en la base.
 *
 * @param sessionId sesión con la que obtuvo el token de acceso
 * @param emailVerified si ya confirmó su correo con el enlace que se le envió
 */
public record UserIdentity(UUID userId, UUID sessionId, String email, boolean emailVerified) {
}
