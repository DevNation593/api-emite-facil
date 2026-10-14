package com.example.einvoice.sequence;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** La fecha de emisión es la fecha local de Ecuador, no la del instante en UTC. */
public final class FiscalClock {

	public static final ZoneId ZONE = ZoneId.of("America/Guayaquil");

	private FiscalClock() {
	}

	public static LocalDate dateOf(Instant instant) {
		return instant.atZone(ZONE).toLocalDate();
	}

}
