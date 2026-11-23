package org.hg.fixture.uncompiled;

/**
 * A test source that `mvn compile` never compiles: it makes the fixture-app's test output a declared output with
 * sources but no classes, which the server reports as "not compiled yet" (the start screen's warning).
 */
public class UncompiledTest {
}
