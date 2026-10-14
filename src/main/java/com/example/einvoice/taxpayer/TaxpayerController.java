package com.example.einvoice.taxpayer;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.einvoice.tenancy.TenantAccess;
import com.example.einvoice.web.TextRules;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/taxpayers")
public class TaxpayerController {

	private final TaxpayerService service;

	public TaxpayerController(TaxpayerService service) {
		this.service = service;
	}

	record CreateTaxpayerRequest(@NotNull @Pattern(regexp = "[0-9]{10}001") String ruc,
			@NotBlank @Size(max = 300) @Pattern(regexp = TextRules.NO_CONTROL_CHARS) String legalName,
			@Size(max = 300) @Pattern(regexp = TextRules.NO_CONTROL_CHARS) String tradeName,
			@NotBlank @Size(max = 300) @Pattern(regexp = TextRules.NO_CONTROL_CHARS) String headOfficeAddress,
			@NotNull @Pattern(regexp = "[12]") String defaultEnvironment, Boolean accountingRequired,
			@Pattern(regexp = "[A-Za-z0-9]{3,13}") String specialTaxpayerCode,
			@Pattern(regexp = "[1-9][0-9]{0,7}") String withholdingAgentCode, RimpeRegime rimpeRegime) {

		Taxpayer.FiscalData fiscalData() {
			return new Taxpayer.FiscalData(Boolean.TRUE.equals(accountingRequired), specialTaxpayerCode,
					withholdingAgentCode, rimpeRegime == null ? RimpeRegime.NONE : rimpeRegime);
		}

	}

	record TaxpayerResponse(UUID id, String ruc, String legalName, String tradeName, String headOfficeAddress,
			String defaultEnvironment, boolean accountingRequired, String specialTaxpayerCode,
			String withholdingAgentCode, RimpeRegime rimpeRegime, Instant productionEnabledAt) {

		static TaxpayerResponse of(Taxpayer taxpayer) {
			return new TaxpayerResponse(taxpayer.getId(), taxpayer.getRuc(), taxpayer.getLegalName(),
					taxpayer.getTradeName(), taxpayer.getHeadOfficeAddress(), taxpayer.getDefaultEnvironment(),
					taxpayer.isAccountingRequired(), taxpayer.getSpecialTaxpayerCode(),
					taxpayer.getWithholdingAgentCode(), taxpayer.getRimpeRegime(), taxpayer.getProductionEnabledAt());
		}

	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	TaxpayerResponse create(TenantAccess access, @Valid @RequestBody CreateTaxpayerRequest request) {
		return TaxpayerResponse.of(service.create(access, request.ruc(), request.legalName(), request.tradeName(),
				request.headOfficeAddress(), request.defaultEnvironment(), request.fiscalData()));
	}

	@GetMapping
	List<TaxpayerResponse> list(TenantAccess access) {
		return service.list(access).stream().map(TaxpayerResponse::of).toList();
	}

	@GetMapping("/{id}")
	TaxpayerResponse get(TenantAccess access, @PathVariable UUID id) {
		return TaxpayerResponse.of(service.get(access, id));
	}

}
