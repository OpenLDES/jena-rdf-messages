package org.openldes.jena.messages.ldes;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.openldes.jena.messages.RdfMessage;
import org.openldes.jena.messages.ldes.Bookmark.CachedPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An <a href="https://w3id.org/ldes/specification">LDES 2.0</a> client: replicates the members of an event stream in
 * ascending order of their ordering key, and, when following, stays synchronized as new members are published.
 *
 * <pre>{@code
 * try (LdesClient client = LdesClient.create("https://example.org/ldes").bookmarks(new FileBookmarkStore(dir)).build()) {
 *     for (LdesMember member : client) {
 *         ...
 *     }
 * }
 * }</pre>
 *
 * The client reads pages in a background thread and hands members over through a bounded queue, so a slow consumer
 * slows down the client. A member counts as emitted when the consumer has taken it, not when it was put in the queue:
 * the saved bookmark never skips a member that the consumer did not see. Every synchronization run is reported to the
 * {@link LdesListener}.
 */
public final class LdesClient implements AutoCloseable, Iterable<LdesMember> {

	private static final Logger LOG = LoggerFactory.getLogger(LdesClient.class);

	public static Builder create(String url) {
		return new Builder(url);
	}

	/** The configuration of a client. */
	public static final class Builder {
		private final String url;
		private boolean follow = false;
		private Duration pollInterval = Duration.ofSeconds(1);
		private Instant after;
		private Instant before;
		private ZoneOffset defaultTimezone;
		private BookmarkStore bookmarks = new MemoryBookmarkStore();
		private boolean startFresh = false;
		private int concurrentFetches = 10;
		private PageFetcher fetcher;
		private int highWaterMark = 64;
		private LdesListener listener = new LdesListener() {
		};

		private Builder(String url) {
			this.url = Objects.requireNonNull(url, "url");
		}

		/** Keep polling for new members after catching up. The default is to stop when all members are emitted. */
		public Builder follow(boolean follow) {
			this.follow = follow;
			return this;
		}

		/** The time between runs when following; an {@code ldes:pollingInterval} of the LDES takes precedence. */
		public Builder pollInterval(Duration pollInterval) {
			this.pollInterval = pollInterval;
			return this;
		}

		/** Only members with a timestamp after this instant (exclusive). Relations that end before it are not followed. */
		public Builder after(Instant after) {
			this.after = after;
			return this;
		}

		/** Only members with a timestamp before this instant (exclusive). */
		public Builder before(Instant before) {
			this.before = before;
			return this;
		}

		/** The timezone of timestamps without one; an {@code ldes:defaultTimezone} of the LDES takes precedence. */
		public Builder defaultTimezone(ZoneOffset defaultTimezone) {
			this.defaultTimezone = defaultTimezone;
			return this;
		}

		/** Where the bookmark is kept between runs. The default keeps it in memory, for the life of the client. */
		public Builder bookmarks(BookmarkStore bookmarks) {
			this.bookmarks = bookmarks;
			return this;
		}

		/** Discard the saved bookmark and read everything again. */
		public Builder startFresh(boolean startFresh) {
			this.startFresh = startFresh;
			return this;
		}

		/** How many pages are requested at the same time (the prefetch window). 1 reads pages one by one. */
		public Builder concurrentFetches(int concurrentFetches) {
			this.concurrentFetches = concurrentFetches;
			return this;
		}

		/** How pages are fetched; the default is {@link HttpPageFetcher}. */
		public Builder fetcher(PageFetcher fetcher) {
			this.fetcher = fetcher;
			return this;
		}

		/** How many members can wait for the consumer before the client pauses. */
		public Builder highWaterMark(int highWaterMark) {
			this.highWaterMark = highWaterMark;
			return this;
		}

		public Builder listener(LdesListener listener) {
			this.listener = Objects.requireNonNull(listener);
			return this;
		}

		/** Builds the client; it starts reading the root page right away. */
		public LdesClient build() {
			return new LdesClient(this);
		}
	}

	private static final Object END = new Object();

	private record Failure(Throwable cause) {
	}

	private record Emitted(LdesMember member, Consumer<Bookmark> advance) {
	}

