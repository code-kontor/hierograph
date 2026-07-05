package org.hg.fixture.locations.lib.customer;

/**
 * High fan-in target: referenced by OrderController, CustomerController and
 * NightlyReportJob.
 */
public class Customer {

    private String name;

    public String getName() {
        return name;
    }
}
