package org.hg.fixture.locations.app.support;

/**
 * Non-participant: touches only the JDK (java.io.PrintStream via System.out),
 * never anything under lib. It must therefore be absent from the filtered
 * source tree of the app -> lib dependency cell, together with its whole
 * app.support package.
 */
public class LoggingHelper {

    public void log(String message) {
        System.out.println(message);
    }
}
