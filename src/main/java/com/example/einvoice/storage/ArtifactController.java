package com.example.einvoice.storage;

import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.einvoice.billing.DocumentKind;
import com.example.einvoice.billing.DocumentKinds;
import com.example.einvoice.storage.ArtifactStore.Download;
import com.example.einvoice.tenancy.TenantAccess;
import com.example.einvoice.web.ApiException;
import com.example.einvoice.xml.DocumentType;

/** Descarga de los artefactos de un comprobante de la empresa activa. */
@RestController
@RequestMapping("/api/v1/documents/{id}/artifacts")
public class ArtifactController {

	private final ArtifactStore artifacts;

	private final DocumentKinds kinds;

	public ArtifactController(ArtifactStore artifacts, DocumentKinds kinds) {
		this.artifacts = artifacts;
		this.kinds = kinds;
	}

	@GetMapping("/{kind}")
	ResponseEntity<byte[]> download(TenantAccess access, @PathVariable UUID id, @PathVariable String kind) {
		ArtifactKind artifactKind = ArtifactKind.fromPathName(kind)
			.orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
					"Tipo de artefacto desconocido"));
		// Un documento ajeno y un artefacto aún no generado responden igual.
		Download artifact = artifacts.findForDownload(access.tenantId(), id, artifactKind)
			.orElseThrow(ApiException::notFound);
		// Con nombre propio y como adjunto: el navegador lo guarda, no lo abre dentro del sitio.
		String extension = MediaType.APPLICATION_PDF.equals(artifactKind.mediaType()) ? ".pdf" : ".xml";
		String stem = kinds.find(DocumentType.of(artifact.documentType())).map(DocumentKind::fileStem).orElse("comprobante");
		String name = stem + "-" + artifact.number() + "-" + artifactKind.pathName() + extension;
		return ResponseEntity.ok()
			.contentType(artifactKind.mediaType())
			.eTag(artifact.sha256())
			.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
			.body(artifact.content());
	}

}