	private final String url;
	private final boolean follow;
	private final Duration pollInterval;
	private final Instant after;
	private final Instant before;
	private final ZoneOffset configuredTimezone;
	private final BookmarkStore bookmarks;
	private final int concurrentFetches;
	private final PageFetcher fetcher;
	private final LdesListener listener;
	private final BlockingQueue<Object> queue;
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
	private final Thread runner;
	private final CountDownLatch contextRead = new CountDownLatch(1);
	private final ReentrantLock lock = new ReentrantLock();
	private final Condition drained = lock.newCondition();
	private final Bookmark saved;
	private volatile LdesContext context;
	private volatile Throwable contextFailure;
	private volatile boolean closed;
	private long emitted;
	private long taken;
	private Object pending;
	private boolean finished;

	private LdesClient(Builder builder) {
		this.url = builder.url;
		this.follow = builder.follow;
		this.pollInterval = builder.pollInterval;
		this.after = builder.after;
		this.before = builder.before;
		this.configuredTimezone = builder.defaultTimezone;
		this.bookmarks = builder.bookmarks;
		this.concurrentFetches = builder.concurrentFetches;
		this.fetcher = builder.fetcher != null ? builder.fetcher : new HttpPageFetcher();
		this.listener = builder.listener;
		this.queue = new ArrayBlockingQueue<>(Math.max(1, builder.highWaterMark));
		if (builder.startFresh) {
			bookmarks.clear(url);
		}
		this.saved = bookmarks.load(url).orElseGet(Bookmark::new);
		this.runner = Thread.ofVirtual().name("ldes-client").unstarted(this::run);
		this.runner.start();
	}

	/** The context information of the LDES, read from the root page. Blocks until it is read; fails as the client would. */
	public LdesContext context() {
		try {
			contextRead.await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new LdesClientException("Interrupted while reading the root page", e);
		}
		if (context == null) {
			throw failure(contextFailure);
		}
		return context;
	}

	private static RuntimeException failure(Throwable cause) {
		if (cause instanceof RuntimeException runtime) {
			return runtime;
		}
		if (cause instanceof Error error) {
			throw error;
		}
		return new LdesClientException(String.valueOf(cause), cause);
	}

	// ---- The run loop

	private void run() {
		try {
			FetchedPage root = fetcher.fetch(url, null, Traversal.BUFFERED_MESSAGES);
			RdfMessage first = root.log().nextOrNull();
			detectVersion(root, first);
			context = LdesContext.read(first, root.url(), configuredTimezone);
			listener.onContext(context);
			contextRead.countDown();
			Duration interval = context.pollingInterval().orElse(pollInterval);
			Traversal.Relevance relevance = relevance();

			while (!closed) {
				Bookmark followed = saved.copy();
				Traversal traversal = new Traversal(context, followed, fetcher, concurrentFetches, relevance, this::emit, listener,
						executor);
				long count = traversal.synchronize(root, first);
				awaitDrained();
				if (!closed) {
					synchronized (saved) {
						saved.immutable().clear();
						saved.immutable().addAll(followed.immutable());
						saved.pages().clear();
						saved.pages().putAll(followed.pages());
					}
				}
				persist();
				LOG.debug("Synchronization run finished: {} new members", count);
				listener.onRunFinished(count);
				if (!follow || closed) {
					break;
				}
				Thread.sleep(interval.toMillis());
				if (closed) {
					break;
				}
				// Every later run starts again from the root page, requested with the ETag it had when it was read completely
				CachedPage cached;
				synchronized (saved) {
					cached = saved.pages().get(context.rootPage());
				}
				FetchedPage answer = fetcher.fetch(context.rootPage(), cached == null ? null : cached.etag(),
						Traversal.BUFFERED_MESSAGES);
				root = answer.status() == 304 && cached != null ? Traversal.cachedPage(context.rootPage(), answer, cached) : answer;
				first = root.log().nextOrNull();
			}
			offer(END);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			offer(END);
		} catch (Throwable t) {
			if (context == null) {
				contextFailure = t;
				contextRead.countDown();
			}
			persist();
			offer(new Failure(t));
		} finally {
			executor.shutdown();
		}
	}

	/**
	 * Decides whether the page is an LDES 2.0 page, following
	 * <a href="https://w3id.org/ldes/specification#compatibility">the compatibility rules</a>. This client only reads
	 * LDES 2.0: an LDES 1.0 page is an error.
	 */
	private static void detectVersion(FetchedPage root, RdfMessage first) {
		if (root.framed() || first == null) {
			return;
		}
		boolean ldes2 = first.stream().anyMatch(quad -> {
			for (var predicate : LdesVocab.LDES2_PREDICATES) {
				if (predicate.equals(quad.getPredicate())) {
					return true;
				}
			}
			return false;
		});
		if (!ldes2 && PageDescription.looksLikeLdes1(first)) {
			throw new LdesClientException(root.url() + " is an LDES 1.0 (TREE) page. This client reads LDES 2.0 only;"
					+ " use an LDES 1.0 client such as the one of Linked Data Interactions");
		}
	}

