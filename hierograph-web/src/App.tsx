import { RootNodeCard } from "@/components/RootNodeCard";
import { Button } from "@/components/ui/button";

export default function App() {
  return (
    <main className="flex min-h-svh flex-col items-center justify-center gap-6">
      <h1 className="text-4xl font-bold tracking-tight">hierograph</h1>
      <p className="text-muted-foreground">
        Frontend scaffold — Vite, React, Tailwind CSS, shadcn/ui
      </p>
      <Button>Get started</Button>
      <RootNodeCard />
    </main>
  );
}
