package com.example.einvoice.catalog;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.einvoice.tenancy.TenantAccess;
import com.example.einvoice.web.PageResponse;
import com.example.einvoice.web.TextRules;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {

	private final CustomerService service;

	public CustomerController(CustomerService service) {
		this.service = service;
	}

	record CreateCustomerRequest(@NotNull @Pattern(regexp = "[0-9]{2}") String idType,
			@NotBlank @Size(max = 20) @Pattern(regexp = TextRules.NO_CONTROL_CHARS) String idNumber,
			@NotBlank @Size(max = 300) @Pattern(regexp = TextRules.NO_CONTROL_CHARS) String name,
			@Email @Size(max = 254) String email,
			@Size(max = 300) @Pattern(regexp = TextRules.NO_CONTROL_CHARS) String address,
			@Size(max = 30) @Pattern(regexp = TextRules.NO_CONTROL_CHARS) String phone) {
	}

	record CustomerResponse(UUID id, String idType, String idNumber, String name, String email, String address,
			String phone) {

		static CustomerResponse of(Customer customer) {
			return new CustomerResponse(customer.getId(), customer.getIdType(), customer.getIdNumber(),
					customer.getName(), customer.getEmail(), customer.getAddress(), customer.getPhone());
		}

	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	CustomerResponse create(TenantAccess access, @Valid @RequestBody CreateCustomerRequest request) {
		return CustomerResponse.of(service.create(access, request.idType(), request.idNumber(), request.name(),
				request.email(), request.address(), request.phone()));
	}

	@GetMapping
	PageResponse<CustomerResponse> list(TenantAccess access, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size, @RequestParam(defaultValue = "") String q) {
		return PageResponse.of(service.search(access, q, PageResponse.request(page, size)), CustomerResponse::of);
	}

	@GetMapping("/{id}")
	CustomerResponse get(TenantAccess access, @PathVariable UUID id) {
		return CustomerResponse.of(service.get(access, id));
	}

}
