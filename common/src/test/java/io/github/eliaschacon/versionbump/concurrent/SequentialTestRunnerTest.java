package io.github.eliaschacon.versionbump.concurrent;

class SequentialTestRunnerTest extends TaskRunnerContract {

	@Override
	protected TaskRunner runner() {
		return new SequentialTestRunner();
	}
}
