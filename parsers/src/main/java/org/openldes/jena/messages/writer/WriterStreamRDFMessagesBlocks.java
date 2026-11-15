package org.openldes.jena.messages.writer;

import java.io.OutputStream;

import org.apache.jena.atlas.io.IndentedWriter;
import org.apache.jena.riot.writer.DirectiveStyle;
import org.apache.jena.riot.writer.WriterStreamRDFBlocks;
import org.apache.jena.sparql.util.Context;

/**
 * Jena's streaming Turtle/TriG writer (prefixes and subject blocks, graph blocks in TriG), plus a message delimiter:
 * the open subject block and graph block are closed before the delimiter is written, so that the next message starts
 * from a clean state.
 */
public final class WriterStreamRDFMessagesBlocks extends WriterStreamRDFBlocks {

	public WriterStreamRDFMessagesBlocks(OutputStream output, Context context) {
		super(output, context);
	}

	public WriterStreamRDFMessagesBlocks(IndentedWriter output, Context context) {
		super(output, context);
	}

	/** Ends the current message: flushes the pending block and graph, and writes the delimiter. */
	public void delimiter(DirectiveStyle style) {
		endData();
		reset();
		if (!out.atLineStart()) {
			out.println();
		}
		out.println(style == DirectiveStyle.AT ? "@message ." : "MESSAGE");
		out.flush();
	}
}
