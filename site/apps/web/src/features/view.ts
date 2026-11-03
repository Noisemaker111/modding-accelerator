import { siteData } from "@modaccel-site/core/browser";
import type { Document, Html } from "foldkit/html";

import { Message } from "./message.js";
import { pageById } from "./model.js";
import type { SiteModel, PageKey } from "./model.js";
import { benchPage } from "./pages/bench.js";
import { checksPage, platformsPage, portingPage } from "./pages/features.js";
import { homePage } from "./pages/home.js";
import { mapPage } from "./pages/map.js";
import {
  contributePage,
  docsPage,
  roadmapPage,
  startPage,
  supportedPage,
} from "./pages/text-pages.js";
import { repo } from "./ui.js";
import type { H } from "./ui.js";

const nav: readonly { readonly id: PageKey; readonly label: string }[] = [
  { id: "start", label: "Get started" },
  { id: "checks", label: "Features" },
  { id: "supported", label: "Supported" },
  { id: "bench", label: "Bench" },
  { id: "map", label: "Porting map" },
  { id: "roadmap", label: "Roadmap" },
  { id: "docs", label: "Docs" },
  { id: "contribute", label: "Contribute" },
];

const featureIds: ReadonlySet<PageKey> = new Set<PageKey>([
  "checks",
  "porting",
  "platforms",
]);

const current = (page: PageKey, id: PageKey) =>
  page === id || (id === "checks" && featureIds.has(page));

const header = (model: SiteModel, h: H): Html =>
  h.header(
    [h.Class("top")],
    [
      h.div(
        [h.Class("bar")],
        [
          h.a([h.Class("brand"), h.Href("/")], ["modaccel"]),
          h.button(
            [
              h.Class("theme"),
              h.Type("button"),
              h.AriaLabel("Switch light or dark"),
              h.OnClick(Message.ClickedTheme()),
            ],
            [
              h.span([h.Class("to-dark")], ["Dark"]),
              h.span([h.Class("to-light")], ["Light"]),
            ]
          ),
        ]
      ),
      h.nav(
        [h.AriaLabel("Pages")],
        nav.map((item) =>
          h.a(
            [
              h.Href(pageById(item.id).path),
              ...(current(model.page, item.id) ? [h.AriaCurrent("page")] : []),
            ],
            [item.label]
          )
        )
      ),
    ]
  );

const footer = (h: H): Html =>
  h.footer(
    [h.Class("foot")],
    [
      h.p(
        [],
        [
          "Every number here is read from the repo at build time (",
          h.code([], ["coverage/ledger.json"]),
          ", ",
          h.code([], ["coverage/changes/index.json"]),
          ", ",
          h.code([], ["SCOPE.md"]),
          ", ",
          h.code([], ["ROADMAP.md"]),
          ") at commit ",
          h.a([h.Href(`${repo}/commit/${siteData.commit}`)], [siteData.commit]),
          ". ",
          h.a([h.Href(repo)], ["Source on GitHub"]),
          ".",
        ]
      ),
    ]
  );

const body = (model: SiteModel, h: H): Html => {
  switch (model.page) {
    case "home": {
      return homePage(h);
    }

    case "start": {
      return startPage(model, h);
    }

    case "checks": {
      return checksPage(h);
    }

    case "porting": {
      return portingPage(h);
    }

    case "platforms": {
      return platformsPage(h);
    }

    case "supported": {
      return supportedPage(h);
    }

    case "bench": {
      return benchPage(h);
    }

    case "map": {
      return mapPage(model, h);
    }

    case "roadmap": {
      return roadmapPage(h);
    }

    case "docs": {
      return docsPage(h);
    }

    case "contribute": {
      return contributePage(h);
    }

    default: {
      return homePage(h);
    }
  }
};

export const view = (model: SiteModel, h: H): Document => {
  const page = pageById(model.page);

  return {
    body: h.div(
      [h.Class("site")],
      [header(model, h), h.main([h.Class("page")], [body(model, h)]), footer(h)]
    ),
    lang: "en",
    title:
      page.id === "home"
        ? "Modding Accelerator: port and check Minecraft mods"
        : `${page.title} · Modding Accelerator`,
  };
};
