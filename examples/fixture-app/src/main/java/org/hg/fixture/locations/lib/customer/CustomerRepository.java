package org.hg.fixture.locations.lib.customer;

/**
 * Interface referenced by CustomerController via a field type + method call.
 */
public interface CustomerRepository {

    Customer load(long id);
}
