package org.openldes.jena.messages.ldes;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.path.Path;
import org.openldes.jena.messages.MessageLog;
import org.openldes.jena.messages.RDFMessages;
import org.openldes.jena.messages.RDFMessagesWriter;
import org.openldes.jena.messages.RdfMessage;
import org.openldes.jena.messages.ldes.Bookmark.CachedPage;
import org.openldes.jena.messages.ldes.OrderingKey.Comparison;
import org.openldes.jena.messages.ldes.PageDescription.Relation;
import org.openldes.jena.messages.ldes.Sequences.Sequence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One <a href="https://w3id.org/ldes/specification#traversal">synchronization run</a>: a merge of sorted logs. Every
 * page is ordered, and the relations tell where the members of other pages can be found in the order, so the client
 * neither sorts members nor remembers which pages it has visited: its state is the {@link Bookmark}.
 * <p>
 * Pages are requested ahead, up to the prefetch window, in the order in which they are followed; a page that is
 * requested ahead buffers only a few messages, so the memory use stays bounded. The order of emission is not changed by
 * prefetching.
 */
final class Traversal {

	private static final Logger LOG = LoggerFactory.getLogger(Traversal.class);

	/** The messages buffered of a page that is requested ahead; a page that is being read gets the usual buffer. */
	static final int PREFETCH_BUFFERED_MESSAGES = 64;
	static final int BUFFERED_MESSAGES = 512;

	/** Hands a member to the consumer. Returns false when the consumer is gone. */
	interface Emitter {
		boolean emit(LdesMember member, Consumer<Bookmark> advance);
	}

	/** Whether members with a key in [lower, upper] may still be wanted; an absent bound is unbounded. */
	interface Relevance {
		boolean test(OrderingKey lower, OrderingKey upper);
	}

	private final LdesContext context;
	private final Bookmark bookmark;
	private final PageFetcher fetcher;
	private final int prefetchWindow;
	private final Relevance relevance;
	private final Emitter emitter;
	private final LdesListener listener;
	private final ExecutorService executor;

	Traversal(LdesContext context, Bookmark bookmark, PageFetcher fetcher, int prefetchWindow, Relevance relevance,
			Emitter emitter, LdesListener listener, ExecutorService executor) {
		this.context = context;
		this.bookmark = bookmark;
		this.fetcher = fetcher;
		this.prefetchWindow = Math.max(0, prefetchWindow);
		this.relevance = relevance;
		this.emitter = emitter;
		this.listener = listener;
		this.executor = executor;
	}

	private record Next(RdfMessage message, Node id, OrderingKey key) {
	}

	/** A page of which the description message has been read, and of which the next member message is known. */
	private final class OpenPage {
		final String url;
		final Long pageNumber;
		final MessageLog log;
		String linkedAs;
		boolean leaf = true;
		RdfMessage descriptionMessage;
		String etag;
		Next next;
		private long index = 0;
		private OrderingKey last;

		OpenPage(String url, Long pageNumber, MessageLog log) {
			this.url = url;
			this.pageNumber = pageNumber;
			this.log = log;
		}

		/** Reads the next member message of the page, to know its key. Closes the page when there is none. */
		void advance(RdfMessage message, boolean readAhead) {
			RdfMessage current = readAhead ? log.nextOrNull() : message;
			if (current == null) {
				next = null;
				log.close();
				return;
			}
			if (current.isEmpty()) {
				throw new LdesClientException("Empty message in page " + url + ": an LDES does not allow empty messages");
			}
			Graph union = current.unionGraph();
			Node id = context.membership().findMemberId(current, union);
			OrderingKey key = orderingKey(id, current, union, pageNumber, index, url);
			if (last != null) {
				Comparison order = OrderingKey.compareCertain(key, last);
				// Only when the order is certain, the page does not respect the specification
				if (order.certain() && order.order() < 0) {
					throw new LdesClientException("Page " + url + " does not respect the ordering of the LDES: " + id
							+ " comes after a member with a larger key");
				}
				if (order.order() >= 0) {
					last = key;
				}
			} else {
				last = key;
			}
			index++;
			next = new Next(current, id, key);
		}
	}

	// ---- Ordering keys and members

