package org.openldes.jena.messages.ldes;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.apache.jena.atlas.json.JSON;
import org.apache.jena.atlas.json.JsonArray;
import org.apache.jena.atlas.json.JsonObject;
import org.apache.jena.atlas.json.JsonValue;

/**
 * The <a href="https://w3id.org/ldes/specification#state-management">state</a> that a client keeps between runs: the
 * ordering key of the last emitted member, the identifiers of the emitted members that share that key, the immutable
 * pages that were read completely, and the ETag and description of the pages that can change. It stays small, no matter
 * how long the stream becomes.
 */
public final class Bookmark {

	/** A page that was read completely and can change: the ETag of the response and its description message (N-Quads). */
	public record CachedPage(String etag, String description) {
	}

	private OrderingKey key;
	private final Set<String> members = new LinkedHashSet<>();
	private final Set<String> immutable = new LinkedHashSet<>();
	private final Map<String, CachedPage> pages = new LinkedHashMap<>();

	public Bookmark() {
	}

	public Bookmark copy() {
		Bookmark copy = new Bookmark();
		copy.key = key;
		copy.members.addAll(members);
		copy.immutable.addAll(immutable);
		copy.pages.putAll(pages);
		return copy;
	}

	/** The ordering key of the last emitted member, or null when no member was emitted yet. */
	public OrderingKey key() {
		return key;
	}

	public void setKey(OrderingKey key) {
		this.key = key;
	}

	/** The identifiers of the emitted members whose ordering key is equal to the key. */
	public Set<String> members() {
		return members;
	}

	/** Pages marked immutable by the page that links to them, read completely, without relations. */
	public Set<String> immutable() {
		return immutable;
	}

	/** The pages that can change, by the IRI they are requested at. */
	public Map<String, CachedPage> pages() {
		return pages;
	}

	public boolean isEmpty() {
		return key == null && members.isEmpty() && immutable.isEmpty() && pages.isEmpty();
	}

	public JsonObject toJson() {
		JsonObject json = new JsonObject();
		if (key != null) {
			json.put("key", key.toJson());
		}
		JsonArray memberArray = new JsonArray();
		members.forEach(memberArray::add);
		json.put("members", memberArray);
		if (!immutable.isEmpty()) {
			JsonArray array = new JsonArray();
			immutable.forEach(array::add);
			json.put("immutable", array);
		}
		if (!pages.isEmpty()) {
			JsonObject pageObject = new JsonObject();
			pages.forEach((url, page) -> {
				JsonObject cached = new JsonObject();
				cached.put("etag", page.etag());
				if (page.description() != null) {
					cached.put("description", page.description());
				}
				pageObject.put(url, cached);
			});
			json.put("pages", pageObject);
		}
		return json;
	}

	public static Bookmark fromJson(JsonObject json) {
		Bookmark bookmark = new Bookmark();
		if (json.hasKey("key")) {
			bookmark.key = OrderingKey.fromJson(json.get("key").getAsObject());
		}
		if (json.hasKey("members")) {
			for (JsonValue member : json.get("members").getAsArray()) {
				bookmark.members.add(member.getAsString().value());
			}
		}
		if (json.hasKey("immutable")) {
			for (JsonValue page : json.get("immutable").getAsArray()) {
				bookmark.immutable.add(page.getAsString().value());
			}
		}
		if (json.hasKey("pages")) {
			JsonObject pages = json.get("pages").getAsObject();
			for (String url : pages.keys()) {
				JsonObject cached = pages.get(url).getAsObject();
				bookmark.pages.put(url, new CachedPage(cached.get("etag").getAsString().value(),
						cached.hasKey("description") ? cached.get("description").getAsString().value() : null));
			}
		}
		return bookmark;
	}

	public static Bookmark fromJson(String json) {
		return fromJson(JSON.parse(json));
	}

	@Override
	public String toString() {
		return toJson().toString();
	}
}
