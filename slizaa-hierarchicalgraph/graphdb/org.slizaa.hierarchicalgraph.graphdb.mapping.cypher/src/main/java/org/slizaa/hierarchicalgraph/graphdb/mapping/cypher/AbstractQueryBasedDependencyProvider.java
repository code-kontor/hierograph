package org.slizaa.hierarchicalgraph.graphdb.mapping.cypher;

import org.slizaa.core.boltclient.IBoltClient;
import org.slizaa.hierarchicalgraph.core.model.HGProxyDependency;
import org.slizaa.hierarchicalgraph.graphdb.mapping.cypher.internal.BoltClientQueries;
import org.slizaa.hierarchicalgraph.graphdb.mapping.cypher.internal.ProxyDependencyQueriesHolder;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.IDependencyDefinition;
import org.slizaa.hierarchicalgraph.graphdb.mapping.spi.IDependencyDefinitionProvider;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Future;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Base class for dependency providers that use Cypher queries to define dependencies.
 *
 * <p>Subclasses implement {@link #initialize()} and register dependency queries using
 * {@link #addSimpleDependencyDefinitions(String)} or
 * {@link #addProxyDependencyDefinitions(String[], String[])}.
 *
 * <h2>Query Result Format</h2>
 *
 * <p>All dependency queries must return rows with exactly 5 columns in this order:
 * <ol>
 *   <li><b>id(sourceNode)</b> — the Neo4j node ID of the dependency source</li>
 *   <li><b>id(targetNode)</b> — the Neo4j node ID of the dependency target</li>
 *   <li><b>id(relationship)</b> — the Neo4j relationship ID</li>
 *   <li><b>type(relationship)</b> — the relationship type as a string</li>
 *   <li><b>weight</b> — an integer weight for the dependency (use {@code 1} if not applicable)</li>
 * </ol>
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * public class MyDependencyProvider extends AbstractQueryBasedDependencyProvider {
 *
 *     @Override
 *     protected void initialize() {
 *         // Simple (eagerly resolved) dependencies:
 *         addSimpleDependencyDefinitions(
 *             "MATCH (t1:Type)-[r:DEPENDS_ON]->(t2:Type) " +
 *             "RETURN id(t1), id(t2), id(r), type(r), r.weight"
 *         );
 *
 *         // Proxy (lazily resolved) dependencies:
 *         // The proxy query defines coarse-grained edges (5 columns, same as simple queries);
 *         // the detail queries resolve fine-grained edges on demand (also 5 columns).
 *         // Detail queries must use $from and $to parameters to filter by node IDs.
 *         addProxyDependencyDefinitions(
 *             "MATCH (p1:Package)-[r:DEPENDS_ON]->(p2:Package) " +
 *             "RETURN id(p1), id(p2), id(r), type(r), r.weight",
 *             new String[] {
 *                 "MATCH (t1:Type)-[r:INVOKES]->(t2:Type) " +
 *                 "WHERE id(t1) IN $from AND id(t2) IN $to " +
 *                 "RETURN id(t1), id(t2), id(r), type(r), r.weight"
 *             }
 *         );
 *     }
 * }
 * }</pre>
 */
public abstract class AbstractQueryBasedDependencyProvider implements IDependencyDefinitionProvider, IBoltClientAware {

    private final List<String> _simpleDependenciesQueries;

    private final List<ProxyDependencyQueriesHolder> _proxyDependenciesQueries;

    private List<IDependencyDefinition> _dependencies;

    public AbstractQueryBasedDependencyProvider() {
        this._simpleDependenciesQueries = new LinkedList<>();
        this._proxyDependenciesQueries = new LinkedList<>();
    }

    @Override
    public final void initialize(final IBoltClient boltClient) throws Exception {

        checkNotNull(boltClient);

        initialize();

        this._dependencies = new ArrayList<>();

        // simple dependencies
        for (String query : this._simpleDependenciesQueries) {
            this._dependencies.addAll(BoltClientQueries.resolveDependencyQuery(boltClient, query, null));
        }

        // proxy dependencies
        for (ProxyDependencyQueriesHolder proxyDependenciesDefinition : this._proxyDependenciesQueries) {

            // create the resolver function
            Function<HGProxyDependency, List<Future<List<IDependencyDefinition>>>> resolverFunction = (proxyDependency) -> BoltClientQueries.resolveProxyDependency(proxyDependency, proxyDependenciesDefinition, boltClient);

            // resolve the 'top-level' queries
            for (String query : proxyDependenciesDefinition.proxyDependencyQueries()) {
                this._dependencies.addAll(BoltClientQueries.resolveDependencyQuery(boltClient, query, resolverFunction));
            }
        }
    }

    @Override
    public final List<IDependencyDefinition> getDependencies() throws Exception {
        return this._dependencies;
    }

    protected abstract void initialize();

    protected void addProxyDependencyDefinitions(String[] proxyDependencyQueries, String[] detailDependencyQueries) {
        this._proxyDependenciesQueries.add(
                new ProxyDependencyQueriesHolder(checkNotNull(proxyDependencyQueries), checkNotNull(detailDependencyQueries)));
    }

    protected void addProxyDependencyDefinitions(String proxyDependencyQuery, String[] detailDependencyQueries) {
        this._proxyDependenciesQueries.add(new ProxyDependencyQueriesHolder(
                new String[]{checkNotNull(proxyDependencyQuery)}, checkNotNull(detailDependencyQueries)));
    }

    protected void addSimpleDependencyDefinitions(String simpleDependencyQuery) {
        this._simpleDependenciesQueries.add(simpleDependencyQuery);
    }
}