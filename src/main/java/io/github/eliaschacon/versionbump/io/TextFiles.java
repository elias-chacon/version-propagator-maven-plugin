package io.github.eliaschacon.versionbump.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Strict text encoding helpers. Decoding and encoding never replace invalid characters silently,
 * so a file is either round-tripped exactly or rejected.
 */
public final class TextFiles {

	/**
	 * Number of leading bytes inspected to detect binary content.
	 */
	static final int BINARY_PROBE_SIZE = 8192;

	private TextFiles() {
	}

	public static byte[] read(Path path) throws IOException {
		return Files.readAllBytes(path);
	}

	/**
	 * Heuristic used by Git and most editors: a NUL byte in the first bytes means binary content.
	 * UTF-16/UTF-32 text also contains NUL bytes, therefore the check is skipped for those charsets.
	 */
	public static boolean looksBinary(byte[] bytes, Charset charset) {
		String name = charset.name().toUpperCase(Locale.ROOT);
		if (name.startsWith("UTF-16") || name.startsWith("UTF-32")) {
			return false;
		}
		int limit = Math.min(bytes.length, BINARY_PROBE_SIZE);
		for (int i = 0; i < limit; i++) {
			if (bytes[i] == 0) {
				return true;
			}
		}
		return false;
	}

	public static String decode(byte[] bytes, Charset charset) throws CharacterCodingException {
		return charset.newDecoder()
			.onMalformedInput(CodingErrorAction.REPORT)
			.onUnmappableCharacter(CodingErrorAction.REPORT)
			.decode(ByteBuffer.wrap(bytes))
			.toString();
	}

	public static byte[] encode(String text, Charset charset) throws CharacterCodingException {
		ByteBuffer buffer = charset.newEncoder()
			.onMalformedInput(CodingErrorAction.REPORT)
			.onUnmappableCharacter(CodingErrorAction.REPORT)
			.encode(CharBuffer.wrap(text));
		byte[] out = new byte[buffer.remaining()];
		buffer.get(out);
		return out;
	}
}
