package com.example.einvoice.certificate;

import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.einvoice.certificate.CertificateService.CertificateView;
import com.example.einvoice.tenancy.TenantAccess;
import com.example.einvoice.web.ApiException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/certificates")
public class CertificateController {

	/** 64 KB de archivo son unos 87 400 caracteres en Base64. */
	private static final int MAX_BASE64_LENGTH = 90_000;

	private final CertificateService service;

	public CertificateController(CertificateService service) {
		this.service = service;
	}

	record UploadCertificateRequest(@NotNull UUID taxpayerId, @NotBlank @Size(max = MAX_BASE64_LENGTH) String p12,
			@NotBlank @Size(max = 200) String password) {

		/** El contenido nunca debe acabar en un log por un volcado accidental de la petición. */
		@Override
		public String toString() {
			return "UploadCertificateRequest[taxpayerId=" + taxpayerId + "]";
		}

	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	CertificateView upload(TenantAccess access, @Valid @RequestBody UploadCertificateRequest request) {
		byte[] p12;
		try {
			p12 = Base64.getDecoder().decode(request.p12());
		}
		catch (IllegalArgumentException ex) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "El campo p12 debe venir en Base64");
		}
		char[] password = request.password().toCharArray();
		try {
			return service.upload(access, request.taxpayerId(), p12, password);
		}
		finally {
			Arrays.fill(password, '\0');
			Arrays.fill(p12, (byte) 0);
		}
	}

	@GetMapping
	List<CertificateView> list(TenantAccess access, @RequestParam UUID taxpayerId) {
		return service.list(access, taxpayerId);
	}

}
