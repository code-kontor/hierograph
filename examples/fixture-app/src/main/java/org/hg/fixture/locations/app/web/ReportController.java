package org.hg.fixture.locations.app.web;

import org.hg.fixture.locations.lib.audit.Auditable;
import org.hg.fixture.locations.lib.order.Order;
import org.hg.fixture.locations.lib.report.ReportFormat;

/**
 * Source annotated by a lib annotation (ANNOTATED_BY -> lib.audit.Auditable)
 * that also references lib.report.ReportFormat (enum field) and
 * lib.order.Order (method parameter).
 */
@Auditable
public class ReportController {

    private ReportFormat format = ReportFormat.PDF;

    public String render(Order order) {
        return format + ":" + order.getId();
    }
}
