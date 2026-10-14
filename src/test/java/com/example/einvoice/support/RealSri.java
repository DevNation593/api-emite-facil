package com.example.einvoice.support;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca la única clase de pruebas que habla con el SRI de verdad. En modo certificación
 * ({@code -Deinvoice.certification=true}) todas las demás pruebas de integración se saltan.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface RealSri {
}
