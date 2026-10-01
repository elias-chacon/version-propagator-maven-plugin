package io.github.eliaschacon.versionbump.hook;

import org.apache.maven.plugin.logging.Log;
import org.slf4j.Logger;

import lombok.RequiredArgsConstructor;

/**
 * Maven plugin {@link Log} on top of SLF4J, the logging API Maven gives to core extensions and lifecycle
 * participants. Messages get a prefix so they can be told apart from the goal that triggered the hook.
 */
@RequiredArgsConstructor
final class Slf4jLog implements Log {

	static final String PREFIX = "[version-propagator] ";

	private final Logger logger;

	@Override
	public boolean isDebugEnabled() {
		return logger.isDebugEnabled();
	}

	@Override
	public void debug(CharSequence content) {
		logger.debug("{}{}", PREFIX, content);
	}

	@Override
	public void debug(CharSequence content, Throwable error) {
		logger.debug(PREFIX + "{}", content, error);
	}

	@Override
	public void debug(Throwable error) {
		logger.debug(PREFIX, error);
	}

	@Override
	public boolean isInfoEnabled() {
		return logger.isInfoEnabled();
	}

	@Override
	public void info(CharSequence content) {
		logger.info("{}{}", PREFIX, content);
	}

	@Override
	public void info(CharSequence content, Throwable error) {
		logger.info(PREFIX + "{}", content, error);
	}

	@Override
	public void info(Throwable error) {
		logger.info(PREFIX, error);
	}

	@Override
	public boolean isWarnEnabled() {
		return logger.isWarnEnabled();
	}

	@Override
	public void warn(CharSequence content) {
		logger.warn("{}{}", PREFIX, content);
	}

	@Override
	public void warn(CharSequence content, Throwable error) {
		logger.warn(PREFIX + "{}", content, error);
	}

	@Override
	public void warn(Throwable error) {
		logger.warn(PREFIX, error);
	}

	@Override
	public boolean isErrorEnabled() {
		return logger.isErrorEnabled();
	}

	@Override
	public void error(CharSequence content) {
		logger.error("{}{}", PREFIX, content);
	}

	@Override
	public void error(CharSequence content, Throwable error) {
		logger.error(PREFIX + "{}", content, error);
	}

	@Override
	public void error(Throwable error) {
		logger.error(PREFIX, error);
	}
}
