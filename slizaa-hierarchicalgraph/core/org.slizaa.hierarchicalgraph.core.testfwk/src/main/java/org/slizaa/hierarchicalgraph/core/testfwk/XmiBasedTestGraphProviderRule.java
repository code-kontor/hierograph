package org.slizaa.hierarchicalgraph.core.testfwk;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.impl.ResourceImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceFactoryImpl;
import org.eclipse.emf.ecore.xmi.impl.XMIResourceImpl;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.slizaa.hierarchicalgraph.core.model.CustomFactoryStandaloneSupport;
import org.slizaa.hierarchicalgraph.core.model.HGNode;
import org.slizaa.hierarchicalgraph.core.model.HGRootNode;
import org.slizaa.hierarchicalgraph.core.model.HierarchicalgraphPackage;

/**
 * <p>
 * </p>
 *
 * @author Gerd W&uuml;therich (gerd@gerd-wuetherich.de)
 */
public class XmiBasedTestGraphProviderRule implements TestRule {

  /** the root node */
  private HGRootNode           _rootNode;

  /** - */
  private XmiBasedGraph        _testGraph;

  /** - */
  private Consumer<HGRootNode> _testGraphConfigurer;

  /**
   * <p>
   * Creates a new instance of type {@link XmiBasedTestGraphProviderRule}.
   * </p>
   *
   * @param testGraph
   */
  public XmiBasedTestGraphProviderRule(XmiBasedGraph testGraph) {
    this(testGraph, null);
  }

  /**
   * <p>
   * Creates a new instance of type {@link XmiBasedTestGraphProviderRule}.
   * </p>
   *
   * @param testGraph
   */
  public XmiBasedTestGraphProviderRule(XmiBasedGraph testGraph, Consumer<HGRootNode> testGraphConfigurer) {
    this._testGraph = checkNotNull(testGraph);
    this._testGraphConfigurer = testGraphConfigurer;
  }

  @Override
  public Statement apply(Statement base, Description description) {
    return new Statement() {

      @Override
      public void evaluate() throws Throwable {

        _rootNode = load(_testGraph.getXmiFileName());

        // configure the resulting graph if necessary
        if (_testGraphConfigurer != null) {
          _testGraphConfigurer.accept(_rootNode);
        }
        
        base.evaluate();

        _rootNode = null;
      }
    };
  }

  public HGNode node(Long id) {
    return _rootNode.lookupNode(checkNotNull(id));
  }

  public HGNode node(long id) {
    return _rootNode.lookupNode(new Long(id));
  }

  public List<HGNode> nodes(long... ids) {

    //
    List<HGNode> result = new LinkedList<HGNode>();

    //
    for (long id : ids) {
      HGNode node = rootNode().lookupNode(new Long(id));
      result.add(node);
    }

    //
    return result;
  }

  public HGRootNode rootNode() {
    return _rootNode;
  }

  /**
   * <p>
   * </p>
   *
   * @param fileName
   * @return
   */
  private static HGRootNode load(String fileName) {

    //
    CustomFactoryStandaloneSupport.registerCustomHierarchicalgraphFactory();
    
    // register extension, see:
    // https://wiki.eclipse.org/EMF/FAQ#How_do_I_use_EMF_in_standalone_applications_.28such_as_an_ordinary_main.29.3F
    Resource.Factory.Registry.INSTANCE.getExtensionToFactoryMap().put("hggraph", new XMIResourceFactoryImpl());
    @SuppressWarnings("unused")
    HierarchicalgraphPackage hierarchicalgraphPackageInstance = HierarchicalgraphPackage.eINSTANCE;

    // create the ressource
    ResourceImpl resource = new XMIResourceImpl();

    // load the content
    try (InputStream zippedInputStream = XmiBasedTestGraphProviderRule.class.getClassLoader()
        .getResourceAsStream(fileName.replace("hggraph", "zip"))) {

      assertThat(zippedInputStream).isNotNull();

      try (InputStream inputStream = getInputStream(new ZipInputStream(zippedInputStream), fileName)) {
        resource.load(inputStream, null);
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    // return the content
    return (HGRootNode) resource.getContents().get(0);
  }

  /**
   * <p>
   * </p>
   *
   * @param zin
   * @param entry
   * @return
   * @throws IOException
   */
  static InputStream getInputStream(ZipInputStream zin, String entry) throws IOException {
    for (ZipEntry e; (e = zin.getNextEntry()) != null;) {
      if (e.getName().equals(entry)) {
        return zin;
      }
    }
    throw new EOFException("Cannot find " + entry);
  }
}
