package org.hg.fixture.locations.app.batch.nightly;

import org.hg.fixture.locations.lib.customer.Customer;
import org.hg.fixture.locations.lib.order.Order;
import org.hg.fixture.locations.lib.order.detail.OrderLine;
import org.hg.fixture.locations.lib.report.AbstractReport;

/**
 * Deepest source (three packages down) with the widest fan-out: EXTENDS
 * lib.report.AbstractReport and references lib.order.Order,
 * lib.customer.Customer and the deep lib.order.detail.OrderLine.
 */
public class NightlyReportJob extends AbstractReport {

    private Order order;
    private Customer customer;

    public void addLine(OrderLine line) {
        record(line.getAmount());
    }
}
