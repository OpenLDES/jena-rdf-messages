package org.openldes.jena.messages.ldes;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * A bookmark store in a directory: one JSON file per LDES, written atomically. This is what {@code --save} of the
 * command line client uses.
 */
public final class FileBookmarkStore implements BookmarkStore {

	private final Path directory;

	public FileBookmarkStore(Path directory) {
		this.directory = directory;
	}

	private Path file(String ldes) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(ldes.getBytes(StandardCharsets.UTF_8));
			return directory.resolve(HexFormat.of().formatHex(hash, 0, 12) + ".json");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	@Override
	public Optional<Bookmark> load(String ldes) {
		Path path = file(ldes);
		if (!Files.exists(path)) {
			return Optional.empty();
		}
		try {
			return Optional.of(Bookmark.fromJson(Files.readString(path, StandardCharsets.UTF_8)));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@Override
	public void save(String ldes, Bookmark bookmark) {
		Path path = file(ldes);
		try {
			Files.createDirectories(directory);
			Path temporary = Files.createTempFile(directory, "bookmark", ".tmp");
			Files.writeString(temporary, bookmark.toJson().toString(), StandardCharsets.UTF_8);
			Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	@Override
	public void clear(String ldes) {
		try {
			Files.deleteIfExists(file(ldes));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
