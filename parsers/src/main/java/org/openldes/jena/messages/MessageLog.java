package org.openldes.jena.messages;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.apache.jena.riot.RiotException;

/**
 * An RDF Message log that is read message by message, as a pull-based {@link Iterator}: the log of a page of an LDES, for
 * example, of which a client reads the next message when the merge of pages needs it.
 * <p>
 * The Jena parsers push their output; a log runs the parser in a (virtual) thread and hands the messages over through a
 * bounded queue, so that a log is never read further ahead than {@code bufferedMessages} messages. A log that is not
 * read to the end must be {@link #close() closed} to release the parser and its input.
 * <p>
 * A failure of the parser is thrown by {@link #hasNext()} or {@link #next()} as a {@link RiotException} (or the original
 * runtime exception), after the messages that were read before it.
 */
public final class MessageLog implements Iterator<RdfMessage>, Iterable<RdfMessage>, AutoCloseable {

	private static final Object END = new Object();

	private record Failure(Throwable cause) {
	}

	private final BlockingQueue<Object> queue;
	private final Thread producer;
	private final Runnable closeAction;
	private Object pending;
	private boolean finished;
	private volatile boolean closed;

	private MessageLog(BlockingQueue<Object> queue, Thread producer, Runnable closeAction) {
		this.queue = queue;
		this.producer = producer;
		this.closeAction = closeAction;
	}

	/**
	 * Starts reading a log in a virtual thread.
	 *
	 * @param bufferedMessages how many messages are read ahead at most
	 * @param parse            runs the parser with the given sink, in the parsing thread
	 * @param closeAction      called once, when the log is closed or finished, to release the input
	 */
	public static MessageLog start(int bufferedMessages, Consumer<StreamRDFMessages> parse, Runnable closeAction) {
		BlockingQueue<Object> queue = new ArrayBlockingQueue<>(Math.max(1, bufferedMessages));
		Thread[] holder = new Thread[1];
		Runnable task = () -> {
			try {
				parse.accept(new MessageCollector(message -> {
					try {
						queue.put(message);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						throw new RiotException("Reading the message log was interrupted", e);
					}
				}));
				offer(queue, END);
			} catch (Throwable t) {
				if (!(t instanceof RiotException && Thread.currentThread().isInterrupted())) {
					offer(queue, new Failure(t));
				}
			}
		};
		holder[0] = Thread.ofVirtual().name("rdf-messages-parser").unstarted(task);
		MessageLog log = new MessageLog(queue, holder[0], closeAction);
		holder[0].start();
		return log;
	}

	/** A log of messages that are already in memory, for example of a page that has not changed. */
	public static MessageLog of(List<RdfMessage> messages) {
		BlockingQueue<Object> queue = new ArrayBlockingQueue<>(messages.size() + 1);
		queue.addAll(messages);
		queue.add(END);
		return new MessageLog(queue, null, () -> {
		});
	}

	/** A log without messages. */
	public static MessageLog empty() {
		return of(List.of());
	}

	private static void offer(BlockingQueue<Object> queue, Object element) {
		try {
			// A consumer that is gone does not take the element: give up after a while instead of blocking forever
			while (!queue.offer(element, 1, TimeUnit.SECONDS)) {
				if (Thread.currentThread().isInterrupted()) {
					return;
				}
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@Override
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
			throw new RiotException("Reading the message log was interrupted", e);
		}
		if (element == END) {
			finished = true;
			release();
			return false;
		}
		if (element instanceof Failure failure) {
			finished = true;
			release();
			if (failure.cause() instanceof RuntimeException runtime) {
				throw runtime;
			}
			if (failure.cause() instanceof Error error) {
				throw error;
			}
			throw new RiotException(failure.cause());
		}
		pending = element;
		return true;
	}

	@Override
	public RdfMessage next() {
		if (!hasNext()) {
			throw new NoSuchElementException();
		}
		RdfMessage message = (RdfMessage) pending;
		pending = null;
		return message;
	}

	/** The next message, or null at the end of the log. */
	public RdfMessage nextOrNull() {
		return hasNext() ? next() : null;
	}

	@Override
	public Iterator<RdfMessage> iterator() {
		return this;
	}

	/** The remaining messages as a stream; closing the stream closes the log. */
	public Stream<RdfMessage> stream() {
		return StreamSupport.stream(Spliterators.spliteratorUnknownSize(this, Spliterator.ORDERED), false)
				.onClose(this::close);
	}

	/** Stops reading, and releases the parser and its input. Reading further gives no more messages. */
	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		pending = null;
		if (producer != null) {
			producer.interrupt();
		}
		queue.clear();
		release();
	}

	private boolean released;

	private void release() {
		if (!released) {
			released = true;
			closeAction.run();
		}
	}

	/** Reads all remaining messages into a list. */
	public List<RdfMessage> toList() {
		List<RdfMessage> result = new java.util.ArrayList<>();
		forEachRemaining(result::add);
		return result;
	}

	@Override
	public String toString() {
		return "MessageLog" + (closed ? " (closed)" : finished ? " (finished)" : "");
	}

	static {
		Objects.requireNonNull(END);
	}
}
