import { toHttpApi } from "@modaccel-site/capability/http-api";
import { toToolkit } from "@modaccel-site/capability/toolkit";
import { capabilities } from "@modaccel-site/core";
import { Effect, FileSystem, Layer, Path } from "effect";
import { McpProtocol, McpServer } from "effect/ai";
import { Etag, HttpPlatform, HttpRouter } from "effect/http";
import { HttpApiBuilder } from "effect/http-api";
import {
  Rendered,
  Responded,
  renderToString,
} from "foldkit/experimental/server";

import { pages, Flags } from "./features/model.js";
import type { PageKey } from "./features/model.js";
import { init } from "./features/update.js";
import { view } from "./features/view.js";

export { renderDocument } from "./server/document.js";

export const prerenderPaths = pages.map((page) => page.path);

const api = toHttpApi("modaccel", capabilities, { prefix: "/api" });

const toolkit = toToolkit(capabilities);

const platform = Layer.mergeAll(
  Path.layer,
  Etag.layerWeak,
  HttpPlatform.layer
).pipe(Layer.provideMerge(FileSystem.layerNoop({})));

const routes = Layer.mergeAll(
  HttpApiBuilder.layer(api.api, { openapiPath: "/api/openapi" }).pipe(
    Layer.provide(api.layer)
  ),
  McpServer.toolkit(toolkit.toolkit).pipe(
    Layer.provide(toolkit.layer),
    Layer.provide(
      McpServer.layerHttp({
        description:
          "Ask Modding Accelerator which route a Minecraft mod port takes, and how a tested mod did.",
        name: "modaccel",
        path: "/mcp",
        protocols: [McpProtocol.v2025_11_25, McpProtocol.v2025_06_18],
        version: "0.1.0",
      })
    )
  )
).pipe(Layer.provide(platform));

const surface = HttpRouter.toWebHandler(routes, { disableLogger: true });

const isSurface = (pathname: string) =>
  pathname === "/mcp" || pathname.startsWith("/api/");

const findPage = (pathname: string): PageKey | undefined => {
  const route = pathname === "/" ? pathname : pathname.replace(/\/$/u, "");

  return pages.find((page) => page.path === route)?.id;
};

const renderSitePage = Effect.fn("site.renderPage")(function* renderSitePage(
  request: Request
) {
  const { pathname } = new URL(request.url);

  if (isSurface(pathname)) {
    const response = yield* Effect.promise(
      // @effect-diagnostics-next-line asyncFunction:off -- The Effect web handler answers with a Promise.
      async () => await surface.handler(request)
    );

    return Responded(response);
  }

  const page = findPage(pathname);

  if (page === undefined) {
    return Responded(
      new Response(
        '<!doctype html><meta charset="utf-8"><title>Not found</title><p>No page here. <a href="/">Go to the home page</a>.</p>',
        { headers: { "Content-Type": "text/html; charset=utf-8" }, status: 404 }
      )
    );
  }

  const application = yield* renderToString(
    { Flags, init, view },
    { flags: { page } }
  );

  return Rendered(application);
});

// @effect-diagnostics-next-line asyncFunction:off -- Foldkit awaits the Web request boundary.
export const renderPage = async (request: Request) =>
  await Effect.runPromise(renderSitePage(request));
