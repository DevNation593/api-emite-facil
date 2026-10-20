package com.example.einvoice.delivery;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.einvoice.billing.DocumentKind;
import com.example.einvoice.billing.DocumentKinds;
import com.example.einvoice.billing.FiscalDocumentRepository;
import com.example.einvoice.billing.FiscalDocumentRepository.ProcessingState;
import com.example.einvoice.billing.RideEnvelope;
import com.example.einvoice.sri.SriEnvironment;
import com.example.einvoice.storage.ArtifactKind;
import com.example.einvoice.storage.ArtifactStore;
import com.example.einvoice.web.ApiException;

/**
 * Archiva un comprobante autorizado: guarda como artefactos el XML autorizado que se entrega al
 * receptor y el RIDE en PDF. Se puede llamar las veces que haga falta; cada artefacto se crea una
 * sola vez y después no cambia.
 */
@Service
public class DocumentArchiver {

	private static final String AUTHORIZED = "AUTHORIZED";

	private final FiscalDocumentRepository documents;

	private final ArtifactStore artifacts;

	private final DocumentKinds kinds;

	public DocumentArchiver(FiscalDocumentRepository documents, ArtifactStore artifacts, DocumentKinds kinds) {
		this.documents = documents;
		this.artifacts = artifacts;
		this.kinds = kinds;
	}

	/** Devuelve false, sin hacer nada, si el comprobante no está autorizado. */
	@Transactional
	public boolean archive(UUID tenantId, UUID documentId) {
		// El bloqueo ordena dos archivados simultáneos: el segundo encuentra los artefactos ya creados.
		ProcessingState locked = documents.lockForProcessing(tenantId, documentId).orElseThrow(ApiException::notFound);
		if (!AUTHORIZED.equals(locked.state())) {
			return false;
		}
		RideEnvelope ride = documents.rideEnvelope(tenantId, documentId)
			.orElseThrow(() -> new IllegalStateException("Comprobante autorizado sin datos de autorización: " + documentId));
		if (!artifacts.exists(tenantId, documentId, ArtifactKind.XML_AUTHORIZED)) {
			byte[] signed = artifacts.find(tenantId, documentId, ArtifactKind.XML_SIGNED)
				.orElseThrow(() -> new IllegalStateException("Comprobante autorizado sin su XML firmado: " + documentId))
				.content();
			artifacts.save(tenantId, documentId, ArtifactKind.XML_AUTHORIZED,
					AuthorizedXmlBuilder.build(ride.authorizationNumber(), ride.authorizedAt(),
							SriEnvironment.fromCode(locked.environment()), signed));
		}
		if (!artifacts.exists(tenantId, documentId, ArtifactKind.RIDE_PDF)) {
			DocumentKind<?> kind = kinds.find(ride.envelope().type())
				.orElseThrow(() -> new IllegalStateException(
						"Comprobante autorizado de un tipo sin implementación: " + documentId));
			artifacts.save(tenantId, documentId, ArtifactKind.RIDE_PDF, ride(kind, ride, tenantId, documentId));
		}
		// Archivado: ya no hace falta la red de seguridad que lo mantenía programado.
		documents.schedule(tenantId, documentId, null, 0);
		return true;
	}

	private <C> byte[] ride(DocumentKind<C> kind, RideEnvelope ride, UUID tenantId, UUID documentId) {
		return kind.buildRide(ride, kind.loadContent(tenantId, documentId));
	}

}
