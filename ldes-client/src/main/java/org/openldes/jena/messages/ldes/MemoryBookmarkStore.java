package org.openldes.jena.messages.ldes;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** A bookmark store in memory: the state lives as long as the store instance. */
public final class MemoryBookmarkStore implements BookmarkStore {

	private final Map<String, Bookmark> bookmarks = new ConcurrentHashMap<>();

	@Override
	public Optional<Bookmark> load(String ldes) {
		return Optional.ofNullable(bookmarks.get(ldes)).map(Bookmark::copy);
	}

	@Override
	public void save(String ldes, Bookmark bookmark) {
		bookmarks.put(ldes, bookmark.copy());
	}

	@Override
	public void clear(String ldes) {
		bookmarks.remove(ldes);
	}
}
