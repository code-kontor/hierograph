package org.hg.fixture.locations.lib.order.detail;

/**
 * Deep target (lib.order.detail, three packages below lib). Referenced from
 * the app side only by the equally deep NightlyReportJob -> exercises
 * deep-to-deep predecessor marking on both trees.
 */
public class OrderLine {

    private int amount;

    public int getAmount() {
        return amount;
    }
}
