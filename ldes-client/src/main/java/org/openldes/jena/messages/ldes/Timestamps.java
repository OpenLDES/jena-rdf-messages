package org.openldes.jena.messages.ldes;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parsing of {@code xsd:dateTime} values, see <a href="https://w3id.org/ldes/specification#no-timezone">timestamps
 * without a timezone</a>: a value without a timezone gets the default timezone when there is one, and is otherwise
 * <em>local</em>, in the same unknown timezone as every other such value of the LDES.
 */
public final class Timestamps {

	/** The timezone of an xsd:dateTime: Z, or an offset of at most 14 hours. */
	private static final Pattern OFFSET = Pattern.compile("^(Z|[+-](0\\d|1[0-3]):[0-5]\\d|[+-]14:00)$");
	private static final Pattern FRACTION = Pattern.compile("\\.(\\d+)");
	private static final Pattern TIMEZONE = Pattern.compile("(Z|[+-]\\d{2}:\\d{2})$");

	private Timestamps() {
	}

	/** An instant, and whether it was read without a timezone (and without a default timezone). */
	public record Parsed(Instant instant, boolean local) {
	}

	/** Whether a string is a timezone offset as accepted for {@code ldes:defaultTimezone}: Z or -14:00 to +14:00. */
	public static boolean isOffset(String value) {
		return value != null && OFFSET.matcher(value).matches();
	}

	public static Optional<ZoneOffset> offset(String value) {
		return isOffset(value) ? Optional.of(ZoneOffset.of(value)) : Optional.empty();
	}

	/**
	 * Parses an xsd:dateTime. Fractions of a second beyond nanoseconds are truncated.
	 *
	 * @param defaultOffset the timezone of values without one, or null when it is unknown
	 * @return the parsed value, or empty when the value is not an xsd:dateTime
	 */
	public static Optional<Parsed> parse(String lexical, ZoneOffset defaultOffset) {
		if (lexical == null) {
			return Optional.empty();
		}
		String value = lexical.trim();
		if (!value.contains("T")) {
			return Optional.empty();
		}
		Matcher fraction = FRACTION.matcher(value);
		if (fraction.find() && fraction.group(1).length() > 9) {
			value = value.substring(0, fraction.start(1) + 9) + value.substring(fraction.end(1));
		}
		boolean zoned = TIMEZONE.matcher(value).find();
		try {
			if (zoned) {
				return Optional.of(new Parsed(OffsetDateTime.parse(value).toInstant(), false));
			}
			LocalDateTime local = LocalDateTime.parse(value);
			ZoneOffset offset = defaultOffset == null ? ZoneOffset.UTC : defaultOffset;
			return Optional.of(new Parsed(local.atOffset(offset).toInstant(), defaultOffset == null));
		} catch (DateTimeParseException e) {
			return Optional.empty();
		}
	}

	/** Appends the default timezone to a value without a timezone, as a client does when it emits a member. */
	public static String withOffset(String lexical, ZoneOffset offset) {
		String value = lexical.trim();
		if (TIMEZONE.matcher(value).find()) {
			return value;
		}
		return value + (offset.equals(ZoneOffset.UTC) ? "Z" : offset.getId());
	}
}
