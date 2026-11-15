package org.openldes.jena.messages.ldes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import org.apache.jena.graph.Node;

/**
 * The sequence component of an ordering key: an {@code xsd}-typed literal, compared as the
 * <a href="https://www.w3.org/TR/xpath-functions-31/#func-compare">XPath comparison</a> does: numbers numerically, dates
 * as points in time, and everything else by Unicode code points.
 */
public final class Sequences {

	private static final Set<String> NUMERIC = Set.of("byte", "decimal", "double", "float", "int", "integer", "long",
			"negativeInteger", "nonNegativeInteger", "nonPositiveInteger", "positiveInteger", "short", "unsignedByte",
			"unsignedInt", "unsignedLong", "unsignedShort");
	private static final Set<String> DATES = Set.of("date", "dateTime", "dateTimeStamp");

	private Sequences() {
	}

	/** A sequence value: the lexical form and the datatype of the literal. */
	public record Sequence(String lexical, String datatype) {

		public static Sequence of(Node literal) {
			return new Sequence(literal.getLiteralLexicalForm(), literal.getLiteralDatatypeURI());
		}

		public boolean isNumeric() {
			return NUMERIC.contains(local(datatype));
		}

		public boolean isDate() {
			return DATES.contains(local(datatype));
		}

		private static String local(String datatype) {
			return datatype != null && datatype.startsWith(LdesVocab.XSD_NS) ? datatype.substring(LdesVocab.XSD_NS.length())
					: "";
		}
	}

	public static int compare(Sequence a, Sequence b) {
		if (a.isNumeric() && b.isNumeric()) {
			try {
				return new BigDecimal(a.lexical().trim()).compareTo(new BigDecimal(b.lexical().trim()));
			} catch (NumberFormatException e) {
				// INF, NaN and malformed numbers fall through to the string comparison
			}
		}
		if (a.isDate() && b.isDate()) {
			Optional<Instant> x = pointInTime(a.lexical());
			Optional<Instant> y = pointInTime(b.lexical());
			if (x.isPresent() && y.isPresent()) {
				return x.get().compareTo(y.get());
			}
		}
		return compareCodepoints(a.lexical(), b.lexical());
	}

	/** A point on the time line, for the comparison of sequences: a value without timezone is read as UTC. */
	private static Optional<Instant> pointInTime(String lexical) {
		String value = lexical.trim();
		if (!value.contains("T")) {
			return Timestamps.parse(value + "T00:00:00", null).map(Timestamps.Parsed::instant);
		}
		return Timestamps.parse(value, null).map(Timestamps.Parsed::instant);
	}

	private static int compareCodepoints(String a, String b) {
		int[] x = a.codePoints().toArray();
		int[] y = b.codePoints().toArray();
		for (int i = 0; i < Math.min(x.length, y.length); i++) {
			if (x[i] != y[i]) {
				return x[i] < y[i] ? -1 : 1;
			}
		}
		return Integer.compare(x.length, y.length);
	}
}
