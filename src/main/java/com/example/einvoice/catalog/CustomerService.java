package com.example.einvoice.catalog;

import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.einvoice.catalog.CatalogRepository.IdentificationType;
import com.example.einvoice.compliance.AuditLog;
import com.example.einvoice.tenancy.TenantAccess;
import com.example.einvoice.web.ApiException;
import com.example.einvoice.web.LikeTerms;

@Service
public class CustomerService {

	private final CustomerRepository customers;

	private final CatalogRepository catalogs;

	private final AuditLog audit;

	public CustomerService(CustomerRepository customers, CatalogRepository catalogs, AuditLog audit) {
		this.customers = customers;
		this.catalogs = catalogs;
		this.audit = audit;
	}

	@Transactional
	public Customer create(TenantAccess access, String idType, String idNumber, String name, String email,
			String address, String phone) {
		access.requireBilling();
		IdentificationType type = catalogs.identificationType(idType)
			.orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IDENTIFICATION_TYPE_UNKNOWN",
					"El tipo de identificación no existe en el catálogo"));
		// Si no, el monto máximo de consumidor final se evitaría registrando esos mismos dígitos como RUC.
		boolean borrowsFinalConsumerNumber = !type.finalConsumer() && catalogs.isFinalConsumerNumber(idNumber);
		if (!idNumber.matches(type.idNumberPattern()) || borrowsFinalConsumerNumber) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IDENTIFICATION_INVALID",
					"La identificación no tiene el formato de su tipo");
		}
		Customer customer;
		try {
			customer = customers.saveAndFlush(new Customer(access.tenantId(), idType, idNumber, name.strip(),
					blankToNull(email), blankToNull(address), blankToNull(phone)));
		}
		catch (DataIntegrityViolationException ex) {
			if (mentions(ex, "customers_identification_key")) {
				throw new ApiException(HttpStatus.CONFLICT, "CUSTOMER_ALREADY_EXISTS",
						"Ya existe un cliente con esa identificación");
			}
			throw ex;
		}
		// La bitácora no guarda datos personales: basta el tipo de identificación.
		audit.record(access, "CUSTOMER_CREATED", "CUSTOMER", customer.getId(),
				Map.of("idType", idType));
		return customer;
	}

	@Transactional(readOnly = true)
	public Page<Customer> search(TenantAccess access, String term, Pageable pageable) {
		return customers.search(access.tenantId(), LikeTerms.escape(term), pageable);
	}

	@Transactional(readOnly = true)
	public Customer get(TenantAccess access, UUID id) {
		return customers.findByIdAndTenantId(id, access.tenantId()).orElseThrow(ApiException::notFound);
	}

	static boolean mentions(DataIntegrityViolationException ex, String constraint) {
		String message = ex.getMostSpecificCause().getMessage();
		return message != null && message.contains(constraint);
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

}
