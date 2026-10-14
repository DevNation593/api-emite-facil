package com.example.einvoice.identity;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Usuarios conocidos por la plataforma. Un usuario es el par (emisor OIDC, sujeto) y se crea al primer uso. */
@Component
public class UserDirectory {

	private final JdbcClient jdbc;

	public UserDirectory(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public UUID resolve(String issuer, String subject, String email) {
		return jdbc.sql("""
				INSERT INTO users (issuer, subject, email) VALUES (:issuer, :subject, :email)
				ON CONFLICT (issuer, subject) DO UPDATE SET email = COALESCE(EXCLUDED.email, users.email)
				RETURNING id
				""").param("issuer", issuer).param("subject", subject).param("email", email).query(UUID.class).single();
	}

}
