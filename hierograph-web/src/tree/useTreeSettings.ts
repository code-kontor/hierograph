import { useLocalStorage } from "@/design-system/useLocalStorage";
import { type TreeLabelFormat } from "@/graph/treeLabelFormat";

export type TreeSettings = {
  showIndentGuides: boolean;
  autoExpandSingleChildren: boolean;
  preserveSelectionOnCollapse: boolean;
  labelFormat: TreeLabelFormat;
};

export type TreeSettingsControls = {
  setShowIndentGuides: (v: boolean) => void;
  setAutoExpandSingleChildren: (v: boolean) => void;
  setPreserveSelectionOnCollapse: (v: boolean) => void;
  setLabelFormat: (v: TreeLabelFormat) => void;
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
  const [labelFormat, setLabelFormat] = useLocalStorage<TreeLabelFormat>(
    "tree.labelFormat",
    "full",
  );

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
