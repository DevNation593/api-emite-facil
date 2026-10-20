package com.example.einvoice.xml;

/**
 * Cada XSD empacado: un tipo de comprobante en una versión publicada por el SRI. Sus archivos viven en
 * {@code xsd/<tipo>/<versión>/} y todos importan el XMLDSig compartido de {@code xsd/common/}.
 */
public enum DocumentSchema {

	INVOICE_1_0_0(DocumentType.INVOICE, "1.0.0"),
	INVOICE_1_1_0(DocumentType.INVOICE, "1.1.0"),
	INVOICE_2_0_0(DocumentType.INVOICE, "2.0.0"),
	INVOICE_2_1_0(DocumentType.INVOICE, "2.1.0"),
	PURCHASE_LIQUIDATION_1_0_0(DocumentType.PURCHASE_LIQUIDATION, "1.0.0"),
	PURCHASE_LIQUIDATION_1_1_0(DocumentType.PURCHASE_LIQUIDATION, "1.1.0"),
	CREDIT_NOTE_1_0_0(DocumentType.CREDIT_NOTE, "1.0.0"),
	CREDIT_NOTE_1_1_0(DocumentType.CREDIT_NOTE, "1.1.0"),
	DEBIT_NOTE_1_0_0(DocumentType.DEBIT_NOTE, "1.0.0"),
	REMISSION_GUIDE_1_0_0(DocumentType.REMISSION_GUIDE, "1.0.0"),
	REMISSION_GUIDE_1_1_0(DocumentType.REMISSION_GUIDE, "1.1.0"),
	RETENTION_RECEIPT_1_0_0(DocumentType.RETENTION_RECEIPT, "1.0.0"),
	RETENTION_RECEIPT_2_0_0(DocumentType.RETENTION_RECEIPT, "2.0.0");

	private final DocumentType type;

	private final String version;

	DocumentSchema(DocumentType type, String version) {
		this.type = type;
		this.version = version;
	}

	public DocumentType type() {
		return type;
	}

	public String version() {
		return version;
	}

	public static DocumentSchema of(DocumentType type, String version) {
		for (DocumentSchema schema : values()) {
			if (schema.type == type && schema.version.equals(version)) {
				return schema;
			}
		}
		throw new IllegalArgumentException("Versión de esquema desconocida para " + type + ": " + version);
	}

	public String resourceDir() {
		return "xsd/" + type.folder() + "/" + version + "/";
	}

	public String xsdResource() {
		return resourceDir() + type.filePrefix() + "_V" + version + ".xsd";
	}

	public String sampleResource() {
		return resourceDir() + type.filePrefix() + "_V" + version + ".xml";
	}

}
