package com.example.einvoice.billing;

import java.util.List;

import com.example.einvoice.billing.FiscalDocumentRepository.NewLine;
import com.example.einvoice.billing.FiscalDocumentRepository.NewPayment;

/** Contenido propio de la factura: sus líneas, el total de impuestos por tarifa y las formas de pago. */
public record InvoiceBody(List<NewLine> lines, List<TaxTotal> taxes, List<NewPayment> payments) {
}
