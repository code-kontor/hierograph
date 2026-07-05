package org.hg.fixture.locations.lib.order;

import org.hg.fixture.locations.lib.order.detail.OrderLine;

/**
 * Referenced only by OrderController (single-source fan-in). Also carries
 * intra-lib.order edges (-> Order, -> OrderLine) so the lib.order subtree has
 * its own non-trivial DSM cells for deeper exploration.
 */
public class OrderService {

    public void submit(Order order) {
        // no-op
    }

    public OrderLine lineFor(Order order) {
        return new OrderLine();
    }
}
