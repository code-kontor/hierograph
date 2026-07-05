package org.hg.fixture.locations.lib.order;

/**
 * High fan-in target: referenced by OrderController, ReportController and
 * NightlyReportJob (three sources across two source sub-packages).
 */
public class Order {

    private long id;

    public long getId() {
        return id;
    }
}
