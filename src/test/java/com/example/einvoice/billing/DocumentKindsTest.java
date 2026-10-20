package com.example.einvoice.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.einvoice.xml.DocumentType;

/** El registro de tipos de comprobante encuentra cada tipo y no admite dos piezas para el mismo. */
class DocumentKindsTest {

	@Test
	void findsTheInvoice() {
		InvoiceKind invoice = new InvoiceKind(null);
		DocumentKinds kinds = new DocumentKinds(List.of(invoice));
		assertThat(kinds.find(DocumentType.INVOICE)).containsSame(invoice);
		assertThat(kinds.find(DocumentType.CREDIT_NOTE)).isEmpty();
	}

	@Test
	void duplicateTypeFails() {
		assertThatThrownBy(() -> new DocumentKinds(List.of(new InvoiceKind(null), new InvoiceKind(null))))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("INVOICE");
	}

}
