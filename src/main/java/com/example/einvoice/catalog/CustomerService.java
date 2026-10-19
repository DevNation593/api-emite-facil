package com.example.einvoice.catalog;

import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
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
		validateIdentification(idType, idNumber);
		Customer customer;
		try {
			customer = customers.saveAndFlush(new Customer(access.tenantId(), idType, idNumber, name.strip(),
					blankToNull(email), blankToNull(address), blankToNull(phone)));
		}
		catch (DataIntegrityViolationException ex) {
			throw duplicateOrOriginal(ex);
		}
		// La bitácora no guarda datos personales: basta el tipo de identificación.
		audit.record(access, "CUSTOMER_CREATED", "CUSTOMER", customer.getId(),
				Map.of("idType", idType));
		return customer;
	}

	@Transactional
	public Customer update(TenantAccess access, UUID id, String idType, String idNumber, String name, String email,
			String address, String phone) {
		access.requireBilling();
		Customer customer = get(access, id);
		validateIdentification(idType, idNumber);
		customer.update(idType, idNumber, name.strip(), blankToNull(email), blankToNull(address), blankToNull(phone));
		try {
			customers.saveAndFlush(customer);
		}
		catch (DataIntegrityViolationException ex) {
			throw duplicateOrOriginal(ex);
		}
		catch (OptimisticLockingFailureException ex) {
			throw changed();
		}
		audit.record(access, "CUSTOMER_UPDATED", "CUSTOMER", id, Map.of("idType", idType));
		return customer;
	}

	@Transactional
	public void delete(TenantAccess access, UUID id) {
		access.requireBilling();
		Customer customer = get(access, id);
		customer.archive();
		try {
			customers.saveAndFlush(customer);
		}
		catch (OptimisticLockingFailureException ex) {
			throw changed();
		}
		audit.record(access, "CUSTOMER_DELETED", "CUSTOMER", id, Map.of("idType", customer.getIdType()));
	}

	private static ApiException changed() {
		return new ApiException(HttpStatus.CONFLICT, "CATALOG_CHANGED", "El cliente cambió. Recargue la lista e inténtelo de nuevo");
	}

	private void validateIdentification(String idType, String idNumber) {
		IdentificationType type = catalogs.identificationType(idType)
			.orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IDENTIFICATION_TYPE_UNKNOWN",
					"El tipo de identificación no existe en el catálogo"));
		// Si no, el monto máximo de consumidor final se evitaría registrando esos mismos dígitos como RUC.
		boolean borrowsFinalConsumerNumber = !type.finalConsumer() && catalogs.isFinalConsumerNumber(idNumber);
		if (!idNumber.matches(type.idNumberPattern()) || borrowsFinalConsumerNumber) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IDENTIFICATION_INVALID",
					"La identificación no tiene el formato de su tipo");
		}
	}

	private static RuntimeException duplicateOrOriginal(DataIntegrityViolationException ex) {
		if (mentions(ex, "customers_identification_key")) {
			return new ApiException(HttpStatus.CONFLICT, "CUSTOMER_ALREADY_EXISTS",
					"Ya existe un cliente con esa identificación");
		}
		return ex;
	}

	@Transactional(readOnly = true)
	public Page<Customer> search(TenantAccess access, String term, Pageable pageable) {
		return customers.search(access.tenantId(), LikeTerms.escape(term), pageable);
	}

	@Transactional(readOnly = true)
	public Customer get(TenantAccess access, UUID id) {
		return customers.findByIdAndTenantIdAndDeletedAtIsNull(id, access.tenantId())
			.orElseThrow(ApiException::notFound);
	}

	static boolean mentions(DataIntegrityViolationException ex, String constraint) {
		String message = ex.getMostSpecificCause().getMessage();
		return message != null && message.contains(constraint);
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

}
