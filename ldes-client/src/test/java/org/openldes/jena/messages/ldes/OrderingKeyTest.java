package org.openldes.jena.messages.ldes;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.openldes.jena.messages.ldes.Sequences.Sequence;

class OrderingKeyTest {

	private static final String XSD = "http://www.w3.org/2001/XMLSchema#";

	@Test
	void sequencesAreComparedAsNumbersDatesAndStrings() {
		assertThat(Sequences.compare(new Sequence("9", XSD + "integer"), new Sequence("10", XSD + "integer"))).isNegative();
		assertThat(Sequences.compare(new Sequence("9", XSD + "string"), new Sequence("10", XSD + "string"))).isPositive();
		assertThat(Sequences.compare(new Sequence("2026-01-01T01:00:00+01:00", XSD + "dateTime"),
				new Sequence("2026-01-01T00:00:00Z", XSD + "dateTime"))).isZero();
		assertThat(Sequences.compare(new Sequence("1.5", XSD + "decimal"), new Sequence("2", XSD + "integer"))).isNegative();
	}

	@Test
	void timestampsWithoutATimezoneAreLocalOrGetTheDefaultTimezone() {
		assertThat(Timestamps.parse("2026-01-01T00:00:00Z", null).orElseThrow().local()).isFalse();
		assertThat(Timestamps.parse("2026-01-01T00:00:00+02:00", null).orElseThrow().instant())
				.isEqualTo(Instant.parse("2025-12-31T22:00:00Z"));
		assertThat(Timestamps.parse("2026-01-01T00:00:00", null).orElseThrow().local()).isTrue();
		Timestamps.Parsed withDefault = Timestamps.parse("2026-01-01T00:00:00", ZoneOffset.ofHours(2)).orElseThrow();
		assertThat(withDefault.local()).isFalse();
		assertThat(withDefault.instant()).isEqualTo(Instant.parse("2025-12-31T22:00:00Z"));
		assertThat(Timestamps.parse("2026-01-01T00:00:00.1234567891234", null).orElseThrow().instant().getNano()).isEqualTo(123456789);
		assertThat(Timestamps.parse("yesterday", null)).isEmpty();
		assertThat(Timestamps.isOffset("Z") && Timestamps.isOffset("+01:00") && Timestamps.isOffset("-14:00") && Timestamps.isOffset("+14:00")).isTrue();
		assertThat(Timestamps.isOffset("CET") || Timestamps.isOffset("+15:00") || Timestamps.isOffset("+1:00") || Timestamps.isOffset("+14:30")).isFalse();
		assertThat(Timestamps.withOffset("2026-01-01T00:00:00", ZoneOffset.ofHours(1))).isEqualTo("2026-01-01T00:00:00+01:00");
		assertThat(Timestamps.withOffset("2026-01-01T00:00:00Z", ZoneOffset.ofHours(1))).isEqualTo("2026-01-01T00:00:00Z");
	}

	@Test
	void comparisonsWithAnUnknownTimezoneAreUncertainWithin28Hours() {
		OrderingKey zoned = OrderingKey.ofTime(Instant.parse("2026-01-01T12:00:00Z"), false);
		OrderingKey local = OrderingKey.ofTime(Instant.parse("2026-01-01T11:00:00Z"), true);
		OrderingKey.Comparison comparison = OrderingKey.compareCertain(local, zoned);
		assertThat(comparison.certain()).isFalse();
		assertThat(comparison.order()).isNegative();
		OrderingKey farLocal = OrderingKey.ofTime(Instant.parse("2026-01-05T11:00:00Z"), true);
		assertThat(OrderingKey.compareCertain(farLocal, zoned)).isEqualTo(new OrderingKey.Comparison(1, true));
		assertThat(OrderingKey.isBeforeBookmark(local, zoned)).isFalse();
		assertThat(OrderingKey.isBeforeBookmark(OrderingKey.ofTime(Instant.parse("2025-12-01T00:00:00Z"), true), zoned)).isTrue();
		assertThat(OrderingKey.latestEarliest(local, zoned)).isEqualTo(zoned);
	}

	@Test
	void implicitKeysCompareByPageThenIndex() {
		assertThat(OrderingKey.compare(OrderingKey.ofPage(0, 5), OrderingKey.ofPage(1, 0))).isNegative();
		assertThat(OrderingKey.compare(OrderingKey.ofPage(1, 1), OrderingKey.ofPage(1, 0))).isPositive();
		assertThat(OrderingKey.compareLower(null, OrderingKey.ofPage(0, 0))).isNegative();
		OrderingKey key = OrderingKey.ofTime(Instant.parse("2026-01-01T00:00:00Z"), true, new Sequence("3", XSD + "integer"));
		assertThat(OrderingKey.fromJson(key.toJson())).isEqualTo(key);
	}
}
