package org.hg.fixture.locations.lib.report;

/**
 * Abstract base extended by NightlyReportJob (EXTENDS + implicit super()
 * INVOKES; the inherited record() call adds another INVOKES edge).
 */
public abstract class AbstractReport {

    protected void record(int value) {
        // no-op
    }
}
