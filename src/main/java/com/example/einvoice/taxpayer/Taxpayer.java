package com.example.einvoice.taxpayer;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Emisor: contribuyente con RUC que factura a través de la plataforma. Un RUC pertenece a un solo tenant. */
@Entity
@Table(name = "taxpayers")
public class Taxpayer {

	/** Datos que el comprobante lleva como leyendas; todos opcionales al dar de alta el emisor. */
	public record FiscalData(boolean accountingRequired, String specialTaxpayerCode, String withholdingAgentCode,
			RimpeRegime rimpeRegime) {

		public static final FiscalData DEFAULT = new FiscalData(false, null, null, RimpeRegime.NONE);

	}

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	private UUID tenantId;

	private String ruc;

	private String legalName;

	private String tradeName;

	private String headOfficeAddress;

	private String defaultEnvironment;

	private boolean accountingRequired;

	private String specialTaxpayerCode;

	private String withholdingAgentCode;

	@Enumerated(EnumType.STRING)
	private RimpeRegime rimpeRegime;

	/** Cuándo se activó la emisión en producción; nulo en pruebas. Lo escribe ProductionActivation. */
	@Column(insertable = false, updatable = false)
	private Instant productionEnabledAt;

	protected Taxpayer() {
	}

	public Taxpayer(UUID tenantId, String ruc, String legalName, String tradeName, String headOfficeAddress,
			String defaultEnvironment, FiscalData fiscalData) {
		this.tenantId = tenantId;
		this.ruc = ruc;
		this.legalName = legalName;
		this.tradeName = tradeName;
		this.headOfficeAddress = headOfficeAddress;
		this.defaultEnvironment = defaultEnvironment;
		this.accountingRequired = fiscalData.accountingRequired();
		this.specialTaxpayerCode = fiscalData.specialTaxpayerCode();
		this.withholdingAgentCode = fiscalData.withholdingAgentCode();
		this.rimpeRegime = fiscalData.rimpeRegime();
	}

	public UUID getId() {
		return id;
	}

	public UUID getTenantId() {
		return tenantId;
	}

	public String getRuc() {
		return ruc;
	}

	public String getLegalName() {
		return legalName;
	}

	public String getTradeName() {
		return tradeName;
	}

	public String getHeadOfficeAddress() {
		return headOfficeAddress;
	}

	public String getDefaultEnvironment() {
		return defaultEnvironment;
	}

	public boolean isAccountingRequired() {
		return accountingRequired;
	}

	public String getSpecialTaxpayerCode() {
		return specialTaxpayerCode;
	}

	public String getWithholdingAgentCode() {
		return withholdingAgentCode;
	}

	public RimpeRegime getRimpeRegime() {
		return rimpeRegime;
	}

	public Instant getProductionEnabledAt() {
		return productionEnabledAt;
	}

}
