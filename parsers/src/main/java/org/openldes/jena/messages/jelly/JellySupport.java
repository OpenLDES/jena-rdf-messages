package org.openldes.jena.messages.jelly;

import java.io.OutputStream;

import org.apache.jena.riot.Lang;
import org.openldes.jena.messages.MessageReader;
import org.openldes.jena.messages.StreamRDFMessages;

/**
 * Jelly-RDF support, available when {@code eu.neverblink.jelly:jelly-jena} is on the classpath. The Jelly classes are
 * only touched after {@link #isAvailable()} says so, which keeps the dependency optional.
 */
public final class JellySupport {

	private static final boolean AVAILABLE = detect();

	private JellySupport() {
	}

	private static boolean detect() {
		try {
			Class.forName("eu.neverblink.jelly.convert.jena.riot.JellyLanguage", false, JellySupport.class.getClassLoader());
			return true;
		} catch (ClassNotFoundException | LinkageError e) {
			return false;
		}
	}

	public static boolean isAvailable() {
		return AVAILABLE;
	}

	/** The Jelly {@link Lang}, registered with Jena by jelly-jena. */
	public static Lang lang() {
		return JellyMessages.lang();
	}

	public static MessageReader reader() {
		return new JellyMessages.Reader();
	}

	public static StreamRDFMessages writer(OutputStream out, boolean framePerMessage) {
		return new JellyMessages.Writer(out, framePerMessage);
	}
}
