package org.openldes.jena.messages.ldes;

import java.util.Optional;

/** Where a client keeps its {@link Bookmark} between runs, by the IRI of the LDES. */
public interface BookmarkStore {

	Optional<Bookmark> load(String ldes);

	void save(String ldes, Bookmark bookmark);

	/** Forgets the bookmark: the next run starts from the beginning. */
	void clear(String ldes);
}
