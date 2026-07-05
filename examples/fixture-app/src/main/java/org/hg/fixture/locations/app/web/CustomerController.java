package org.hg.fixture.locations.app.web;

import org.hg.fixture.locations.lib.customer.Customer;
import org.hg.fixture.locations.lib.customer.CustomerApi;
import org.hg.fixture.locations.lib.customer.CustomerRepository;

/**
 * Source that reaches lib.customer via three different edge kinds:
 * IMPLEMENTS (CustomerApi), field OF_TYPE (CustomerRepository) and a
 * method return type (Customer).
 */
public class CustomerController implements CustomerApi {

    private final CustomerRepository repository;

    public CustomerController(CustomerRepository repository) {
        this.repository = repository;
    }

    @Override
    public Customer findById(long id) {
        return repository.load(id);
    }
}
