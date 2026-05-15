package org.slizaa.hierarchicalgraph.core.selections.fwk;

import static org.slizaa.hierarchicalgraph.core.selections.fwk.SlizaaNodesAssert.assertThat;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.slizaa.hierarchicalgraph.core.selections.xref.XRefStack;

public class XRefStackProbeRule implements TestRule {

  /** - */
  private XRefStack _xRefStack;

  @Override
  public Statement apply(Statement base, Description description) {
    return new Statement() {

      @Override
      public void evaluate() throws Throwable {

        //
        _xRefStack = new XRefStack();

        //
        base.evaluate();
      }
    };
  }

  public XRefStack xRefStack() {
    return _xRefStack;
  }
  
  public void assertCenterNodes(int size, long... nodeIds) {
    assertThat(xRefStack().getCenterNodes()).hasSize(size);
    assertThat(xRefStack().getCenterNodes()).containsNodesWithId(nodeIds);
  }

  public void assertLeftsidedNodes(int size, long... nodeIds) {
    assertThat(xRefStack().getLeftsidedNodes()).hasSize(size);
    assertThat(xRefStack().getLeftsidedNodes()).containsNodesWithId(nodeIds);
  }

  public void assertRightsidedNodes(int size, long... nodeIds) {
    assertThat(xRefStack().getRightsidedNodes()).hasSize(size);
    assertThat(xRefStack().getRightsidedNodes()).containsNodesWithId(nodeIds);
  }
}