	private static Node singleLiteral(List<Node> values, String what, Node id) {
		if (values.size() != 1 || !values.get(0).isLiteral()) {
			throw new LdesClientException("Expected exactly one literal for " + what + " of member " + id + ", found "
					+ values.size());
		}
		return values.get(0);
	}

	OrderingKey orderingKey(Node id, RdfMessage message, Graph union, Long pageNumber, long index, String pageUrl) {
		if (context.mode() == OrderMode.IMPLICIT) {
			if (pageNumber == null) {
				throw new LdesClientException("Cannot determine the page number of " + pageUrl + ": its relation has no ldes:pageNumber");
			}
			return OrderingKey.ofPage(pageNumber, index);
		}
		Timestamps.Parsed time = null;
		if (context.timestampPath() != null) {
			Node literal = singleLiteral(Paths.values(context.timestampPath(), id, message, union), "ldes:timestampPath", id);
			String datatype = literal.getLiteralDatatypeURI();
			Optional<Timestamps.Parsed> parsed = Timestamps.parse(literal.getLiteralLexicalForm(), context.defaultOffset());
			if (datatype == null || !datatype.startsWith(LdesVocab.XSD_NS + "dateTime") || parsed.isEmpty()) {
				throw new LdesClientException("The timestamp of member " + id + " is not an xsd:dateTime: " + literal);
			}
			time = parsed.get();
		}
		Sequence sequence = null;
		if (context.sequencePath() != null) {
			Node literal = singleLiteral(Paths.values(context.sequencePath(), id, message, union), "ldes:sequencePath", id);
			if (literal.getLiteralDatatypeURI() == null || !literal.getLiteralDatatypeURI().startsWith(LdesVocab.XSD_NS)) {
				throw new LdesClientException("The sequence of member " + id + " is not an xsd literal: " + literal);
			}
			sequence = Sequence.of(literal);
		}
		if (time == null) {
			return OrderingKey.ofSequence(sequence);
		}
		return OrderingKey.ofTime(time.instant(), time.local(), sequence);
	}

	private LdesMember toMember(Next next, String pageUrl) {
		RdfMessage message = next.message();
		Graph union = message.unionGraph();
		Node id = next.id();
		Optional<Node> sequence = optionalValue(context.sequencePath(), id, message, union);
		Optional<Node> isVersionOf = optionalValue(context.versionOfPath(), id, message, union);
		Optional<Boolean> finalized = Optional.empty();
		if (context.transactionFinalizedPath() != null) {
			Node expected = context.transactionFinalizedObject() != null ? context.transactionFinalizedObject()
					: NodeFactory.createLiteralDT("true", XSDDatatype.XSDboolean);
			finalized = optionalValue(context.transactionFinalizedPath(), id, message, union)
					.map(value -> value.equals(expected) || sameBoolean(value, expected));
		}
		Optional<Node> type = message.stream().filter(q -> q.getSubject().equals(id) && q.getPredicate().equals(LdesVocab.TYPE))
				.map(Quad::getObject).findFirst();
		if (context.defaultOffset() != null) {
			message = withDefaultTimezone(message, context.defaultOffset());
		}
		return new LdesMember(id, message, next.key(), pageUrl, Optional.ofNullable(next.key().time()), sequence,
				isVersionOf, finalized, type);
	}

	private static boolean sameBoolean(Node a, Node b) {
		return a.isLiteral() && b.isLiteral() && a.getLiteralLexicalForm().trim().equals(b.getLiteralLexicalForm().trim());
	}

	private static Optional<Node> optionalValue(Path path, Node id, RdfMessage message, Graph union) {
		if (path == null) {
			return Optional.empty();
		}
		List<Node> values = Paths.values(path, id, message, union);
		return values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
	}

	/** Adds the default timezone to every xsd:dateTime literal of the message that has none. */
	static RdfMessage withDefaultTimezone(RdfMessage message, java.time.ZoneOffset offset) {
		boolean changed = false;
		DatasetGraph target = DatasetGraphFactory.create();
		for (Quad quad : message.toList()) {
			Node object = quad.getObject();
			if (object.isLiteral() && XSDDatatype.XSDdateTime.getURI().equals(object.getLiteralDatatypeURI())) {
				String lexical = object.getLiteralLexicalForm();
				String withOffset = Timestamps.withOffset(lexical, offset);
				if (!withOffset.equals(lexical)) {
					object = NodeFactory.createLiteralDT(withOffset, XSDDatatype.XSDdateTime);
					quad = Quad.create(quad.getGraph(), quad.getSubject(), quad.getPredicate(), object);
					changed = true;
				}
			}
			target.add(quad);
		}
		return changed ? RdfMessage.of(message.index(), target, message.prefixes()) : message;
	}

