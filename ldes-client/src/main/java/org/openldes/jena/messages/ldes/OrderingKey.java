package org.openldes.jena.messages.ldes;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import org.apache.jena.atlas.json.JsonObject;

import org.openldes.jena.messages.ldes.Sequences.Sequence;

/**
 * The <a href="https://w3id.org/ldes/specification#ordering-keys">ordering key</a> of a member, or a bound of a relation:
 * the pair (timestamp, sequence), only a timestamp, only a sequence, or, in implicit order, the pair (page number, index).
 * <p>
 * A timestamp that was read without a timezone, while no default timezone is known, is {@code local}: it is compared
 * with other local timestamps as is, and with a timestamp that has a timezone as a period of 28 hours (timezones range
 * from -14:00 to +14:00), in which case the comparison can be uncertain.
 *
 * @param time     the instant of the timestamp, or null; a local timestamp is the instant as if it were UTC
 * @param local    whether the timestamp has an unknown timezone
 * @param sequence the sequence, or null
 * @param page     the page number in implicit order, or null
 * @param index    the index in the page in implicit order, or null
 */
public record OrderingKey(Instant time, boolean local, Sequence sequence, Long page, Long index) {

	private static final Duration MARGIN = Duration.ofHours(14);

	public static OrderingKey ofTime(Instant time, boolean local) {
		return new OrderingKey(time, local, null, null, null);
	}

	public static OrderingKey ofTime(Instant time, boolean local, Sequence sequence) {
		return new OrderingKey(time, local, sequence, null, null);
	}

	public static OrderingKey ofSequence(Sequence sequence) {
		return new OrderingKey(null, false, sequence, null, null);
	}

	public static OrderingKey ofPage(long page, long index) {
		return new OrderingKey(null, false, null, page, index);
	}

	/** The result of a comparison: the order, and whether it is certain (not when it depends on an unknown timezone). */
	public record Comparison(int order, boolean certain) {
	}

	private Instant earliest() {
		return local ? time.minus(MARGIN) : time;
	}

	private Instant latest() {
		return local ? time.plus(MARGIN) : time;
	}

	private static <T extends Comparable<T>> int compareOptional(T a, T b) {
		// An absent component is the lowest possible value
		if (a == null && b == null) {
			return 0;
		}
		if (a == null) {
			return -1;
		}
		if (b == null) {
			return 1;
		}
		return a.compareTo(b);
	}

	private static int compareSequences(Sequence a, Sequence b) {
		if (a == null && b == null) {
			return 0;
		}
		if (a == null) {
			return -1;
		}
		if (b == null) {
			return 1;
		}
		return Sequences.compare(a, b);
	}

	/**
	 * Compares two timestamps. Two timestamps that both have no timezone, or both have one, are compared as is. Otherwise
	 * the order is only certain when it holds for every timezone, and the uncertain order is that of the earliest instants.
	 */
	private static Comparison compareTimes(OrderingKey a, OrderingKey b) {
		if (a.local == b.local) {
			return new Comparison(Integer.signum(a.time.compareTo(b.time)), true);
		}
		if (a.latest().isBefore(b.earliest())) {
			return new Comparison(-1, true);
		}
		if (a.earliest().isAfter(b.latest())) {
			return new Comparison(1, true);
		}
		return new Comparison(Integer.signum(a.earliest().compareTo(b.earliest())), false);
	}

	/** Compares two keys, taking into account that the order of timestamps without a timezone can be uncertain. */
	public static Comparison compareCertain(OrderingKey a, OrderingKey b) {
		if (a.page != null || b.page != null) {
			int order = compareOptional(a.page, b.page);
			if (order == 0) {
				order = compareOptional(a.index, b.index);
			}
			return new Comparison(Integer.signum(order), true);
		}
		Comparison time = a.time != null && b.time != null ? compareTimes(a, b)
				: new Comparison(Integer.signum(compareOptional(a.time, b.time)), true);
		int sequence = Integer.signum(compareSequences(a.sequence, b.sequence));
		if (time.certain()) {
			return new Comparison(time.order() != 0 ? time.order() : sequence, true);
		}
		// The timestamps are indeterminate: the sequences give the order, but the outcome stays uncertain
		return new Comparison(sequence != 0 ? sequence : time.order(), false);
	}

	/** The order in which keys are processed. When it is uncertain, that is by the earliest instant. */
	public static int compare(OrderingKey a, OrderingKey b) {
		return compareCertain(a, b).order();
	}

	/** The lower bound of a relation: an absent bound is the lowest possible key. */
	public static int compareLower(OrderingKey a, OrderingKey b) {
		if (a == null && b == null) {
			return 0;
		}
		if (a == null) {
			return -1;
		}
		if (b == null) {
			return 1;
		}
		return compare(a, b);
	}

	/**
	 * Whether a relation with this upper bound can only lead to members that have been emitted: the upper key is certainly
	 * smaller than or equal to the bookmark.
	 */
	public static boolean isBeforeBookmark(OrderingKey upper, OrderingKey bookmark) {
		if (upper.time != null && bookmark.time != null && upper.local != bookmark.local) {
			return !upper.latest().isAfter(bookmark.earliest());
		}
		Comparison comparison = compareCertain(upper, bookmark);
		return comparison.certain() && comparison.order() <= 0;
	}

	/** Of two keys, the one of which the earliest instant is the latest: the bookmark after an uncertain comparison. */
	public static OrderingKey latestEarliest(OrderingKey a, OrderingKey b) {
		if (a.time == null || b.time == null) {
			return compare(a, b) > 0 ? a : b;
		}
		return !b.earliest().isBefore(a.earliest()) ? b : a;
	}

	// ---- JSON, for the saved state

	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		if (time != null) {
			json.put("time", time.toString());
			if (local) {
				json.put("local", true);
			}
		}
		if (sequence != null) {
			json.put("sequence", sequence.lexical());
			json.put("datatype", sequence.datatype());
		}
		if (page != null) {
			json.put("page", page);
			json.put("index", index);
		}
		return json;
	}

	public static OrderingKey fromJson(JsonObject json) {
		Instant time = json.hasKey("time") ? Instant.parse(json.get("time").getAsString().value()) : null;
		boolean local = json.hasKey("local") && json.get("local").getAsBoolean().value();
		Sequence sequence = json.hasKey("sequence")
				? new Sequence(json.get("sequence").getAsString().value(), json.get("datatype").getAsString().value())
				: null;
		Long page = json.hasKey("page") ? json.get("page").getAsNumber().value().longValue() : null;
		Long index = json.hasKey("index") ? json.get("index").getAsNumber().value().longValue() : null;
		return new OrderingKey(time, local, sequence, page, index);
	}

	@Override
	public String toString() {
		if (page != null) {
			return "(page " + page + ", index " + index + ")";
		}
		return "(" + (time == null ? "" : time + (local ? " local" : "")) + (sequence == null ? "" : ", " + sequence.lexical()) + ")";
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof OrderingKey other && Objects.equals(time, other.time) && local == other.local
				&& Objects.equals(sequence, other.sequence) && Objects.equals(page, other.page)
				&& Objects.equals(index, other.index);
	}

	@Override
	public int hashCode() {
		return Objects.hash(time, local, sequence, page, index);
	}
}
