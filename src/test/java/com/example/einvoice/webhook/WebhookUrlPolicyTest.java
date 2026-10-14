package com.example.einvoice.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class WebhookUrlPolicyTest {

	/** Nombres de mentira: lo que resuelve cada uno. */
	static final Map<String, List<String>> NAMES = Map.of("hooks.example.com", List.of("93.184.216.34"),
			"interno.example.com", List.of("10.0.0.7"), "mixto.example.com", List.of("93.184.216.34", "127.0.0.1"),
			"seis.example.com", List.of("2606:4700::1111"));

	static InetAddress[] resolve(String host) throws UnknownHostException {
		List<String> known = NAMES.get(host);
		if (known == null) {
			// Los literales sí se interpretan; un nombre desconocido no existe.
			if (host.matches("[0-9a-fA-FxX:.\\[\\]]+")) {
				return InetAddress.getAllByName(host);
			}
			throw new UnknownHostException(host);
		}
		InetAddress[] addresses = new InetAddress[known.size()];
		for (int i = 0; i < addresses.length; i++) {
			addresses[i] = InetAddress.getByName(known.get(i));
		}
		return addresses;
	}

	final WebhookUrlPolicy strict = new WebhookUrlPolicy(false, false, WebhookUrlPolicyTest::resolve);

	@Test
	void acceptsAnHttpsAddressOnAPublicHost() {
		URI url = strict.parse("https://hooks.example.com/facturas?ficha=abc");

		assertThat(strict.vet(url).getHostAddress()).isEqualTo("93.184.216.34");
		assertThat(strict.vet(strict.parse("https://hooks.example.com:8443/x")).getHostAddress()).isEqualTo("93.184.216.34");
		assertThat(strict.vet(strict.parse("https://seis.example.com/x")).getHostAddress()).startsWith("2606:4700");
	}

	@Test
	void rejectsWhatIsNotAPlainHttpsAddress() {
		for (String url : List.of("http://hooks.example.com/x", "ftp://hooks.example.com/x", "hooks.example.com/x",
				"https://usuario:clave@hooks.example.com/x", "https://usuario@hooks.example.com/x",
				"https://hooks.example.com/x#parte", "https:///solo-ruta", "https://hooks.example.com:0/x",
				"https://hooks.example.com:70000/x", "https://hooks.example.com/con espacio", "",
				"https://hooks.example.com/" + "a".repeat(2048))) {
			assertThatThrownBy(() -> strict.parse(url)).as(url).isInstanceOf(WebhookUrlException.class);
		}
	}

	@Test
	void theReasonNeverRepeatsTheAddress() {
		assertThatThrownBy(() -> strict.parse("https://usuario:clave-secreta@hooks.example.com/x"))
			.hasMessageNotContaining("clave-secreta")
			.hasMessageNotContaining("hooks.example.com");
	}

	@Test
	void plainHttpIsOnlyForDevelopment() {
		WebhookUrlPolicy lax = new WebhookUrlPolicy(true, false, WebhookUrlPolicyTest::resolve);

		assertThat(lax.parse("http://hooks.example.com/x").getScheme()).isEqualTo("http");
		assertThatThrownBy(() -> lax.parse("ftp://hooks.example.com/x")).isInstanceOf(WebhookUrlException.class);
	}

	@Test
	void knowsWhichAddressesAreNotThePublicInternet() throws Exception {
		for (String address : List.of("127.0.0.1", "127.255.255.254", "10.1.2.3", "172.16.0.1", "172.31.255.255",
				"192.168.1.1", "169.254.169.254", "100.64.0.1", "100.127.255.255", "0.0.0.0", "0.1.2.3", "224.0.0.1",
				"239.255.255.255", "240.0.0.1", "255.255.255.255", "192.0.0.1", "192.0.2.1", "198.18.0.1", "198.19.255.255",
				"198.51.100.1", "203.0.113.1", "192.88.99.1", "::1", "::", "fe80::1", "fec0::1", "fc00::1", "fd12::1",
				"ff02::1", "::ffff:10.0.0.1", "::ffff:127.0.0.1", "::10.0.0.1", "64:ff9b::a00:1", "2001:db8::1", "2002:a00:1::1",
				"2001::1", "100::1")) {
			assertThat(WebhookUrlPolicy.isPublic(InetAddress.getByName(address))).as(address).isFalse();
		}
		for (String address : List.of("8.8.8.8", "93.184.216.34", "172.15.255.255", "172.32.0.1", "100.63.255.255",
				"100.128.0.1", "198.17.255.255", "198.20.0.1", "2606:4700::1111", "2a00:1450:4003:80f::200e",
				"::ffff:8.8.8.8")) {
			assertThat(WebhookUrlPolicy.isPublic(InetAddress.getByName(address))).as(address).isTrue();
		}
	}

	@Test
	void aNameMustResolveOnlyToPublicAddresses() {
		for (String url : List.of("https://interno.example.com/x", "https://mixto.example.com/x", "https://127.0.0.1/x",
				"https://[::1]/x", "https://169.254.169.254/latest/meta-data", "https://[::ffff:10.0.0.1]/x")) {
			assertThatThrownBy(() -> strict.vet(strict.parse(url))).as(url).isInstanceOf(WebhookUrlException.class);
		}
		assertThatThrownBy(() -> strict.vet(strict.parse("https://no-existe.example.com/x")))
			.isInstanceOf(WebhookUrlException.class);
	}

	@Test
	void theLocalMachineWrittenInAnOddWayIsStillTheLocalMachine() {
		// 2130706433 es 127.0.0.1 escrito como un solo número: pasa por nombre y resuelve a la propia máquina.
		assertThatThrownBy(() -> strict.vet(strict.parse("https://2130706433/x"))).isInstanceOf(WebhookUrlException.class)
			.hasMessageContaining("público");
		// Estas ni siquiera son un servidor para quien interpreta la dirección.
		for (String url : List.of("https://0x7f.0.0.1/x", "https://127.1/x")) {
			assertThatThrownBy(() -> strict.parse(url)).as(url).isInstanceOf(WebhookUrlException.class);
		}
	}

	@Test
	void rejectsAnAddressItCouldNotSendToFaithfully() {
		// Una letra con tilde sin codificar llegaría al receptor convertida en otra cosa; un nombre
		// acabado en punto no admite la comprobación del certificado.
		for (String url : List.of("https://hooks.example.com/facturación/avisos", "https://hooks.example.com/x?cliente=peña",
				"https://hooks.example.com./x")) {
			assertThatThrownBy(() -> strict.parse(url)).as(url).isInstanceOf(WebhookUrlException.class);
		}
		assertThat(strict.parse("https://hooks.example.com/facturaci%C3%B3n/avisos").getRawPath())
			.isEqualTo("/facturaci%C3%B3n/avisos");
		assertThat(strict.parse("HTTPS://Hooks.Example.COM/x").getHost()).isEqualTo("Hooks.Example.COM");
	}

	@Test
	void privateAddressesAreOnlyForDevelopment() {
		WebhookUrlPolicy lax = new WebhookUrlPolicy(true, true, WebhookUrlPolicyTest::resolve);

		assertThat(lax.vet(lax.parse("http://127.0.0.1:8080/x")).isLoopbackAddress()).isTrue();
		assertThat(lax.vet(lax.parse("https://interno.example.com/x")).getHostAddress()).isEqualTo("10.0.0.7");
	}

}