	// ---- Pages

	/** The log of a page that has not changed: its description message as it was, and no members. */
	static FetchedPage cachedPage(String url, FetchedPage answer, CachedPage cached) {
		List<RdfMessage> messages = cached.description() == null ? List.of()
				: RDFMessages.parse(cached.description(), Lang.NQUADS);
		String etag = answer.etag() != null ? answer.etag() : cached.etag();
		return new FetchedPage(url, 200, etag, null, true, false, MessageLog.of(messages));
	}

	private static String serialize(RdfMessage message) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		RDFMessagesWriter.create().lang(Lang.NQUADS).writeVersion(false).write(out, List.of(message));
		return out.toString(StandardCharsets.UTF_8);
	}

	private record Opened(OpenPage page, PageDescription description) {
	}

	/** Opens a page: reads its description message, if any, and its first member message. */
	private Opened openPage(FetchedPage page, Long pageNumber, boolean isRoot, RdfMessage first, boolean firstKnown) {
		RdfMessage message = firstKnown ? first : page.log().nextOrNull();
		PageDescription description = null;
		RdfMessage descriptionMessage = null;
		if (message != null && !message.isEmpty() && PageDescription.isDescriptionMessage(message, page.url())) {
			description = PageDescription.read(message, page.url(), context.mode(), context.defaultOffset());
			descriptionMessage = message;
			if (isRoot) {
				pageNumber = description.pageNumber().orElse(0L);
			}
			message = page.log().nextOrNull();
		} else if (isRoot) {
			pageNumber = 0L;
		}
		OpenPage open = new OpenPage(page.url(), pageNumber, page.log());
		open.etag = page.etag();
		open.descriptionMessage = descriptionMessage;
		open.advance(message, false);
		return new Opened(open, description);
	}

	// ---- The run

	private record Prefetched(FetchedPage page, RdfMessage first) {
	}

	private final List<OpenPage> pages = new ArrayList<>();
	private final List<Relation> relations = new ArrayList<>();
	private final Set<String> seen = new HashSet<>();
	private final Set<String> markedImmutable = new HashSet<>();
	private final Set<String> completed = new HashSet<>();
	private final Set<String> referenced = new HashSet<>();
	private final Map<String, CachedPage> observed = new LinkedHashMap<>();
	private final Map<Relation, Future<Prefetched>> prefetched = new HashMap<>();
	private volatile boolean finishedRun = false;
	private long emitted = 0;

	private boolean relevant(OrderingKey lower, OrderingKey upper) {
		return relevance == null || relevance.test(lower, upper);
	}

	private FetchedPage openRelation(Relation relation, int bufferedMessages) {
		CachedPage cached = bookmark.pages().get(relation.page());
		FetchedPage page = fetcher.fetch(relation.page(), cached == null ? null : cached.etag(), bufferedMessages);
		if (page.status() != 304) {
			return page;
		}
		if (cached == null) {
			throw new LdesClientException(relation.page() + " answered HTTP 304, while the client did not send an ETag");
		}
		LOG.debug("{} has not changed (HTTP 304)", relation.page());
		return cachedPage(relation.page(), page, cached);
	}

	private Future<Prefetched> prefetchPage(Relation relation) {
		Future<Prefetched> pending = executor.submit(() -> {
			FetchedPage page = openRelation(relation, PREFETCH_BUFFERED_MESSAGES);
			RdfMessage first = page.log().nextOrNull();
			if (finishedRun) {
				page.log().close();
				return new Prefetched(page, first);
			}
			try {
				if (first != null && !first.isEmpty() && PageDescription.isDescriptionMessage(first, page.url())) {
					List<Relation> found = PageDescription.read(first, page.url(), context.mode(), context.defaultOffset())
							.relations();
					synchronized (Traversal.this) {
						addRelations(found);
						prefetchAhead();
					}
				}
			} catch (RuntimeException e) {
				// An invalid description is reported when the page is opened
			}
			return new Prefetched(page, first);
		});
		prefetched.put(relation, pending);
		return pending;
	}

	/** Requests the pages of the relations that are followed next, until the window is full. */
	private synchronized void prefetchAhead() {
		if (finishedRun || prefetched.size() >= prefetchWindow) {
			return;
		}
		List<Relation> upcoming = new ArrayList<>();
		for (Relation relation : relations) {
			if (!prefetched.containsKey(relation) && relevant(relation.lower(), relation.upper())) {
				upcoming.add(relation);
			}
		}
		upcoming.sort((a, b) -> OrderingKey.compareLower(a.lower(), b.lower()));
		for (Relation relation : upcoming.subList(0, Math.min(upcoming.size(), prefetchWindow - prefetched.size()))) {
			LOG.debug("Requesting {} ahead", relation.page());
			prefetchPage(relation);
		}
	}

	private synchronized void addRelations(List<Relation> found) {
		for (Relation relation : found) {
			referenced.add(relation.page());
			// Relations that can only lead to members that have been emitted already, are not followed
			if (relation.upper() != null && bookmark.key() != null && OrderingKey.isBeforeBookmark(relation.upper(), bookmark.key())) {
				LOG.debug("Skipping {}: before the bookmark", relation.page());
				continue;
			}
			if (!relevant(relation.lower(), relation.upper())) {
				LOG.debug("Skipping {}: not wanted by the conditions", relation.page());
				continue;
			}
			// An immutable page that was read completely in an earlier run does not need to be fetched again
			if (bookmark.immutable().contains(relation.page())) {
				LOG.debug("Skipping {}: immutable, and already processed", relation.page());
				continue;
			}
			String identity = relation.page() + " " + relation.lower() + " " + relation.upper();
			if (seen.add(identity)) {
				relations.add(relation);
			}
		}
	}

	private void open(FetchedPage fetched, Long pageNumber, boolean isRoot, RdfMessage first, boolean firstKnown,
			String linkedAs) {
		Opened opened = openPage(fetched, pageNumber, isRoot, first, firstKnown);
		OpenPage page = opened.page();
		page.linkedAs = linkedAs;
		page.leaf = opened.description() == null || opened.description().relations().isEmpty();
		listener.onPage(fetched.url(), opened.description());
		if (opened.description() != null) {
			markedImmutable.addAll(opened.description().immutablePages());
			addRelations(opened.description().relations());
		}
		if (page.next != null) {
			pages.add(page);
		} else {
			finished(page);
		}
		prefetchAhead();
	}

	/** A page that is read completely, is immutable, and has no relations, does not have to be read again. */
	private void finished(OpenPage page) {
		if (page.linkedAs != null && page.leaf && markedImmutable.contains(page.linkedAs)) {
			completed.add(page.linkedAs);
		} else if (page.etag != null) {
			// A page that can change: the next run requests it with this ETag
			String key = page.linkedAs != null ? page.linkedAs : context.rootPage();
			observed.put(key, new CachedPage(page.etag, page.descriptionMessage == null ? null : serialize(page.descriptionMessage)));
		}
	}

	private Prefetched await(Future<Prefetched> pending) {
		try {
			return pending.get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new LdesClientException("Interrupted while waiting for a page", e);
		} catch (ExecutionException e) {
			if (e.getCause() instanceof RuntimeException runtime) {
				throw runtime;
			}
			throw new LdesClientException(e.getCause().getMessage(), e.getCause());
		}
	}

	/**
	 * Executes one synchronization run. Returns the number of members that were emitted.
	 *
	 * @param root  the root page, of which the first message has already been read
	 * @param first the first message of the root page, or null when the root page is empty
	 */
	long synchronize(FetchedPage root, RdfMessage first) {
		try {
			// 1. the root page
			open(root, null, true, first, true, null);

			while (!pages.isEmpty() || !relations.isEmpty()) {
				// 2. the source with the smallest lower key; an open page wins a tie
				OpenPage bestPage = null;
				Relation bestRelation = null;
				OrderingKey bestKey = null;
				boolean first$ = true;
				for (OpenPage page : pages) {
					if (first$ || OrderingKey.compareLower(page.next.key(), bestKey) < 0) {
						bestPage = page;
						bestKey = page.next.key();
						first$ = false;
					}
				}
				synchronized (this) {
					for (Relation relation : relations) {
						if (first$ || OrderingKey.compareLower(relation.lower(), bestKey) < 0) {
							bestPage = null;
							bestRelation = relation;
							bestKey = relation.lower();
							first$ = false;
						}
					}
				}

				if (bestPage != null) {
					// 3. an open page
					OpenPage page = bestPage;
					Next next = page.next;
					// The rest of the page is not wanted (any more): the order guarantees that its remaining keys are not smaller
					if (!relevant(next.key(), null)) {
						LOG.debug("Closing {}: the rest is not wanted by the conditions", page.url);
						pages.remove(page);
						page.log.close();
						continue;
					}
					// Only a member that is certainly before the bookmark, or that is a member of the bookmark, is skipped
					Comparison comparison = bookmark.key() == null ? new Comparison(1, true)
							: OrderingKey.compareCertain(next.key(), bookmark.key());
					boolean inBookmark = bookmark.members().contains(next.id().getURI());
					boolean skip = (comparison.certain() && comparison.order() < 0)
							|| (comparison.certain() && comparison.order() == 0 && (context.mode() == OrderMode.IMPLICIT || inBookmark))
							|| (!comparison.certain() && inBookmark);
					if (!skip) {
						Node id = next.id();
						OrderingKey key = next.key();
						Consumer<Bookmark> advance = target -> {
							if (!comparison.certain()) {
								// Neither earlier nor later for certain: the bookmark is the key with the latest earliest instant
								target.setKey(target.key() == null ? key : OrderingKey.latestEarliest(target.key(), key));
								target.members().add(id.getURI());
							} else if (comparison.order() > 0) {
								target.setKey(key);
								target.members().clear();
								target.members().add(id.getURI());
							} else {
								target.members().add(id.getURI());
							}
						};
						if (!emitter.emit(toMember(next, page.url), advance)) {
							return emitted;
						}
						emitted++;
						advance.accept(bookmark);
					}
					page.advance(null, true);
					if (page.next == null) {
						pages.remove(page);
						finished(page);
					}
				} else {
					// 4. an unopened relation
					Relation relation = bestRelation;
					synchronized (this) {
						relations.remove(relation);
					}
					if (!relevant(relation.lower(), relation.upper())) {
						LOG.debug("Skipping {}: not wanted by the conditions", relation.page());
						Future<Prefetched> pending = prefetched.remove(relation);
						if (pending != null) {
							closeLater(pending);
						}
						continue;
					}
					LOG.debug("Opening {}", relation.page());
					FetchedPage fetched;
					RdfMessage firstMessage = null;
					boolean firstKnown = false;
					if (prefetchWindow > 0) {
						Future<Prefetched> pending;
						synchronized (this) {
							pending = prefetched.containsKey(relation) ? prefetched.get(relation) : prefetchPage(relation);
							prefetchAhead();
						}
						try {
							Prefetched result = await(pending);
							fetched = result.page();
							firstMessage = result.first();
							firstKnown = true;
						} finally {
							synchronized (this) {
								prefetched.remove(relation);
							}
						}
					} else {
						fetched = openRelation(relation, BUFFERED_MESSAGES);
					}
					open(fetched, relation.pageNumber(), false, firstMessage, firstKnown, relation.page());
				}
			}
			// 5. no sources left: the run is finished. Remember the immutable pages that are still linked to.
			Set<String> immutable = new HashSet<>(bookmark.immutable());
			immutable.addAll(completed);
			bookmark.immutable().clear();
			for (String url : immutable) {
				if (referenced.contains(url)) {
					bookmark.immutable().add(url);
				}
			}
			bookmark.pages().clear();
			bookmark.pages().putAll(observed);
			return emitted;
		} finally {
			finishedRun = true;
			root.log().close();
			pages.forEach(page -> page.log.close());
			synchronized (this) {
				prefetched.values().forEach(this::closeLater);
				prefetched.clear();
			}
		}
	}

	private void closeLater(Future<Prefetched> pending) {
		executor.submit(() -> {
			try {
				pending.get().page().log().close();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (ExecutionException e) {
				// The page failed to load, and is not needed
			}
		});
	}
}
