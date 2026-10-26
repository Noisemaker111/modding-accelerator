import { Schema } from "effect";

export const pages = [
  { id: "home", path: "/", title: "Modding Accelerator" },
  { id: "start", path: "/get-started", title: "Get started" },
  { id: "checks", path: "/features/checks", title: "Fast checks" },
  { id: "porting", path: "/features/porting", title: "Porting" },
  { id: "platforms", path: "/features/platforms", title: "Platforms" },
  { id: "supported", path: "/supported", title: "What is supported" },
  { id: "bench", path: "/bench", title: "Bench" },
  { id: "map", path: "/porting-map", title: "Porting map" },
  { id: "roadmap", path: "/roadmap", title: "Roadmap" },
  { id: "docs", path: "/docs", title: "Commands" },
  { id: "contribute", path: "/contribute", title: "Contribute" },
] as const;

export type PageInfo = (typeof pages)[number];

export const PageId = Schema.Literals(pages.map((page) => page.id));

export type PageKey = typeof PageId.Type;

export const Shell = Schema.Literals(["win", "nix"]);

export type ShellKind = typeof Shell.Type;

export const Flags = Schema.Struct({ page: PageId });

export type SiteFlags = typeof Flags.Type;

export const Model = Schema.Struct({
  from: Schema.String,
  page: PageId,
  shell: Shell,
  to: Schema.String,
});

export type SiteModel = typeof Model.Type;

export const defaultFrom = "1.20.1";

export const defaultTo = "1.21.6";

export const pageById = (id: PageKey): PageInfo =>
  pages.find((page) => page.id === id) ?? pages[0];
