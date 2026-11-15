package org.openldes.jena.messages.ldes;

/**
 * What a client reports besides members: the context information, the pages it reads, and the end of every
 * synchronization run, which the specification requires a client to signal to the processors further in the pipeline.
 * All methods have empty defaults.
 */
public interface LdesListener {

	/** The context information of the LDES has been read from the root page, before any member is emitted. */
	default void onContext(LdesContext context) {
	}

	/** A page has been opened. The description is null when the page has no description message. */
	default void onPage(String url, PageDescription description) {
	}

	/**
	 * A synchronization run is finished: every member that was available has been emitted.
	 *
	 * @param emitted the number of members emitted in this run
	 */
	default void onRunFinished(long emitted) {
	}

	/** A member was skipped because it did not match the time window of the client. */
	default void onMemberSkipped(LdesMember member) {
	}
}
