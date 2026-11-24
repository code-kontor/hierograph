import { HelpPopoverButton } from "@/design-system/ui/help-popover";

// One gesture of a graph: the input, and what it does.
export type Gesture = { keys: string; action: string };

// The gestures every graph has (docs/design-system.md, "Interaction"); a
// graph that zooms or pans adds its own.
export const SELECTION_GESTURES: Gesture[] = [
  { keys: "Click", action: "Select: what it uses and what uses it" },
  { keys: "Click again, Esc", action: "Clear the selection" },
  {
    keys: "Pin button",
    action: "Hold the selection: clicks keep it, only a menu changes it",
  },
  { keys: "Click the empty space", action: "Clear the selection" },
  { keys: "Double-click, chevron", action: "Expand or collapse" },
  { keys: "Right-click", action: "The menu, each item with its gesture" },
  { keys: "Wheel, Shift + wheel", action: "Scroll" },
];

// The keys every graph has. The arrow keys move the way a graph is drawn,
// so each graph lists its own.
export const KEYBOARD_GESTURES: Gesture[] = [
  { keys: "Home End", action: "The first, the last node" },
  { keys: "+ / −", action: "Expand, collapse (−: or the container)" },
  { keys: "Enter, Space", action: "Select" },
];

type GesturesHelpProps = {
  gestures: Gesture[];
};

// The `?` in the corner of a graph's pane, listing its gestures.
export function GesturesHelp({ gestures }: GesturesHelpProps) {
  const rows = gestures.map((gesture) => (
    <GestureRow key={gesture.keys} {...gesture} />
  ));
  return (
    <HelpPopoverButton label="Gestures">
      <table className="w-full border-collapse text-[11.5px]">
        <tbody>{rows}</tbody>
      </table>
    </HelpPopoverButton>
  );
}

type GestureRowProps = Gesture;

function GestureRow({ keys, action }: GestureRowProps) {
  return (
    <tr>
      <th className="text-fg py-0.5 pr-3 text-left align-top font-mono font-normal whitespace-nowrap">
        {keys}
      </th>
      <td className="text-fg-muted py-0.5">{action}</td>
    </tr>
  );
}
