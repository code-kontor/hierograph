/* eslint-disable */
import * as types from './graphql';
import type { TypedDocumentNode as DocumentNode } from '@graphql-typed-document-node/core';

/**
 * Map of all GraphQL operations in the project.
 *
 * This map has several performance disadvantages:
 * 1. It is not tree-shakeable, so it will include all operations in the project.
 * 2. It is not minifiable, so the string of a GraphQL query will be multiple times inside the bundle.
 * 3. It does not support dead code elimination, so it will add unused operations.
 *
 * Therefore it is highly recommended to use the babel or swc plugin for production.
 * Learn more about it here: https://the-guild.dev/graphql/codegen/plugins/presets/preset-client#reducing-bundle-size
 */
type Documents = {
    "\n  query GraphStatus {\n    server {\n      graph {\n        state\n        update\n        generation\n      }\n    }\n  }\n": typeof types.GraphStatusDocument,
    "\n  query StartScreen {\n    server {\n      url\n      uiUrl\n      version\n      commit {\n        id\n        dirty\n      }\n      startedAt\n      workspace\n      stateDir\n      source {\n        kind\n        manifest\n        buildRoot\n        includeTests\n        watchMode\n        buildFilesMode\n        parquetDir\n      }\n      artifacts {\n        declared\n        scanned\n        notCompiled {\n          artifact\n          dirs\n        }\n      }\n      graph {\n        state\n        update\n        generation\n        loadedAt\n        counts {\n          modules\n          packages\n          types\n          total\n        }\n      }\n    }\n    views {\n      name\n      description\n      source\n      valid\n      problems\n      groupCount\n      errors\n      warnings\n      restructure\n    }\n  }\n": typeof types.StartScreenDocument,
};
const documents: Documents = {
    "\n  query GraphStatus {\n    server {\n      graph {\n        state\n        update\n        generation\n      }\n    }\n  }\n": types.GraphStatusDocument,
    "\n  query StartScreen {\n    server {\n      url\n      uiUrl\n      version\n      commit {\n        id\n        dirty\n      }\n      startedAt\n      workspace\n      stateDir\n      source {\n        kind\n        manifest\n        buildRoot\n        includeTests\n        watchMode\n        buildFilesMode\n        parquetDir\n      }\n      artifacts {\n        declared\n        scanned\n        notCompiled {\n          artifact\n          dirs\n        }\n      }\n      graph {\n        state\n        update\n        generation\n        loadedAt\n        counts {\n          modules\n          packages\n          types\n          total\n        }\n      }\n    }\n    views {\n      name\n      description\n      source\n      valid\n      problems\n      groupCount\n      errors\n      warnings\n      restructure\n    }\n  }\n": types.StartScreenDocument,
};

/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 *
 *
 * @example
 * ```ts
 * const query = graphql(`query GetUser($id: ID!) { user(id: $id) { name } }`);
 * ```
 *
 * The query argument is unknown!
 * Please regenerate the types.
 */
export function graphql(source: string): unknown;

/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n  query GraphStatus {\n    server {\n      graph {\n        state\n        update\n        generation\n      }\n    }\n  }\n"): (typeof documents)["\n  query GraphStatus {\n    server {\n      graph {\n        state\n        update\n        generation\n      }\n    }\n  }\n"];
/**
 * The graphql function is used to parse GraphQL queries into a document that can be used by GraphQL clients.
 */
export function graphql(source: "\n  query StartScreen {\n    server {\n      url\n      uiUrl\n      version\n      commit {\n        id\n        dirty\n      }\n      startedAt\n      workspace\n      stateDir\n      source {\n        kind\n        manifest\n        buildRoot\n        includeTests\n        watchMode\n        buildFilesMode\n        parquetDir\n      }\n      artifacts {\n        declared\n        scanned\n        notCompiled {\n          artifact\n          dirs\n        }\n      }\n      graph {\n        state\n        update\n        generation\n        loadedAt\n        counts {\n          modules\n          packages\n          types\n          total\n        }\n      }\n    }\n    views {\n      name\n      description\n      source\n      valid\n      problems\n      groupCount\n      errors\n      warnings\n      restructure\n    }\n  }\n"): (typeof documents)["\n  query StartScreen {\n    server {\n      url\n      uiUrl\n      version\n      commit {\n        id\n        dirty\n      }\n      startedAt\n      workspace\n      stateDir\n      source {\n        kind\n        manifest\n        buildRoot\n        includeTests\n        watchMode\n        buildFilesMode\n        parquetDir\n      }\n      artifacts {\n        declared\n        scanned\n        notCompiled {\n          artifact\n          dirs\n        }\n      }\n      graph {\n        state\n        update\n        generation\n        loadedAt\n        counts {\n          modules\n          packages\n          types\n          total\n        }\n      }\n    }\n    views {\n      name\n      description\n      source\n      valid\n      problems\n      groupCount\n      errors\n      warnings\n      restructure\n    }\n  }\n"];

export function graphql(source: string) {
  return (documents as any)[source] ?? {};
}

export type DocumentType<TDocumentNode extends DocumentNode<any, any>> = TDocumentNode extends DocumentNode<  infer TType,  any>  ? TType  : never;