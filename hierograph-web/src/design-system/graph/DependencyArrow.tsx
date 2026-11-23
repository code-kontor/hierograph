import { type ComponentProps, useId } from "react";

// How a drawn dependency relates to what is in focus: one the focus uses, one
// on it, one of a cycle (a feedback dependency against the levels, or one
// closing a cycle), or one handed to the screen from outside (e.g. the
// dependencies in an agent's link).
export type DependencyRole = "uses" | "usedBy" | "feedback" | "given";

// The colours of the roles, from the tokens: what the focus uses in the
// accent, what uses it in grey, a cycle's in the cycle colour, given ones in
// green.
const ROLE_COLOURS: Record<DependencyRole, string> = {
  uses: "var(--hg-accent)",
  usedBy: "var(--hg-fg-muted)",
  feedback: "var(--hg-status-warning)",
  given: "var(--hg-status-success)",
};

const ROLES = Object.keys(ROLE_COLOURS) as DependencyRole[];

// The stroke width: a cycle's arrows fixed (heavier when they touch the
// focus), the others by weight on a log scale, against the heaviest drawn.
export function dependencyArrowWidth(
  role: DependencyRole,
  weight: number,
  maxWeight: number,
  touchesFocus: boolean,
): number {
  if (role === "feedback") return touchesFocus ? 1.6 : 1;
  return (
    0.9 + (0.9 * Math.log(1 + weight)) / Math.log(1 + Math.max(1, maxWeight))
  );
}

// The id the arrowheads of one SVG are defined under: put
// `<DependencyArrowHeads id={…} />` into the SVG and pass the same id to its
// arrows.
export function useArrowHeadsId(): string {
  return `ah${useId().replace(/[^a-zA-Z0-9]/g, "")}`;
}

type DependencyArrowHeadsProps = {
  id: string;
};

// The arrowheads of every role, as markers: open chevrons with their tip on
// the end of the line.
export function DependencyArrowHeads({ id }: DependencyArrowHeadsProps) {
  const markers = ROLES.map((role) => (
    <ArrowHead key={role} id={`${id}-${role}`} color={ROLE_COLOURS[role]} />
  ));
  return <defs>{markers}</defs>;
}

type ArrowHeadProps = {
  id: string;
  color: string;
};

function ArrowHead({ id, color }: ArrowHeadProps) {
  return (
    <marker
      id={id}
      viewBox="0 0 10 10"
      refX="9"
      refY="5"
      markerWidth="9"
      markerHeight="9"
      orient="auto-start-reverse"
      markerUnits="userSpaceOnUse"
    >
      <path
        d="M1.5,1.5 L9,5 L1.5,8.5"
        fill="none"
        stroke={color}
        strokeWidth="1.6"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </marker>
  );
}

type DependencyArrowProps = ComponentProps<"g"> & {
  // The line's path; it ends on what is depended on.
  d: string;
  role: DependencyRole;
  // Against the order (the levels, the tree's order): dashed.
  against: boolean;
  width: number;
  // The id of the SVG's `DependencyArrowHeads`.
  heads: string;
  // The tooltip.
  title: string;
};

// A dependency as every screen draws it: a line in its role's colour, by its
// width, dashed against the order, with an open arrowhead on what is depended
// on; at full strength under the pointer. A wider invisible line takes the
// pointer, so the arrow is easy to hover and to right-click for its menu. The
// group passes other props (a menu trigger's, data attributes) on.
export function DependencyArrow({
  d,
  role,
  against,
  width,
  heads,
  title,
  ...props
}: DependencyArrowProps) {
  return (
    <g {...props} className="group" data-arrow>
      <title>{title}</title>
      <path
        d={d}
        fill="none"
        stroke={ROLE_COLOURS[role]}
        strokeWidth={width.toFixed(2)}
        strokeDasharray={against ? "5 3" : undefined}
        strokeOpacity={against ? 0.95 : 0.7}
        markerEnd={`url(#${heads}-${role})`}
        className="group-hover:[stroke-opacity:1]"
        data-arrow-line
        data-role={role}
      />
      <path
        d={d}
        fill="none"
        stroke="transparent"
        strokeWidth={10}
        style={{ pointerEvents: "stroke" }}
        data-arrow-hit
      />
    </g>
  );
}
