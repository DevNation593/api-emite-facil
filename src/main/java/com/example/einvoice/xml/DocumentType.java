package com.example.einvoice.xml;

/**
 * Tipos de comprobante electrónico con XSD empacado (ficha técnica, tabla 3). Cada uno tiene su carpeta
 * en {@code xsd/} y el prefijo con que el SRI nombra sus archivos.
 */
public enum DocumentType {

	INVOICE("01", "invoice", "factura", "factura", "infoFactura"),
	PURCHASE_LIQUIDATION("03", "liquidation", "LiquidacionCompra", "liquidacionCompra", "infoLiquidacionCompra"),
	CREDIT_NOTE("04", "credit-note", "NotaCredito", "notaCredito", "infoNotaCredito"),
	DEBIT_NOTE("05", "debit-note", "NotaDebito", "notaDebito", "infoNotaDebito"),
	REMISSION_GUIDE("06", "remission-guide", "GuiaRemision", "guiaRemision", "infoGuiaRemision"),
	RETENTION_RECEIPT("07", "retention-receipt", "ComprobanteRetencion", "comprobanteRetencion", "infoCompRetencion");

	private final String code;

	private final String folder;

	private final String filePrefix;

	private final String rootElement;

	private final String infoElement;

	DocumentType(String code, String folder, String filePrefix, String rootElement, String infoElement) {
		this.code = code;
		this.folder = folder;
		this.filePrefix = filePrefix;
		this.rootElement = rootElement;
		this.infoElement = infoElement;
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

	/** Nombre del elemento raíz del XML del comprobante. */
	public String rootElement() {
		return rootElement;
	}

	/** Nombre del elemento de información propia del tipo, hijo de la raíz. */
	public String infoElement() {
		return infoElement;
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
