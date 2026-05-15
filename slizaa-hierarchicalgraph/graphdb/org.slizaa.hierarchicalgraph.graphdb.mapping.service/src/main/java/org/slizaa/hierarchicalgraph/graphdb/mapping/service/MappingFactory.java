package org.slizaa.hierarchicalgraph.graphdb.mapping.service;

import org.eclipse.emf.ecore.EFactory;
import org.eclipse.emf.ecore.EPackage;
import org.slizaa.hierarchicalgraph.core.model.HierarchicalgraphPackage;
import org.slizaa.hierarchicalgraph.core.model.impl.CustomHierarchicalgraphFactoryImpl;
import org.slizaa.hierarchicalgraph.graphdb.mapping.service.internal.DefaultMappingService;
import org.slizaa.hierarchicalgraph.graphdb.model.GraphDbHierarchicalgraphPackage;
import org.slizaa.hierarchicalgraph.graphdb.model.impl.CustomGraphDbHierarchicalgraphFactoryImpl;

/**
 * <p>
 * </p>
 *
 * @author Gerd W&uuml;therich (gerd@gerd-wuetherich.de)
 */
public class MappingFactory {

  /**
   * <p>
   * </p>
   *
   * @return
   */
  /**
   * <p>
   * </p>
   *
   * @return
   */
  public static IMappingService createMappingServiceForStandaloneSetup() {
    
    //
    configureModel();
    
    //
    return new DefaultMappingService();
  }
  
  /**
   * <p>
   * </p>
   */
  private static void configureModel() {
    
    EPackage.Registry.INSTANCE.put(HierarchicalgraphPackage.eNS_URI, new EPackage.Descriptor() {
      @Override
      public EPackage getEPackage() {
        return HierarchicalgraphPackage.eINSTANCE;
      }

      @Override
      public EFactory getEFactory() {
        return new CustomHierarchicalgraphFactoryImpl();
      }
    });

    EPackage.Registry.INSTANCE.put(GraphDbHierarchicalgraphPackage.eNS_URI, new EPackage.Descriptor() {
      @Override
      public EPackage getEPackage() {
        return GraphDbHierarchicalgraphPackage.eINSTANCE;
      }

      @Override
      public EFactory getEFactory() {
        return new CustomGraphDbHierarchicalgraphFactoryImpl();
      }
    });    
  }
}
