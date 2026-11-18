// The start screen. A placeholder until it shows the workspace, the graph and
// the hierarchy views from the server.
export function StartPage() {
  return (
    <section className="text-fg flex flex-col gap-2 p-3">
      <h1 className="text-[18px] font-semibold">Welcome to hierograph</h1>
      <p className="text-fg-muted text-[13px]">
        Screens are opened from your agent session.
      </p>
    </section>
  );
}
