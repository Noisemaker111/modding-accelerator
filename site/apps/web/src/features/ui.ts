import type { ColourValue } from "@modaccel-site/core/browser";
import type { Html, HtmlBuilder } from "foldkit/html";

import type { SiteMessage } from "./message.js";

export type H = HtmlBuilder<SiteMessage>;

type Child = Html | string;

const inlinePattern = /(?<part>`[^`]+`|\*\*[^*]+\*\*)/u;

export const inline = (h: H, text: string): Child[] =>
  text
    .split(inlinePattern)
    .filter((part) => part !== "")
    .map((part) => {
      if (part.startsWith("`") && part.endsWith("`")) {
        return h.code([], [part.slice(1, -1)]);
      }

      if (part.startsWith("**") && part.endsWith("**")) {
        return h.strong([], [part.slice(2, -2)]);
      }

      return part;
    });

export const para = (h: H, text: string, className = ""): Html =>
  h.p(className === "" ? [] : [h.Class(className)], inline(h, text));

export const dot = (h: H, colour: ColourValue): Html =>
  h.span([h.Class(`dot ${colour}`), h.AriaHidden(true)], []);

export const link = (h: H, href: string, label: string): Html =>
  h.a([h.Href(href)], [label]);

export const section = (
  h: H,
  title: string,
  children: readonly Child[]
): Html => h.section([h.Class("block")], [h.h2([], [title]), ...children]);

export const table = (
  h: H,
  head: readonly string[],
  rows: readonly (readonly Child[])[],
  className = ""
): Html =>
  h.div(
    [h.Class("scroll")],
    [
      h.table(
        [h.Class(`tbl stack ${className}`.trim())],
        [
          h.thead(
            [],
            [
              h.tr(
                [],
                head.map((cell) => h.th([], [cell]))
              ),
            ]
          ),
          h.tbody(
            [],
            rows.map((row) =>
              h.tr(
                [],
                row.map((cell, index) =>
                  h.td([h.DataAttribute("k", head[index] ?? "")], [cell])
                )
              )
            )
          ),
        ]
      ),
    ]
  );

export const fact = (h: H, value: string, label: string): Html =>
  h.div([h.Class("fact")], [h.b([], [value]), h.span([], [label])]);

export const facts = (h: H, items: readonly Html[]): Html =>
  h.div([h.Class("facts")], items);

export const cards = (h: H, items: readonly Html[]): Html =>
  h.div([h.Class("cards")], items);

export const card = (h: H, title: string, children: readonly Child[]): Html =>
  h.div([h.Class("card")], [h.h3([], [title]), ...children]);

export const commandLine = (h: H, text: string): Html =>
  h.pre([h.Class("cmd")], [h.code([], [text])]);

export const tag = (h: H, text: string, colour: ColourValue | "plain"): Html =>
  h.span([h.Class(`tag ${colour}`)], [text]);

export const repo = "https://github.com/Noisemaker111/modding-accelerator";

export const repoFile = (file: string) => `${repo}/blob/main/${file}`;
