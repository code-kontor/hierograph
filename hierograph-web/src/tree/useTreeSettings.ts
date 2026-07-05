import { useLocalStorage } from "@/design-system/useLocalStorage";
import type { NodeLabelFormat } from "@/graph";

function normalizeTreeLabelFormat(value: string): NodeLabelFormat {
  return value === "shortened" || value === "abbreviated"
    ? "abbreviated"
    : "full";
}

export type TreeSettings = {
  showIndentGuides: boolean;
  autoExpandSingleChildren: boolean;
  preserveSelectionOnCollapse: boolean;
  labelFormat: NodeLabelFormat;
};

export type TreeSettingsControls = {
  setShowIndentGuides: (v: boolean) => void;
  setAutoExpandSingleChildren: (v: boolean) => void;
  setPreserveSelectionOnCollapse: (v: boolean) => void;
  setLabelFormat: (v: NodeLabelFormat) => void;
};

export type UseTreeSettingsResult = {
  settings: TreeSettings;
} & TreeSettingsControls;

export const DEFAULT_TREE_SETTINGS: TreeSettings = {
  showIndentGuides: true,
  autoExpandSingleChildren: false,
  preserveSelectionOnCollapse: false,
  labelFormat: "full",
};

export function useTreeSettings(): UseTreeSettingsResult {
  const [showIndentGuides, setShowIndentGuides] = useLocalStorage<boolean>(
    "tree.showIndentGuides",
    true,
  );
  const [autoExpandSingleChildren, setAutoExpandSingleChildren] =
    useLocalStorage<boolean>("tree.autoExpandSingleChildren", false);
  const [preserveSelectionOnCollapse, setPreserveSelectionOnCollapse] =
    useLocalStorage<boolean>("tree.preserveSelectionOnCollapse", false);
  const [storedLabelFormat, setLabelFormat] = useLocalStorage<string>(
    "tree.labelFormat",
    "full",
  );
  const labelFormat = normalizeTreeLabelFormat(storedLabelFormat);

  return {
    settings: {
      showIndentGuides,
      autoExpandSingleChildren,
      preserveSelectionOnCollapse,
      labelFormat,
    },
    setShowIndentGuides,
    setAutoExpandSingleChildren,
    setPreserveSelectionOnCollapse,
    setLabelFormat,
  };
}
