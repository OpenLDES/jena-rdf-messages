package org.openldes.jena.messages.writer;

import org.apache.jena.atlas.io.AWriter;
import org.apache.jena.atlas.lib.CharSpace;
import org.apache.jena.riot.writer.WriterStreamRDFPlain;

/** Jena's streaming N-Triples/N-Quads writer, plus the {@code VERSION} and {@code MESSAGE} lines. */
public final class WriterStreamRDFMessagesPlain extends WriterStreamRDFPlain {

	public WriterStreamRDFMessagesPlain(AWriter out, CharSpace charSpace) {
		super(out, charSpace);
	}

	@Override
	public void version(String version) {
		out.print("VERSION \"" + version + "\"\n");
	}

	/** Ends the current message. */
	public void delimiter() {
		out.print("MESSAGE\n");
		out.flush();
	}
}
