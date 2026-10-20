package com.example.einvoice.billing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.example.einvoice.billing.InvoiceService.LineCommand;
import com.example.einvoice.catalog.CatalogRepository;
import com.example.einvoice.catalog.Product;
import com.example.einvoice.catalog.ProductService;
import com.example.einvoice.tenancy.TenantAccess;
import com.example.einvoice.web.ApiException;

/**
 * Reglas de las líneas que comparten la factura y las notas: el producto del catálogo con su tarifa de IVA, y
 * el cálculo de la factura con sus errores (INVALID_LINE, AMOUNT_TOO_LARGE).
 */
@Component
public class CatalogLines {

	/** Mayor importe que cabe en las columnas numeric(14,2) del documento. */
	public static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999.99");

	/** Una línea pedida, con su producto del catálogo y lista para el cálculo. */
	public record CatalogLine(Product product, InvoiceLineInput input) {
	}

	private final ProductService products;

	private final CatalogRepository catalogs;

	public CatalogLines(ProductService products, CatalogRepository catalogs) {
		this.products = products;
		this.catalogs = catalogs;
	}

	/**
	 * Cada línea con su producto: precio por omisión, el del producto; descuento por omisión, cero; la tarifa
	 * de IVA, la del producto. Un producto ajeno o archivado responde 404.
	 */
	public List<CatalogLine> resolve(TenantAccess access, List<LineCommand> lines) {
		List<CatalogLine> resolved = new ArrayList<>(lines.size());
		for (LineCommand line : lines) {
			Product product = products.get(access, line.productId());
			resolved.add(new CatalogLine(product,
					new InvoiceLineInput(line.quantity(),
							line.unitPrice() == null ? product.getUnitPrice() : line.unitPrice(),
							line.discount() == null ? BigDecimal.ZERO : line.discount(), product.getIvaRateCode(),
							ivaRate(product.getIvaRateCode()))));
		}
		return resolved;
	}

	/** Porcentaje de una tarifa de IVA del catálogo; 422 TAX_RATE_UNKNOWN si no existe. */
	public BigDecimal ivaRate(String rateCode) {
		return catalogs.ivaRate(rateCode)
			.orElseThrow(() -> unprocessable("TAX_RATE_UNKNOWN", "La tarifa de IVA del producto no existe"));
	}

	/**
	 * Cálculo de la factura (redondeo por línea y por tarifa) con sus errores: una línea que no se puede
	 * calcular responde 422 INVALID_LINE, un importe que no cabe en el documento 422 AMOUNT_TOO_LARGE.
	 */
	public static InvoiceTotals calculate(List<InvoiceLineInput> inputs) {
		InvoiceTotals totals;
		try {
			totals = InvoiceCalculator.calculate(inputs);
		}
		catch (IllegalArgumentException ex) {
			throw unprocessable("INVALID_LINE", ex.getMessage());
		}
		requireStorable(totals.subtotal(), totals.totalDiscount(), totals.total());
		requireStorable(totals.lines().stream().map(LineAmounts::subtotal).toArray(BigDecimal[]::new));
		return totals;
	}

	/** 422 AMOUNT_TOO_LARGE si algún importe no cabe en las columnas del documento. */
	public static void requireStorable(BigDecimal... amounts) {
		for (BigDecimal amount : amounts) {
			if (amount.compareTo(MAX_AMOUNT) > 0) {
				throw unprocessable("AMOUNT_TOO_LARGE", "Los importes del comprobante superan el máximo admitido");
			}
		}
	}

	private static ApiException unprocessable(String errorCode, String detail) {
		return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, errorCode, detail);
	}

}
