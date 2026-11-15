package org.openldes.jena.messages;

import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.apache.jena.atlas.iterator.Iter;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.riot.system.PrefixMap;
import org.apache.jena.riot.system.PrefixMapFactory;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.graph.GraphFactory;

/**
 * An <a href="https://w3c-cg.github.io/rsp/spec/messages#rdf-messages">RDF Message</a>: an RDF dataset that is to be
 * interpreted atomically, as one communicative act. It can be empty.
 * <p>
 * A message is not a store: it is the unit that a consumer processes (validates, transforms, loads, forwards) before
 * anything is added anywhere. Structurally it is a {@link DatasetGraph}; the difference with any other dataset is only
 * the intended interpretation. The quads of a message are not merged with those of other messages, and what is asserted in
 * one message is not asserted in another.
 * <p>
 * Instances are created by the parsers (see {@link RDFMessagesParser} and {@link MessageCollector}), or with
 * {@link #of(DatasetGraph)} by producers that want to write a message log.
 */
public final class RdfMessage {

	private final DatasetGraph dataset;
	private final long index;
	private final PrefixMap prefixes;

	private RdfMessage(long index, DatasetGraph dataset, PrefixMap prefixes) {
		this.index = index;
		this.dataset = Objects.requireNonNull(dataset, "dataset");
		this.prefixes = Objects.requireNonNull(prefixes, "prefixes");
	}

	/** A message with the quads of a dataset, without a position in a log. */
	public static RdfMessage of(DatasetGraph dataset) {
		return new RdfMessage(-1, dataset, PrefixMapFactory.create());
	}

	/** A message with the quads of a dataset, the prefixes that were in effect, and its 0-based position in the log. */
	public static RdfMessage of(long index, DatasetGraph dataset, PrefixMap prefixes) {
		return new RdfMessage(index, dataset, prefixes);
	}

	/** A message with the given quads. */
	public static RdfMessage of(Iterable<Quad> quads) {
		DatasetGraph dsg = DatasetGraphFactory.create();
		quads.forEach(dsg::add);
		return of(dsg);
	}

	/** An empty message. */
	public static RdfMessage empty() {
		return of(DatasetGraphFactory.create());
	}

	/** The quads of the message, as a dataset. */
	public DatasetGraph dataset() {
		return dataset;
	}

	/** The 0-based position of the message in the log it was read from, or -1 when it was not read from a log. */
	public long index() {
		return index;
	}

	/** The prefixes that were declared when the message ended. Prefixes persist across messages. */
	public PrefixMap prefixes() {
		return prefixes;
	}

	public boolean isEmpty() {
		return dataset.isEmpty();
	}

	/** The number of quads in the message (triples in the default graph included). */
	public long size() {
		return Iter.count(dataset.find());
	}

	/** All quads of the message, with {@link Quad#defaultGraphNodeGenerated} as graph for the default graph. */
	public Iterator<Quad> quads() {
		return dataset.find();
	}

	public Stream<Quad> stream() {
		return StreamSupport.stream(Spliterators.spliteratorUnknownSize(quads(), Spliterator.ORDERED), false);
	}

	public List<Quad> toList() {
		return Iter.toList(quads());
	}

	/**
	 * The union of all graphs of the message, the default graph included: the data graph for evaluating a SHACL shape or a
	 * property path on the message (see the <a href="https://w3id.org/ldes/specification#membership">LDES
	 * specification</a>).
	 */
	public Graph unionGraph() {
		Graph union = GraphFactory.createDefaultGraph();
		dataset.find().forEachRemaining(quad -> union.add(quad.asTriple()));
		return union;
	}

	/** Whether the message contains a quad in a named graph. */
	public boolean hasNamedGraphs() {
		return dataset.listGraphNodes().hasNext();
	}

	/** The names of the graphs of the message, without the default graph. */
	public List<Node> graphNames() {
		return Iter.toList(dataset.listGraphNodes());
	}

	/** Adds all quads of the message to a dataset. This is the "processing" step before a message is stored. */
	public void addTo(DatasetGraph target) {
		dataset.find().forEachRemaining(target::add);
	}

	@Override
	public String toString() {
		return "RdfMessage" + (index >= 0 ? " #" + index : "") + " (" + size() + " quads)";
	}
}
