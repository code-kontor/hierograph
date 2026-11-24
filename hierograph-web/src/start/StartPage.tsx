import { HierarchyTree } from "./HierarchyTree";

// The start screen: the hierarchy tree of the current hierarchy view, as wide
// and tall as the window. The view is chosen in the header, and the server's
// state, with what the view excludes, is in the header's status panel. It
// needs no graph: the tree says when there is none.
export function StartPage() {
  return (
    // As tall as the space it is given, scrolling on its own.
    <div className="text-fg relative h-full min-h-[480px] min-w-0">
      <div className="absolute inset-0">
        <HierarchyTree />
      </div>
    </div>
  );
}
