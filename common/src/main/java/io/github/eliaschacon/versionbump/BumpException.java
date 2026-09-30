package io.github.eliaschacon.versionbump;

/**
 * Domain failure raised when a bump cannot be performed safely (invalid version, invalid
 * configuration, unsupported POM layout, I/O failure...). The Mojo translates it into a
 * Maven failure so that the build stops with the message shown to the user.
 */
public class BumpException extends Exception {

	private static final long serialVersionUID = 1L;

	public BumpException(String message) {
		super(message);
	}

	public BumpException(String message, Throwable cause) {
		super(message, cause);
	}
}
