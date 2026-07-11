import { ChevronRight } from "lucide-react";
import type { ReactNode } from "react";

import { cn } from "@/design-system/cn";

export type DrillCrumb = { id: string; label: string };

type DiagramBreadcrumbProps = {
  rootLabel: string;
  path: DrillCrumb[];
  // index -1 => back to the tree-selection root scope; 0..n-1 => truncate drill
  // path to that depth (keep the first index+1 entries).
  onNavigate: (index: number) => void;
};

export function DiagramBreadcrumb({
  rootLabel,
  path,
  onNavigate,
}: DiagramBreadcrumbProps) {
  return (
    <nav className="text-muted-foreground flex items-center gap-1 font-mono text-[11px]">
      <BreadcrumbButton
        label={rootLabel}
        onClick={() => onNavigate(-1)}
        isLast={path.length === 0}
      />
      <BreadcrumbTrail path={path} onNavigate={onNavigate} />
    </nav>
  );
}

type BreadcrumbTrailProps = {
  path: DrillCrumb[];
  onNavigate: (index: number) => void;
};

function BreadcrumbTrail({ path, onNavigate }: BreadcrumbTrailProps) {
  const items: ReactNode[] = [];
  for (let index = 0; index < path.length; index++) {
    const crumb = path[index];
    items.push(
      <BreadcrumbButton
        key={crumb.id}
        label={crumb.label}
        onClick={() => onNavigate(index)}
        isLast={index === path.length - 1}
        showChevron
      />,
    );
  }
  return <>{items}</>;
}

type BreadcrumbButtonProps = {
  label: string;
  onClick: () => void;
  isLast: boolean;
  showChevron?: boolean;
};

function BreadcrumbButton({
  label,
  onClick,
  isLast,
  showChevron,
}: BreadcrumbButtonProps) {
  return (
    <span className="flex items-center gap-1">
      {showChevron && <ChevronRight size={12} />}
      <button
        type="button"
        disabled={isLast}
        onClick={onClick}
        className={cn("hover:text-foreground", isLast && "text-foreground")}
      >
        {label}
      </button>
    </span>
  );
}
