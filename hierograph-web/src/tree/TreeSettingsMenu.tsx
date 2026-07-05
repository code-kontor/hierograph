import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuGhostTrigger,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
} from "@/design-system/ui/dropdown-menu";
import type { NodeLabelFormat } from "@/graph";

import type { TreeSettings, TreeSettingsControls } from "./useTreeSettings";

type TreeSettingsMenuProps = TreeSettings & TreeSettingsControls;

export function TreeSettingsMenu({
  showIndentGuides,
  autoExpandSingleChildren,
  preserveSelectionOnCollapse,
  labelFormat,
  setShowIndentGuides,
  setAutoExpandSingleChildren,
  setPreserveSelectionOnCollapse,
  setLabelFormat,
}: TreeSettingsMenuProps) {
  return (
    <DropdownMenu>
      <DropdownMenuGhostTrigger title="Settings" />
      <DropdownMenuContent>
        <DropdownMenuCheckboxItem
          checked={showIndentGuides}
          onCheckedChange={setShowIndentGuides}
          onSelect={(e) => e.preventDefault()}
        >
          Indent guides
        </DropdownMenuCheckboxItem>
        <DropdownMenuCheckboxItem
          checked={autoExpandSingleChildren}
          onCheckedChange={setAutoExpandSingleChildren}
          onSelect={(e) => e.preventDefault()}
        >
          Auto-expand single children
        </DropdownMenuCheckboxItem>
        <DropdownMenuCheckboxItem
          checked={preserveSelectionOnCollapse}
          onCheckedChange={setPreserveSelectionOnCollapse}
          onSelect={(e) => e.preventDefault()}
        >
          Preserve selection on collapse
        </DropdownMenuCheckboxItem>
        <DropdownMenuSeparator />
        <DropdownMenuLabel>Label format</DropdownMenuLabel>
        <DropdownMenuRadioGroup
          value={labelFormat}
          onValueChange={(v) => setLabelFormat(v as NodeLabelFormat)}
        >
          <DropdownMenuRadioItem
            value="full"
            onSelect={(e) => e.preventDefault()}
          >
            Full names
          </DropdownMenuRadioItem>
          <DropdownMenuRadioItem
            value="abbreviated"
            onSelect={(e) => e.preventDefault()}
          >
            <span className="flex-1">Shortened</span>
            <span className="text-fg-subtle font-mono text-[11px]">
              n.d.p.owner
            </span>
          </DropdownMenuRadioItem>
        </DropdownMenuRadioGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
