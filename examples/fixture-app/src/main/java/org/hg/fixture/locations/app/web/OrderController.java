package org.hg.fixture.locations.app.web;

import org.hg.fixture.locations.lib.customer.Customer;
import org.hg.fixture.locations.lib.order.Order;
import org.hg.fixture.locations.lib.order.OrderService;

/**
 * Fan-out source: references three lib types across two lib sub-packages
 * (lib.order.Order, lib.order.OrderService, lib.customer.Customer).
 */
public class OrderController {

    private final OrderService service = new OrderService();
    private Order currentOrder;

    public Order create(Customer customer) {
        Order order = new Order();
        service.submit(order);
        this.currentOrder = order;
        return order;
    }
}
