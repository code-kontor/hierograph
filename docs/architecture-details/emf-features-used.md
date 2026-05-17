# EMF Features Used in Hierarchicalgraph Core Model

See also: [EMF to Kotlin Migration Difficulty Assessment](emf-migration-difficulty.md)

## EMF Features USED

| Feature | How it's used |
|---|---|
| **Notifications / Adapters** | `Adapter`, `Notification`, `ENotificationImpl` -- used for cache invalidation when model changes |
| **EList variants** | `BasicEList`, `ECollections`, `EObjectEList`, `EObjectContainmentWithInverseEList`, `EObjectResolvingEList` + custom `EObjectEListWithoutUniqueCheck` |
| **EMap** | `EMap`, `BasicEMap`, `EcoreEMap` -- for caching aggregated dependency maps in `ExtendedHGNodeTrait` |
| **EOperations** | Extensively -- `getIdentifier()`, `isPredecessorOf()`, `getIncomingDependenciesFrom()`, `resolveProxyDependencies()`, etc. |
| **EDataTypes** | Standard types + custom: `Future<T>`, `Optional<T>` |
| **EPackage Registry** | `CustomFactoryStandaloneSupport` registers the package with a custom factory |
| **EFactory / EPackage** | Full generated factory and package metadata (~3000 lines) |
| **EMF Reflection** | `eStaticClass()`, `eGet()`, `eSet()`, `eInverseAdd()` in generated impls |
| **EcoreUtil** | `EcoreUtil.isAncestor()` and other utilities |
| **EMF Edit** (partial) | Only `IItemLabelProvider` / `IItemStyledLabelProvider` for label rendering |

## EMF Features NOT USED

| Feature | Notes |
|---|---|
| **Resources / ResourceSet / XMI** | No serialization in main code (XMI only in tests) |
| **Validation Framework** | No `Diagnostician` or validation logic |
| **EMF Transaction** | No transactional editing domain |
| **EMF Compare** | Not used |
| **CDO** | Not used |
| **EMF Query** | Not used |
| **OCL** | No constraints |
| **EContentAdapter** | Uses manual adapters instead |
| **EMF Proxies** | `resolveProxies="false"` everywhere -- custom `HGProxyDependency` concept instead of EMF's lazy proxy resolution |
| **EAnnotations (runtime)** | Present in `.ecore` for documentation only, not accessed at runtime |

## Key Insight

The model uses EMF as an **in-memory domain modeling framework** -- leveraging the core metamodel
(classes, attributes, references, operations, data types), the notification/adapter system for
reactivity, and the collection infrastructure (EList, EMap). It deliberately avoids all the heavier
EMF features: no persistence, no transactions, no validation, no distributed storage. The custom
`Extended*` implementation pattern and hand-built caching in `ExtendedHGNodeTrait` show that the
team chose to build domain-specific behavior on top of EMF's structural foundation rather than
relying on EMF's higher-level frameworks.
