package com.example.einvoice.identity;

import java.util.UUID;

/**
 * Usuario autenticado de la petición: identidad del proveedor OIDC ya asociada a su fila en users.
 *
 * @param emailVerified lo que el proveedor de identidad dice del correo; nulo si no dice nada
 */
public record UserIdentity(UUID userId, String issuer, String subject, String email, Boolean emailVerified) {
}
