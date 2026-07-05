import type { LucideIcon } from "lucide-react";
import {
  AtSign,
  Blocks,
  ExternalLink,
  File,
  FileCode,
  Folder,
  List,
  Package,
  SquareFunction,
  Table,
  Variable,
} from "lucide-react";

const iconByType: Record<string, LucideIcon> = {
  "java.module": Package,
  "java.package": Folder,
  "java.class": FileCode,
  "java.interface": Blocks,
  "java.enum": List,
  "java.record": Table,
  "java.annotation": AtSign,
  "java.method": SquareFunction,
  "java.field": Variable,
  "external.type": ExternalLink,
};

export function getNodeIcon(type: string): LucideIcon {
  return iconByType[type] ?? File;
}
