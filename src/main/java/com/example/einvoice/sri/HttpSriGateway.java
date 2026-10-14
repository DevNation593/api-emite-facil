package com.example.einvoice.sri;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Cliente SOAP de los servicios del SRI sobre el cliente HTTP del JDK. No sigue redirecciones, no
 * reintenta por su cuenta y acota tanto el tiempo total de cada llamada como el tamaño de la respuesta.
 */
@Component
@EnableConfigurationProperties(SriProperties.class)
public class HttpSriGateway implements SriGateway {

	/** Tamaño máximo de respuesta que se lee. Una autorización lleva dentro el comprobante (320 KB como mucho). */
	static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

	/** Parte de una respuesta de error que se conserva como evidencia. */
	private static final int MAX_ERROR_BODY_BYTES = 64 * 1024;

	private final SriProperties properties;

	private final HttpClient client;

	public HttpSriGateway(SriProperties properties) {
		this.properties = properties;
		this.client = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.followRedirects(HttpClient.Redirect.NEVER)
			.connectTimeout(properties.connectTimeout())
			.build();
	}

	@Override
	public ReceptionResult receive(SriEnvironment environment, byte[] signedXml) {
		Exchange exchange = post(properties.endpoints(environment).reception(), SriSoapRequests.reception(signedXml));
		if (exchange.error() != null) {
			return ReceptionResult.failed(exchange.status(), exchange.error(), exchange.body());
		}
		return SriResponseParser.reception(exchange.body()).withTransport(exchange.status(), exchange.body());
	}

	@Override
	public AuthorizationResult authorize(SriEnvironment environment, String accessKey) {
		byte[] request;
		try {
			request = SriSoapRequests.authorization(accessKey);
		}
		catch (IllegalArgumentException ex) {
			return AuthorizationResult.failed(null, ex.getMessage(), null);
		}
		Exchange exchange = post(properties.endpoints(environment).authorization(), request);
		if (exchange.error() != null) {
			return AuthorizationResult.failed(exchange.status(), exchange.error(), exchange.body());
		}
		return SriResponseParser.authorization(exchange.body()).withTransport(exchange.status(), exchange.body());
	}

	/** Resultado del intercambio HTTP: con error si no hubo una respuesta 200 completa. */
	private record Exchange(Integer status, byte[] body, String error) {
	}

	private Exchange post(URI endpoint, byte[] soap) {
		HttpRequest request = HttpRequest.newBuilder(endpoint)
			.timeout(properties.readTimeout())
			.header("Content-Type", "text/xml; charset=UTF-8")
			.header("SOAPAction", "\"\"")
			.POST(HttpRequest.BodyPublishers.ofByteArray(soap))
			.build();
		// El plazo total cubre también la lectura del cuerpo, que el timeout de la petición no acota.
		Duration deadline = properties.connectTimeout().plus(properties.readTimeout());
		CompletableFuture<HttpResponse<byte[]>> call = client.sendAsync(request, info -> new CappedBody());
		try {
			HttpResponse<byte[]> response = call.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
			if (response.statusCode() != 200) {
				byte[] body = response.body();
				return new Exchange(response.statusCode(),
						body.length <= MAX_ERROR_BODY_BYTES ? body : Arrays.copyOf(body, MAX_ERROR_BODY_BYTES),
						"El SRI respondió HTTP " + response.statusCode());
			}
			return new Exchange(200, response.body(), null);
		}
		catch (ExecutionException ex) {
			Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
			if (cause instanceof ResponseTooLarge) {
				return new Exchange(null, null, "La respuesta del SRI es demasiado grande");
			}
			// Solo el tipo del fallo: el mensaje de estas excepciones puede llevar la dirección.
			return new Exchange(null, null, "Sin respuesta del SRI: " + cause.getClass().getSimpleName());
		}
		catch (TimeoutException ex) {
			call.cancel(true);
			return new Exchange(null, null, "Sin respuesta del SRI: tiempo de espera agotado");
		}
		catch (InterruptedException ex) {
			call.cancel(true);
			Thread.currentThread().interrupt();
			return new Exchange(null, null, "Sin respuesta del SRI: llamada interrumpida");
		}
	}

	private static final class ResponseTooLarge extends RuntimeException {

		private static final long serialVersionUID = 1L;

	}

	/** Acumula el cuerpo hasta el máximo; si lo supera, corta la descarga. */
	private static final class CappedBody implements HttpResponse.BodySubscriber<byte[]> {

		private final CompletableFuture<byte[]> result = new CompletableFuture<>();

		private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

		private Flow.Subscription subscription;

		@Override
		public CompletionStage<byte[]> getBody() {
			return result;
		}

		@Override
		public void onSubscribe(Flow.Subscription subscription) {
			this.subscription = subscription;
			subscription.request(Long.MAX_VALUE);
		}

		@Override
		public void onNext(List<ByteBuffer> items) {
			for (ByteBuffer item : items) {
				if (buffer.size() + item.remaining() > MAX_RESPONSE_BYTES) {
					subscription.cancel();
					result.completeExceptionally(new ResponseTooLarge());
					return;
				}
				byte[] chunk = new byte[item.remaining()];
				item.get(chunk);
				buffer.writeBytes(chunk);
			}
		}

		@Override
		public void onError(Throwable throwable) {
			result.completeExceptionally(throwable);
		}

		@Override
		public void onComplete() {
			result.complete(buffer.toByteArray());
		}

	}

}
