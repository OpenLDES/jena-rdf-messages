package org.openldes.jena.messages.ldes;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.vocabulary.RDF;

/** The terms of the LDES vocabulary (https://w3id.org/ldes#) that the client uses, and the TREE terms that mark an LDES 1.0. */
public final class LdesVocab {

	public static final String NS = "https://w3id.org/ldes#";
	public static final String TREE_NS = "https://w3id.org/tree#";
	public static final String XSD_NS = "http://www.w3.org/2001/XMLSchema#";

	/** The profile that a response can announce in its media type. */
	public static final String PROFILE = "https://w3id.org/ldes/specification";

	private LdesVocab() {
	}

	private static Node ldes(String name) {
		return NodeFactory.createURI(NS + name);
	}

	public static final Node EVENT_STREAM = ldes("EventStream");
	public static final Node RELATION = ldes("relation");
	public static final Node PAGE = ldes("page");
	public static final Node MEMBER = ldes("member");
	public static final Node IMMUTABLE = ldes("immutable");
	public static final Node TIME_FROM = ldes("timeFrom");
	public static final Node TIME_UNTIL = ldes("timeUntil");
	public static final Node SEQUENCE_FROM = ldes("sequenceFrom");
	public static final Node SEQUENCE_UNTIL = ldes("sequenceUntil");
	public static final Node PAGE_NUMBER = ldes("pageNumber");
	public static final Node PAGE_NUMBER_UNTIL = ldes("pageNumberUntil");
	public static final Node MEMBER_GRAPH = ldes("memberGraph");
	public static final Node MEMBER_TYPE = ldes("memberType");
	public static final Node MEMBER_PATH = ldes("memberPath");
	public static final Node TIMESTAMP_PATH = ldes("timestampPath");
	public static final Node SEQUENCE_PATH = ldes("sequencePath");
	public static final Node VERSION_OF_PATH = ldes("versionOfPath");
	public static final Node VERSION_TIMESTAMP_PATH = ldes("versionTimestampPath");
	public static final Node VERSION_SEQUENCE_PATH = ldes("versionSequencePath");
	public static final Node TRANSACTION_PATH = ldes("transactionPath");
	public static final Node TRANSACTION_FINALIZED_PATH = ldes("transactionFinalizedPath");
	public static final Node TRANSACTION_FINALIZED_OBJECT = ldes("transactionFinalizedObject");
	public static final Node POLLING_INTERVAL = ldes("pollingInterval");
	public static final Node DEFAULT_TIMEZONE = ldes("defaultTimezone");
	public static final Node RETENTION_POLICY = ldes("retentionPolicy");
	public static final Node SHAPE = ldes("shape");

	public static final Node TYPE = RDF.Nodes.type;

	/** The TREE terms that mark a page as an LDES 1.0 page. */
	public static final String[] LDES1_PREDICATES = { TREE_NS + "view", TREE_NS + "member", TREE_NS + "relation",
			TREE_NS + "viewDescription" };
	/** The terms that mark a page as an LDES 2.0 page. */
	public static final Node[] LDES2_PREDICATES = { RELATION, PAGE, MEMBER };
}
