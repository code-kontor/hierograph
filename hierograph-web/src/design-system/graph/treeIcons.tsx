import {
  CircleDot,
  FileCode,
  Folder,
  Globe,
  Layers,
  type LucideIcon,
  type LucideProps,
  Package,
} from "lucide-react";

import { type TreeNodeKind, treeNodeKind } from "./treeLabels";

// The icon of each kind of node, wherever the UI names a node in a list.
export const KIND_ICONS: Record<TreeNodeKind, LucideIcon> = {
  group: Layers,
  module: Package,
  external: Globe,
  package: Folder,
  type: FileCode,
  member: CircleDot,
  other: CircleDot,
};

// The letter IDEs give each kind of type, so classes, interfaces, enums,
// records and annotations tell apart at a glance (a type outside the
// workspace keeps the plain type icon).
const TYPE_LETTERS: Record<string, string> = {
  "java.class": "C",
  "java.interface": "I",
  "java.enum": "E",
  "java.record": "R",
  "java.annotation": "@",
};

type NodeIconProps = LucideProps & {
  type: string;
  text: string;
};

// A node's icon: a type's letter for a type, otherwise its kind's icon.
export function NodeIcon({ type, text, ...props }: NodeIconProps) {
  const letter = TYPE_LETTERS[type];
  if (letter !== undefined) return <LetterIcon letter={letter} {...props} />;
  const Icon = KIND_ICONS[treeNodeKind(type, text)];
  return <Icon {...props} />;
}

type LetterIconProps = LucideProps & {
  letter: string;
};

// A rounded square with a letter in it, drawn like a Lucide icon.
function LetterIcon({ letter, ...props }: LetterIconProps) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      {...(props as React.SVGProps<SVGSVGElement>)}
    >
      <rect x="2.5" y="2.5" width="19" height="19" rx="5" />
      <text
        x="12"
        y="12"
        dy="0.36em"
        textAnchor="middle"
        fill="currentColor"
        stroke="none"
        fontSize="13"
        fontWeight="700"
        fontFamily="ui-sans-serif, system-ui, sans-serif"
      >
        {letter}
      </text>
    </svg>
  );
}
