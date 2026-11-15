package org.openldes.jena.messages;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.jena.atlas.web.MediaType;

/**
 * The version labels of RDF Messages, see <a href="https://w3c-cg.github.io/rsp/spec/messages#version-labels">section
 * 1.7 of the specification</a>: the labels of RDF 1.2 with the suffix {@code -messages}.
 * <p>
 * A version is announced in-line ({@code VERSION "1.2-messages"} or {@code @version "1.2-messages" .}), or externally
 * with the {@code version} parameter of the media type, for example {@code application/trig; version=1.2-messages}.
 */
public final class MessageVersions {

	/** The suffix that makes a version label announce an RDF Message log. */
	public static final String SUFFIX = "-messages";

	public static final String RDF12_MESSAGES = "1.2-messages";
	public static final String RDF12_BASIC_MESSAGES = "1.2-basic-messages";
	public static final String RDF11_MESSAGES = "1.1-messages";

	/** The version labels that the specification defines. Other labels with the suffix are accepted too. */
	public static final List<String> LABELS = List.of(RDF12_MESSAGES, RDF12_BASIC_MESSAGES, RDF11_MESSAGES);

	/** The default label that the writers announce. */
	public static final String DEFAULT = RDF12_MESSAGES;

	private MessageVersions() {
	}

	/** Whether a version label announces an RDF Message log. */
	public static boolean announcesMessages(String label) {
		return label != null && label.endsWith(SUFFIX) && label.length() > SUFFIX.length();
	}

	/** The {@code version} parameter of a media type: name case-insensitive, value optionally quoted. */
	private static final Pattern VERSION_PARAMETER = Pattern.compile(";\\s*version\\s*=\\s*(?:\"([^\"]*)\"|([^;\\s]+))",
			Pattern.CASE_INSENSITIVE);

	/** The version label of a media type, if it has a {@code version} parameter: {@code text/turtle; version=1.2-messages}. */
	public static Optional<String> versionOf(String mediaType) {
		if (mediaType == null || mediaType.isBlank()) {
			return Optional.empty();
		}
		Matcher matcher = VERSION_PARAMETER.matcher(mediaType);
		if (!matcher.find()) {
			return Optional.empty();
		}
		return Optional.of(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
	}

	/** Whether a media type announces an RDF Message log with its {@code version} parameter. */
	public static boolean announcesMessages(MediaType mediaType) {
		return mediaType != null && versionOf(mediaType.toHeaderString()).filter(MessageVersions::announcesMessages).isPresent();
	}
}
