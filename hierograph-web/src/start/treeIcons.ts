import {
  CircleDot,
  FileCode,
  Folder,
  Globe,
  Layers,
  type LucideIcon,
  Package,
} from "lucide-react";

import type { TreeNodeKind } from "./treeLabels";

// The icon of each kind of node, wherever the start screen names a node.
export const KIND_ICONS: Record<TreeNodeKind, LucideIcon> = {
  group: Layers,
  module: Package,
  external: Globe,
  package: Folder,
  type: FileCode,
  member: CircleDot,
  other: CircleDot,
};
