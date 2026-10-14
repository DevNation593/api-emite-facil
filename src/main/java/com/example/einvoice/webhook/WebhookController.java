package com.example.einvoice.webhook;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.einvoice.tenancy.TenantAccess;
import com.example.einvoice.web.PageResponse;
import com.example.einvoice.webhook.WebhookSubscriptionService.Created;
import com.example.einvoice.webhook.WebhookSubscriptionService.Delivery;
import com.example.einvoice.webhook.WebhookSubscriptionService.RotatedSecret;
import com.example.einvoice.webhook.WebhookSubscriptionService.Subscription;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/webhooks")
public class WebhookController {

	static final String EVENT_TYPE = "document\\.(received|authorized|rejected|delivered)";

	private final WebhookSubscriptionService subscriptions;

	public WebhookController(WebhookSubscriptionService subscriptions) {
		this.subscriptions = subscriptions;
	}

	record CreateSubscriptionRequest(@NotBlank @Size(max = WebhookUrlPolicy.MAX_LENGTH) String url,
			@NotEmpty @Size(max = 8) List<@NotNull @Pattern(regexp = EVENT_TYPE) String> eventTypes,
			@Size(max = 200) String description) {
	}

	record UpdateSubscriptionRequest(@NotBlank @Size(max = WebhookUrlPolicy.MAX_LENGTH) String url,
			@NotEmpty @Size(max = 8) List<@NotNull @Pattern(regexp = EVENT_TYPE) String> eventTypes,
			@Size(max = 200) String description, @NotNull Boolean enabled) {
	}

	@PostMapping("/subscriptions")
	@ResponseStatus(HttpStatus.CREATED)
	Created create(TenantAccess access, @Valid @RequestBody CreateSubscriptionRequest request) {
		return subscriptions.create(access, request.url(), request.eventTypes(), request.description());
	}

	@GetMapping("/subscriptions")
	List<Subscription> list(TenantAccess access) {
		return subscriptions.list(access);
	}

	@PutMapping("/subscriptions/{id}")
	Subscription update(TenantAccess access, @PathVariable UUID id, @Valid @RequestBody UpdateSubscriptionRequest request) {
		return subscriptions.update(access, id, request.url(), request.eventTypes(), request.description(),
				request.enabled());
	}

	@DeleteMapping("/subscriptions/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void delete(TenantAccess access, @PathVariable UUID id) {
		subscriptions.delete(access, id);
	}

	@GetMapping("/subscriptions/{id}/deliveries")
	PageResponse<Delivery> deliveries(TenantAccess access, @PathVariable UUID id,
			@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
		return subscriptions.deliveries(access, id, page, size);
	}

	@PostMapping("/deliveries/{id}/retry")
	@ResponseStatus(HttpStatus.ACCEPTED)
	void retry(TenantAccess access, @PathVariable UUID id) {
		subscriptions.retry(access, id);
	}

	/** Envía un aviso de prueba a la dirección, sin comprobante: para probarla sin emitir nada. */
	@PostMapping("/subscriptions/{id}/test")
	@ResponseStatus(HttpStatus.ACCEPTED)
	Delivery test(TenantAccess access, @PathVariable UUID id) {
		return subscriptions.requestTest(access, id);
	}

	/** Cambia el secreto de firma y lo devuelve: es la única vez que se muestra. */
	@PostMapping("/subscriptions/{id}/secret")
	RotatedSecret rotate(TenantAccess access, @PathVariable UUID id) {
		return subscriptions.rotate(access, id);
	}

}