	/** The time window of the client, as a test on the intervals of relations and pages. Null when there is no window. */
	private Traversal.Relevance relevance() {
		if (after == null && before == null) {
			return null;
		}
		return (lower, upper) -> {
			// Only certain comparisons prune: a bound without a timezone is never a reason to skip
			if (after != null && upper != null && upper.time() != null && !upper.local() && !upper.time().isAfter(after)) {
				return false;
			}
			return !(before != null && lower != null && lower.time() != null && !lower.local() && !lower.time().isBefore(before));
		};
	}

	private boolean inWindow(LdesMember member) {
		Optional<Instant> timestamp = member.timestamp();
		if (timestamp.isEmpty()) {
			return true;
		}
		if (after != null && !timestamp.get().isAfter(after)) {
			return false;
		}
		return before == null || timestamp.get().isBefore(before);
	}

	private boolean emit(LdesMember member, Consumer<Bookmark> advance) {
		if (closed) {
			return false;
		}
		if (!inWindow(member)) {
			listener.onMemberSkipped(member);
			return true;
		}
		lock.lock();
		try {
			emitted++;
		} finally {
			lock.unlock();
		}
		return offer(new Emitted(member, advance));
	}

	private boolean offer(Object element) {
		try {
			while (!closed) {
				if (queue.offer(element, 200, TimeUnit.MILLISECONDS)) {
					return true;
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		return false;
	}

	private void awaitDrained() throws InterruptedException {
		lock.lock();
		try {
			while (!closed && taken < emitted) {
				drained.await(200, TimeUnit.MILLISECONDS);
			}
		} finally {
			lock.unlock();
		}
	}

	private void persist() {
		try {
			synchronized (saved) {
				bookmarks.save(url, saved);
			}
		} catch (RuntimeException e) {
			if (closed) {
				LOG.debug("Could not save the bookmark after the client was closed: {}", e.toString());
			} else {
				LOG.error("Could not save the bookmark: {}", e.toString());
			}
		}
	}

	// ---- The consumer side

	@Override
	public Iterator<LdesMember> iterator() {
		return new Iterator<>() {
			@Override
			public boolean hasNext() {
				return LdesClient.this.hasNext();
			}

			@Override
			public LdesMember next() {
				return LdesClient.this.next();
			}
		};
	}

	/** Whether another member is available; blocks until one is, or the client is finished. Throws when the client failed. */
	public boolean hasNext() {
		if (pending != null) {
			return true;
		}
		if (finished || closed) {
			return false;
		}
		Object element;
		try {
			element = queue.take();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			close();
			throw new LdesClientException("Interrupted while waiting for members", e);
		}
		if (element == END) {
			finished = true;
			return false;
		}
		if (element instanceof Failure failure) {
			finished = true;
			throw failure(failure.cause());
		}
		pending = element;
		return true;
	}

	/** The next member. Taking it advances the saved bookmark. */
	public LdesMember next() {
		if (!hasNext()) {
			throw new NoSuchElementException();
		}
		Emitted emitted$ = (Emitted) pending;
		pending = null;
		synchronized (saved) {
			emitted$.advance().accept(saved);
		}
		lock.lock();
		try {
			taken++;
			drained.signalAll();
		} finally {
			lock.unlock();
		}
		return emitted$.member();
	}

	/** The members as a stream. Closing the stream closes the client. */
	public Stream<LdesMember> stream() {
		return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator(), Spliterator.ORDERED), false)
				.onClose(this::close);
	}

	@Override
	public void forEach(Consumer<? super LdesMember> action) {
		while (hasNext()) {
			action.accept(next());
		}
	}

	/** Stops the client: no more pages are read, and the bookmark of what the consumer has taken is saved. */
	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		runner.interrupt();
		queue.clear();
		executor.shutdownNow();
		persist();
	}

	/** The bookmark as it will be saved: what the consumer has taken so far. */
	public Bookmark bookmark() {
		synchronized (saved) {
			return saved.copy();
		}
	}

	static {
		Objects.requireNonNull(END);
	}
}
