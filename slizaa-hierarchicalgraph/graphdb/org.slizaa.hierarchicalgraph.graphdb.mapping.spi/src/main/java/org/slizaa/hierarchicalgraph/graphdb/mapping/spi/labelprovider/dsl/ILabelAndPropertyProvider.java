package org.slizaa.hierarchicalgraph.graphdb.mapping.spi.labelprovider.dsl;

import java.util.Collection;
import java.util.Map;

public interface ILabelAndPropertyProvider {

  Map<String, String> getProperties();

  Collection<String> getLabels();
}
