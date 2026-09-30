package io.github.eliaschacon.versionbump.files;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.github.eliaschacon.versionbump.BumpException;
import io.github.eliaschacon.versionbump.io.FileChange;
import io.github.eliaschacon.versionbump.io.TextFiles;
import lombok.Builder;
import lombok.Data;

/**
 * Computes (without writing) the result of applying the replacement rules to one file.
 * Only rules whose scope contains the file are applied, in order, each on the output of the previous one.
 */
public final class FileReplacer {

	private final List<ReplacementRule.Compiled> rules;
	private final Charset charset;
	private final String description;
	public FileReplacer(List<ReplacementRule.Compiled> rules, Charset charset, String description) {
		this.rules = new ArrayList<>(rules);
		this.charset = charset;
		this.description = description;
	}

	public Outcome process(Path file) throws BumpException {
		List<ReplacementRule.Compiled> applicable = new ArrayList<>();
		for (ReplacementRule.Compiled rule : rules) {
			if (rule.appliesTo(file)) {
				applicable.add(rule);
			}
		}
		if (applicable.isEmpty()) {
			return Outcome.of(file, Status.NO_RULE);
		}

		byte[] bytes;
		try {
			bytes = TextFiles.read(file);
		} catch (IOException e) {
			throw new BumpException("Cannot read " + file + ": " + e, e);
		}
		if (TextFiles.looksBinary(bytes, charset)) {
			return Outcome.of(file, Status.SKIPPED_BINARY);
		}
		String text;
		try {
			text = TextFiles.decode(bytes, charset);
		} catch (CharacterCodingException e) {
			return Outcome.of(file, Status.SKIPPED_UNDECODABLE);
		}

		int total = 0;
		String original = text;
		for (ReplacementRule.Compiled rule : applicable) {
			ReplacementRule.Result result = rule.apply(text);
			text = result.getText();
			total += result.getCount();
		}
		if (total == 0) {
			return Outcome.of(file, Status.NO_MATCH);
		}
		if (text.equals(original)) {
			// Matches were found but replacing them produced identical content: nothing to write.
			return Outcome.builder().path(file).status(Status.UNCHANGED).replacements(total).build();
		}
		try {
			FileChange change = FileChange.builder()
				.path(file)
				.originalBytes(bytes)
				.newBytes(TextFiles.encode(text, charset))
				.replacements(total)
				.description(description)
				.build();
			return Outcome.builder().path(file).status(Status.CHANGED).replacements(total).change(change).build();
		} catch (CharacterCodingException e) {
			throw new BumpException("Cannot encode the new content of " + file + " using " + charset
				+ ". Check the replacement rules.", e);
		}
	}

	public enum Status {
		CHANGED, UNCHANGED, NO_MATCH, NO_RULE, SKIPPED_BINARY, SKIPPED_UNDECODABLE
	}

	/**
	 * Result for one file; {@link #getChange()} is only set for {@link Status#CHANGED}.
	 */
	@Data
	@Builder
	public static final class Outcome {
		private final Path path;
		private final Status status;
		private final int replacements;
		private final FileChange change;

		static Outcome of(Path path, Status status) {
			return builder().path(path).status(status).build();
		}
	}
}
