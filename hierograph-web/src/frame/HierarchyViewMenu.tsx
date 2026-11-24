import { ChevronDown } from "lucide-react";
import type { ReactNode } from "react";
import { twMerge } from "tailwind-merge";

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/design-system/ui/dropdown-menu";
import type { HierarchyViewsQuery } from "@/graphql/generated/graphql";

type HierarchyView = HierarchyViewsQuery["views"][number];

// The radio value of the hierarchy as scanned: no `view=` at all. A view name
// is never empty, so it can't clash.
const DEFAULT_VALUE = "";

// What choosing a view does, at the top of the menu.
export const CHOOSE_HINT =
  "Chooses the hierarchy this UI shows. The agent session isn't affected.";

// The views by where their document lives (`ViewInfo.source`), in this order:
// kept with the code, then defined at runtime.
const SECTIONS = [
  { source: "project", label: "Project views" },
  { source: "state", label: "Runtime views" },
] as const;

type HierarchyViewMenuProps = {
  // The current `view=`, `undefined` for the hierarchy as scanned.
  view: string | undefined;
  // `undefined` while the views load (or the server can't be reached).
  views: HierarchyViewsQuery | undefined;
  onChoose: (view: string | undefined) => void;
};

// The header's hierarchy view: shows the current one and chooses another,
// on every screen. The hierarchy as scanned ("default") comes first, then the
// views by where they are defined. Every row has the same structure: the name,
// then its groups and what it excludes, then its description; an invalid view
// is listed with its problems but can't be chosen.
export function HierarchyViewMenu({
  view,
  views,
  onChoose,
}: HierarchyViewMenuProps) {
  const modules = views?.server.graph.counts?.modules;
  const all = views?.views ?? [];
  const choose = (value: string) => {
    onChoose(value === DEFAULT_VALUE ? undefined : value);
  };
  const sections = SECTIONS.map((section) => (
    <ViewSection
      key={section.source}
      label={section.label}
      views={all.filter((entry) => entry.source === section.source)}
    />
  ));
  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        className="hover:bg-state-hover focus-visible:ring-ring text-fg-muted flex h-7 items-center gap-1.5 rounded-[5px] px-2 text-[12px] outline-none focus-visible:ring-2"
        aria-label={`Hierarchy view: ${view ?? "default"}`}
      >
        Hierarchy view
        <span
          className={twMerge(
            "text-fg font-mono font-medium",
            view !== undefined && "text-primary",
          )}
        >
          {view ?? "default"}
        </span>
        <ChevronDown className="size-3.5" />
      </DropdownMenuTrigger>
      <DropdownMenuContent className="max-h-[75vh] w-[380px] overflow-y-auto">
        <p className="text-fg-subtle px-2 pt-1 pb-1.5 text-[11px]">
          {CHOOSE_HINT}
        </p>
        <DropdownMenuSeparator />
        <DropdownMenuRadioGroup
          value={view ?? DEFAULT_VALUE}
          onValueChange={choose}
        >
          <DropdownMenuLabel>Built-in</DropdownMenuLabel>
          <ViewItem
            value={DEFAULT_VALUE}
            name="default"
            meta={["no groups", "nothing excluded"]}
            description={
              modules === undefined
                ? "The hierarchy as scanned from the build."
                : `The hierarchy as scanned from the build: ${plural(modules, "module", "modules")}.`
            }
          />
          {sections}
        </DropdownMenuRadioGroup>
        {all.length === 0 && views !== undefined && (
          <p className="text-fg-muted px-2 py-1.5 text-[12px]">
            No hierarchy views are defined. Ask the agent to define one, or add
            a document to <span className="font-mono">.hierograph-views/</span>.
          </p>
        )}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

type ViewSectionProps = {
  label: string;
  views: HierarchyView[];
};

// One source's views under its label: the valid ones to choose, then the
// invalid ones.
function ViewSection({ label, views }: ViewSectionProps) {
  if (views.length === 0) return null;
  const valid = views.filter((view) => view.valid);
  const invalid = views.filter((view) => !view.valid);
  const validItems = valid.map((view) => (
    <ViewItem
      key={view.name}
      value={view.name}
      name={view.name}
      meta={viewMeta(view)}
      badge={view.restructure ? <Badge>restructuring</Badge> : undefined}
      description={view.description ?? undefined}
    />
  ));
  const invalidItems = invalid.map((view) => (
    <InvalidView key={view.name} view={view} />
  ));
  return (
    <>
      <DropdownMenuSeparator />
      <DropdownMenuLabel>{label}</DropdownMenuLabel>
      {validItems}
      {invalidItems}
    </>
  );
}

type InvalidViewProps = {
  view: HierarchyView;
};

// Not an item: it can't be chosen, so the keyboard skips it.
function InvalidView({ view }: InvalidViewProps) {
  return (
    <div className="py-1.5 pr-2 pl-[32px]">
      <ViewName
        name={view.name}
        badge={<span className="text-status-error text-[12px]">invalid</span>}
      />
      {view.description && <Description text={view.description} />}
      <ProblemList problems={view.problems} />
    </div>
  );
}

type ViewItemProps = {
  value: string;
  name: string;
  meta: string[];
  badge?: ReactNode;
  description: string | undefined;
};

function ViewItem({ value, name, meta, badge, description }: ViewItemProps) {
  return (
    <DropdownMenuRadioItem
      value={value}
      className="h-auto items-start py-1.5 [&>span:first-child]:mt-0.5"
    >
      <div className="min-w-0">
        <ViewName name={name} badge={badge} />
        <p className="text-fg-muted text-[12px]">{meta.join(" · ")}</p>
        {description && <Description text={description} clamp />}
      </div>
    </DropdownMenuRadioItem>
  );
}

// The same fields for every view: its groups, and what it leaves out.
function viewMeta(view: HierarchyView): string[] {
  const groups = view.groupCount ?? 0;
  return [
    groups === 0 ? "no groups" : plural(groups, "group", "groups"),
    excludedLabel(view.excludedNodes),
  ];
}

// What a view leaves out, by the kind of its outermost excluded nodes:
// "excludes 69 modules", "excludes 2 modules, 3 packages". Everything inside
// them goes with them.
function excludedLabel(nodes: HierarchyView["excludedNodes"]): string {
  if (!nodes || nodes.length === 0) return "nothing excluded";
  const counts = new Map<Kind, number>();
  for (const node of nodes) {
    const kind = kindOf(node.type);
    counts.set(kind, (counts.get(kind) ?? 0) + 1);
  }
  const parts = KINDS.filter((kind) => counts.has(kind)).map((kind) =>
    plural(counts.get(kind) ?? 0, kind, PLURALS[kind]),
  );
  return `excludes ${parts.join(", ")}`;
}

type Kind = "module" | "package" | "type" | "member" | "node";
const KINDS: Kind[] = ["module", "package", "type", "member", "node"];
const PLURALS: Record<Kind, string> = {
  module: "modules",
  package: "packages",
  type: "types",
  member: "members",
  node: "nodes",
};

function kindOf(type: string): Kind {
  if (type === "java.module") return "module";
  if (type === "java.package") return "package";
  if (type === "java.method" || type === "java.field") return "member";
  if (type.startsWith("java.") || type === "external.type") return "type";
  return "node";
}

type ViewNameProps = {
  name: string;
  badge: ReactNode;
};

function ViewName({ name, badge }: ViewNameProps) {
  return (
    <div className="flex flex-wrap items-baseline gap-x-2">
      <span className="text-fg font-mono text-[13px] font-medium">{name}</span>
      {badge}
    </div>
  );
}

type DescriptionProps = {
  text: string;
  // At most two lines, the whole text on hover: a long description doesn't
  // make the menu tall.
  clamp?: boolean;
};

function Description({ text, clamp }: DescriptionProps) {
  return (
    <p
      className={twMerge(
        "text-fg-muted mt-0.5 text-[12px]",
        clamp && "line-clamp-2",
      )}
      title={clamp ? text : undefined}
    >
      {text}
    </p>
  );
}

type BadgeProps = {
  children: ReactNode;
};

function Badge({ children }: BadgeProps) {
  return (
    <span className="border-border text-fg-muted rounded-[4px] border px-1 text-[11px]">
      {children}
    </span>
  );
}

type ProblemListProps = {
  problems: string[];
};

function ProblemList({ problems }: ProblemListProps) {
  const items = problems.map((problem) => (
    <ProblemItem key={problem} problem={problem} />
  ));
  return (
    <ul className="text-status-error mt-1 list-disc pl-4 text-[12px]">
      {items}
    </ul>
  );
}

type ProblemItemProps = {
  problem: string;
};

function ProblemItem({ problem }: ProblemItemProps) {
  return <li className="font-mono break-words">{problem}</li>;
}

function plural(count: number, one: string, many: string): string {
  return `${count.toLocaleString("en")} ${count === 1 ? one : many}`;
}
