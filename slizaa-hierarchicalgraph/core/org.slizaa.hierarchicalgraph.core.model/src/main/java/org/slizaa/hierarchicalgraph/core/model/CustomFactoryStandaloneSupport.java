package org.slizaa.hierarchicalgraph.core.model;

import org.eclipse.emf.ecore.EFactory;
import org.eclipse.emf.ecore.EPackage;
import org.slizaa.hierarchicalgraph.core.model.impl.CustomHierarchicalgraphFactoryImpl;

public class CustomFactoryStandaloneSupport {

    public static void registerCustomHierarchicalgraphFactory() {

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
  }
}
