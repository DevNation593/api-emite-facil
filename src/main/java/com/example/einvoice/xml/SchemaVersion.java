package com.example.einvoice.xml;

/** Versiones del esquema de factura (01) publicadas por el SRI. */
public enum SchemaVersion {

	V1_0_0("1.0.0"), V1_1_0("1.1.0"), V2_0_0("2.0.0"), V2_1_0("2.1.0");

	private final String value;

	SchemaVersion(String value) {
		this.value = value;
	}

	public String value() {
		return value;
	}

	public static SchemaVersion of(String value) {
		for (SchemaVersion version : values()) {
			if (version.value.equals(value)) {
				return version;
			}
		}
		throw new IllegalArgumentException("Versión de esquema de factura desconocida: " + value);
	}

	public String resourceDir() {
		return "xsd/invoice/" + value + "/";
	}

	public String xsdResource() {
		return resourceDir() + "factura_V" + value + ".xsd";
	}

	public String sampleResource() {
		return resourceDir() + "factura_V" + value + ".xml";
	}

}
