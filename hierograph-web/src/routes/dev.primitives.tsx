// Dev-only primitives showcase; long-term handling (keep/remove, prod exclusion) tracked in task #0031.
import { createFileRoute } from "@tanstack/react-router";
import { Plus } from "lucide-react";
import { useState } from "react";

import { Pane } from "@/components/layout/Pane";
import { Button } from "@/components/ui/button";
import { Message } from "@/components/ui/message";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from "@/components/ui/tooltip";

export const Route = createFileRoute("/dev/primitives")({
  component: RouteComponent,
});

function RouteComponent() {
  const [borderedValue, setBorderedValue] = useState("package");
  const [ghostValue, setGhostValue] = useState("rename");

  return (
    <div className="flex flex-col gap-8 p-6">
      <section className="flex flex-col gap-3">
        <h2 className="text-muted-foreground font-mono text-[11px] uppercase">
          Message
        </h2>
        <div className="flex flex-col gap-2">
          <Message variant="loading" title="Loading dependencies">
            This may take a moment.
          </Message>
          <Message variant="empty" title="No results">
            Nothing matches the current filter.
          </Message>
          <Message variant="info" title="Heads up">
            This view only shows direct dependencies.
          </Message>
          <Message variant="error" title="Failed to load">
            The GraphQL request returned an error.
          </Message>
        </div>
      </section>

      <section className="flex flex-col gap-3">
        <h2 className="text-muted-foreground font-mono text-[11px] uppercase">
          Table
        </h2>
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Name</TableHead>
              <TableHead>Type</TableHead>
              <TableHead>Package</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            <TableRow>
              <TableCell>OrderService</TableCell>
              <TableCell>class</TableCell>
              <TableCell>com.example.order</TableCell>
            </TableRow>
            <TableRow data-state="selected">
              <TableCell>OrderRepository</TableCell>
              <TableCell>interface</TableCell>
              <TableCell>com.example.order</TableCell>
            </TableRow>
            <TableRow>
              <TableCell>OrderStatus</TableCell>
              <TableCell>enum</TableCell>
              <TableCell>com.example.order</TableCell>
            </TableRow>
            <TableRow>
              <TableCell>OrderMapper</TableCell>
              <TableCell>class</TableCell>
              <TableCell>com.example.order.mapper</TableCell>
            </TableRow>
          </TableBody>
        </Table>
      </section>

      <section className="flex flex-col gap-3">
        <h2 className="text-muted-foreground font-mono text-[11px] uppercase">
          Select
        </h2>
        <div className="flex items-center gap-4">
          <Select value={borderedValue} onValueChange={setBorderedValue}>
            <SelectTrigger className="w-44">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              <SelectItem value="package">Package</SelectItem>
              <SelectItem value="class">Class</SelectItem>
              <SelectItem value="method">Method</SelectItem>
            </SelectContent>
          </Select>
          <Select value={ghostValue} onValueChange={setGhostValue}>
            <SelectTrigger variant="ghost" />
            <SelectContent>
              <SelectItem value="rename">Rename</SelectItem>
              <SelectItem value="delete">Delete</SelectItem>
              <SelectItem value="duplicate">Duplicate</SelectItem>
            </SelectContent>
          </Select>
        </div>
      </section>

      <section className="flex flex-col gap-3">
        <h2 className="text-muted-foreground font-mono text-[11px] uppercase">
          Pane
        </h2>
        <div className="h-64 w-96">
          <Pane
            title="Hierarchical Graph"
            toolbar={
              <>
                <Select value={ghostValue} onValueChange={setGhostValue}>
                  <SelectTrigger variant="ghost" />
                  <SelectContent>
                    <SelectItem value="rename">Rename</SelectItem>
                    <SelectItem value="delete">Delete</SelectItem>
                    <SelectItem value="duplicate">Duplicate</SelectItem>
                  </SelectContent>
                </Select>
                <Button variant="ghost" size="icon">
                  <Plus />
                </Button>
              </>
            }
          >
            Pane content goes here.
          </Pane>
        </div>
      </section>

      <section className="flex flex-col gap-3">
        <h2 className="text-muted-foreground font-mono text-[11px] uppercase">
          Tooltip
        </h2>
        <TooltipProvider>
          <Tooltip open>
            <TooltipTrigger asChild>
              <span className="text-sm">Hover target</span>
            </TooltipTrigger>
            <TooltipContent>
              <div className="text-foreground font-mono text-[12.5px] font-semibold">
                OrderService
              </div>
              <div className="font-mono text-[11px] text-[var(--hg-fg-subtle)]">
                class
              </div>
              <div className="text-muted-foreground font-mono text-[11px] break-all">
                com.example.order.OrderService
              </div>
            </TooltipContent>
          </Tooltip>
        </TooltipProvider>
      </section>

      <section className="flex flex-col gap-3">
        <h2 className="text-muted-foreground font-mono text-[11px] uppercase">
          Tabs
        </h2>
        <div className="border-border bg-panel-header h-[34px] rounded border">
          <Tabs defaultValue="props">
            <TabsList>
              <TabsTrigger value="props">Node Details</TabsTrigger>
              <TabsTrigger value="table">Dependencies</TabsTrigger>
              <TabsTrigger value="trees">Cross-marked trees</TabsTrigger>
            </TabsList>
            <TabsContent value="props">Node Details content.</TabsContent>
            <TabsContent value="table">Dependencies content.</TabsContent>
            <TabsContent value="trees">Cross-marked trees content.</TabsContent>
          </Tabs>
        </div>
      </section>

      <section className="flex flex-col gap-3">
        <h2 className="text-muted-foreground font-mono text-[11px] uppercase">
          Button
        </h2>
        <div className="flex items-center gap-2">
          <Button variant="default">Default</Button>
          <Button variant="secondary">Secondary</Button>
          <Button variant="ghost">Ghost</Button>
          <Button variant="destructive">Destructive</Button>
        </div>
      </section>
    </div>
  );
}
