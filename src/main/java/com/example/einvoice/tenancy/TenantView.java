package com.example.einvoice.tenancy;

import java.util.UUID;

/** Un tenant visto por uno de sus miembros. */
public record TenantView(UUID id, String name, TenantStatus status, Role role) {
}
