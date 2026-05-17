package org.slizaa.hierarchicalgraph.core.model.spi;

import org.slizaa.hierarchicalgraph.core.model.HGNode;

public interface IAutoExpandInterceptor {

    boolean preventAutoExpansion(HGNode node);
}
