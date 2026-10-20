package com.example.einvoice.notes;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.example.einvoice.billing.DocumentKinds;
import com.example.einvoice.billing.InvoiceKind;
import com.example.einvoice.xml.DocumentSchema;
import com.example.einvoice.xml.DocumentType;

/** Cada nota se registra como tipo de comprobante con sus nombres, su esquema y sus generadores. */
class NoteKindsTest {

	private final CreditNoteKind credit = new CreditNoteKind(null);

	private final DebitNoteKind debit = new DebitNoteKind(null);

	@Test
	void registryFindsBothNotes() {
		DocumentKinds kinds = new DocumentKinds(List.of(new InvoiceKind(null), credit, debit));
		assertThat(kinds.find(DocumentType.CREDIT_NOTE)).containsSame(credit);
		assertThat(kinds.find(DocumentType.DEBIT_NOTE)).containsSame(debit);
	}

	@Test
	void creditNoteIdentity() {
		assertThat(credit.type()).isEqualTo(DocumentType.CREDIT_NOTE);
		assertThat(credit.schema()).isEqualTo(DocumentSchema.CREDIT_NOTE_1_1_0);
		assertThat(credit.displayName()).isEqualTo("Nota de crédito");
		assertThat(credit.fileStem()).isEqualTo("nota-credito");
		assertThat(credit.auditReserved()).isEqualTo("CREDIT_NOTE_RESERVED");
	}

	@Test
	void debitNoteIdentity() {
		assertThat(debit.type()).isEqualTo(DocumentType.DEBIT_NOTE);
		assertThat(debit.schema()).isEqualTo(DocumentSchema.DEBIT_NOTE_1_0_0);
		assertThat(debit.displayName()).isEqualTo("Nota de débito");
		assertThat(debit.fileStem()).isEqualTo("nota-debito");
		assertThat(debit.auditReserved()).isEqualTo("DEBIT_NOTE_RESERVED");
	}

	@Test
	void xmlIsTheOneOfTheBuilders() {
		assertThat(credit.buildXml(CreditNoteXmlBuilderTest.envelope(), CreditNoteXmlBuilderTest.content()))
			.isEqualTo(CreditNoteXmlBuilder.build(CreditNoteXmlBuilderTest.envelope(),
					CreditNoteXmlBuilderTest.content()));
		assertThat(debit.buildXml(DebitNoteXmlBuilderTest.envelope(null), DebitNoteXmlBuilderTest.content("Mora")))
			.isEqualTo(DebitNoteXmlBuilder.build(DebitNoteXmlBuilderTest.envelope(null),
					DebitNoteXmlBuilderTest.content("Mora")));
	}

	@Test
	void contentSurvivesTheJsonRoundTrip() {
		CreditNoteContent content = CreditNoteXmlBuilderTest.content();
		assertThat(credit.reloaded(content)).isEqualTo(content);
		DebitNoteContent note = DebitNoteXmlBuilderTest.content("Mora");
		assertThat(debit.reloaded(note)).isEqualTo(note);
	}

}
