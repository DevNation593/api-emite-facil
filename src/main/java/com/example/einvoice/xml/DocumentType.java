package com.example.einvoice.xml;

/**
 * Tipos de comprobante electrónico con XSD empacado (ficha técnica, tabla 3). Cada uno tiene su carpeta
 * en {@code xsd/} y el prefijo con que el SRI nombra sus archivos.
 */
public enum DocumentType {

	INVOICE("01", "invoice", "factura"),
	PURCHASE_LIQUIDATION("03", "liquidation", "LiquidacionCompra"),
	CREDIT_NOTE("04", "credit-note", "NotaCredito"),
	DEBIT_NOTE("05", "debit-note", "NotaDebito"),
	REMISSION_GUIDE("06", "remission-guide", "GuiaRemision"),
	RETENTION_RECEIPT("07", "retention-receipt", "ComprobanteRetencion");

	private final String code;

	private final String folder;

	private final String filePrefix;

	DocumentType(String code, String folder, String filePrefix) {
		this.code = code;
		this.folder = folder;
		this.filePrefix = filePrefix;
	}

	/** Código {@code codDoc} del SRI. */
	public String code() {
		return code;
	}

	String folder() {
		return folder;
	}

	String filePrefix() {
		return filePrefix;
	}

	public static DocumentType of(String code) {
		for (DocumentType type : values()) {
			if (type.code.equals(code)) {
				return type;
			}
		}
		throw new IllegalArgumentException("Tipo de comprobante desconocido: " + code);
	}

}
