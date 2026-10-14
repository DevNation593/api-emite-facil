package com.example.einvoice.sri;

/** Error o advertencia que el SRI devuelve junto a una respuesta, tal como llegó. */
public record SriMessage(String identifier, String message, String additionalInfo, String type) {
}
