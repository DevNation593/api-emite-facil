package com.example.einvoice.taxpayer;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.einvoice.compliance.AuditLog;
import com.example.einvoice.tenancy.TenantAccess;
import com.example.einvoice.web.ApiException;

@Service
public class TaxpayerService {

	private final TaxpayerRepository taxpayers;

	private final AuditLog audit;

	public TaxpayerService(TaxpayerRepository taxpayers, AuditLog audit) {
		this.taxpayers = taxpayers;
		this.audit = audit;
	}

	@Transactional
	public Taxpayer create(TenantAccess access, String ruc, String legalName, String tradeName,
			String headOfficeAddress, String defaultEnvironment, Taxpayer.FiscalData fiscalData) {
		access.requireWrite();
		if (fiscalData.rimpeRegime() == RimpeRegime.NEGOCIO_POPULAR) {
			// El XSD de factura 1.1.0 incorporado solo admite la leyenda de RIMPE emprendedor: las
			// facturas de este emisor saldrían inválidas tras consumir su secuencial (docs/14).
			throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "RIMPE_REGIME_UNSUPPORTED",
					"Todavía no se pueden emitir comprobantes de contribuyentes RIMPE negocio popular");
		}
		if (!"1".equals(defaultEnvironment)) {
			// Un emisor nace en pruebas; a producción se pasa con la activación (ProductionActivation).
			throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PRODUCTION_NOT_ENABLED",
					"Un emisor se da de alta en el ambiente de pruebas; el paso a producción se activa después");
		}
		Taxpayer taxpayer;
		try {
			taxpayer = taxpayers.saveAndFlush(new Taxpayer(access.tenantId(), ruc, legalName.strip(),
					blankToNull(tradeName), headOfficeAddress.strip(), defaultEnvironment, fiscalData));
		}
		catch (DataIntegrityViolationException ex) {
			// La restricción UNIQUE de la base decide, también cuando dos tenants registran a la vez.
			if (violates(ex, "taxpayers_ruc_key")) {
				throw new ApiException(HttpStatus.CONFLICT, "RUC_ALREADY_REGISTERED",
						"El RUC ya está registrado en la plataforma");
			}
			throw ex;
		}
		audit.record(access.tenantId(), access.userId(), "TAXPAYER_CREATED", "TAXPAYER", taxpayer.getId(),
				Map.of("ruc", ruc));
		return taxpayer;
	}

	@Transactional(readOnly = true)
	public List<Taxpayer> list(TenantAccess access) {
		return taxpayers.findAllByTenantIdOrderByLegalName(access.tenantId());
	}

	/** Un emisor de otro tenant se trata igual que uno inexistente. */
	@Transactional(readOnly = true)
	public Taxpayer get(TenantAccess access, UUID id) {
		return taxpayers.findByIdAndTenantId(id, access.tenantId()).orElseThrow(ApiException::notFound);
	}

	static boolean violates(DataIntegrityViolationException ex, String constraint) {
		String message = ex.getMostSpecificCause().getMessage();
		return message != null && message.contains(constraint);
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

}
