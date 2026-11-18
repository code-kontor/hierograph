package org.hg.fixture.basic.rel.source;

import org.hg.fixture.basic.rel.target.TargetB;

public class MethodInvoker {
    public void invoke() {
        TargetB.ping();
    }
}
