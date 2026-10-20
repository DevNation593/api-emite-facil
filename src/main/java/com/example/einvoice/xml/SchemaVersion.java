package com.example.einvoice.xml;

/** Versiones del esquema de factura (01) publicadas por el SRI. */
public enum SchemaVersion {

	V1_0_0(DocumentSchema.INVOICE_1_0_0), V1_1_0(DocumentSchema.INVOICE_1_1_0), V2_0_0(DocumentSchema.INVOICE_2_0_0),
	V2_1_0(DocumentSchema.INVOICE_2_1_0);

	private final DocumentSchema schema;

	SchemaVersion(DocumentSchema schema) {
		this.schema = schema;
	}

	public String value() {
		return schema.version();
	}

	public DocumentSchema schema() {
		return schema;
	}

	public static SchemaVersion of(String value) {
		for (SchemaVersion version : values()) {
			if (version.value().equals(value)) {
				return version;
			}
		}
		throw new IllegalArgumentException("Versión de esquema de factura desconocida: " + value);
	}

	public String resourceDir() {
		return schema.resourceDir();
	}

	public String xsdResource() {
		return schema.xsdResource();
	}

	public String sampleResource() {
		return schema.sampleResource();
	}

}
