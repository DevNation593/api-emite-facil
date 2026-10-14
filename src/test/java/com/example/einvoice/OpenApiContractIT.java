package com.example.einvoice;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;

import com.example.einvoice.support.AbstractIntegrationTest;

/** El contrato contracts/openapi.yaml y los controladores describen exactamente las mismas rutas. */
class OpenApiContractIT extends AbstractIntegrationTest {

	static final Path CONTRACT = Path.of("contracts", "openapi.yaml").toAbsolutePath().normalize();

	static final Set<String> HTTP_METHODS = Set.of("get", "post", "put", "patch", "delete");

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	RequestMappingHandlerMapping mappings;

	@Test
	void contractAndControllersDescribeTheSameRoutes() throws IOException {
		assertThat(implementedRoutes()).isEqualTo(contractRoutes());
	}

	@Test
	void everyOperationDeclaresProblemResponsesAndSecurity() throws IOException {
		Map<String, Object> contract = load();
		assertThat(contract.get("openapi").toString()).startsWith("3.1");
		assertThat(contract.get("security")).isNotNull();
		paths(contract).forEach((path, item) -> operations(item).forEach((method, operation) -> {
			Map<String, Object> responses = asMap(asMap(operation).get("responses"));
			assertThat(responses.keySet()).as("%s %s", method, path).contains("401");
		}));
	}

	private Set<String> implementedRoutes() {
		Set<String> routes = new TreeSet<>();
		mappings.getHandlerMethods().keySet().forEach(info -> {
			for (String pattern : info.getPatternValues()) {
				if (pattern.startsWith("/api/v1")) {
					for (RequestMethod method : info.getMethodsCondition().getMethods()) {
						routes.add(method.name() + " " + pattern);
					}
				}
			}
		});
		return routes;
	}

	private Set<String> contractRoutes() throws IOException {
		Map<String, Object> contract = load();
		String base = asMap(((List<?>) contract.get("servers")).get(0)).get("url").toString();
		Set<String> routes = new TreeSet<>();
		paths(contract).forEach((path, item) -> operations(item)
			.forEach((method, operation) -> routes.add(method.toUpperCase() + " " + base + path)));
		return routes;
	}

	private static Map<String, Object> load() throws IOException {
		try (Reader reader = Files.newBufferedReader(CONTRACT, StandardCharsets.UTF_8)) {
			return new Yaml().load(reader);
		}
	}

	private static Map<String, Object> paths(Map<String, Object> contract) {
		return asMap(contract.get("paths"));
	}

	private static Map<String, Object> operations(Object pathItem) {
		Map<String, Object> operations = new java.util.LinkedHashMap<>(asMap(pathItem));
		operations.keySet().retainAll(HTTP_METHODS);
		return operations;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> asMap(Object value) {
		return (Map<String, Object>) value;
	}

}
