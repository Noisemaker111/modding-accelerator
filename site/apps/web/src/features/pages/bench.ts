import { siteData } from "@modaccel-site/core/browser";
import type { ColourValue } from "@modaccel-site/core/browser";
import type { Html } from "foldkit/html";

import { dot, fact, facts, para, section, table } from "../ui.js";
import type { H } from "../ui.js";

const { bench, rule } = siteData;

const status = (h: H, key: string): Html =>
  h.span(
    [h.Class("status")],
    [dot(h, rule.colour[key] ?? "yellow"), rule.word[key] ?? key]
  );

const pairColour = (value: string): ColourValue => {
  if (value === "working" || value === "behaviour" || value === "load") {
    return "green";
  }

  return value === "build" || value === "doing" ? "yellow" : "red";
};

const loaderGrid = (h: H): Html =>
  h.div(
    [h.Class("scroll")],
    [
      h.table(
        [h.Class("tbl grid")],
        [
          h.thead(
            [],
            [
              h.tr(
                [],
                [
                  h.th([], ["From ↓ to →"]),
                  ...bench.loaders.map((loader) => h.th([], [loader])),
                ]
              ),
            ]
          ),
          h.tbody(
            [],
            bench.loaderKeys.map((fromKey, row) =>
              h.tr(
                [],
                [
                  h.th([], [bench.loaders[row] ?? fromKey]),
                  ...bench.loaderKeys.map((toKey) => {
                    if (fromKey === toKey) {
                      return h.td([h.Class("same")], ["–"]);
                    }

                    const pair = bench.loaderPairs.find(
                      (one) => one.from === fromKey && one.to === toKey
                    );

                    return h.td(
                      [h.Title(pair?.detail ?? "Not started.")],
                      [
                        dot(h, pairColour(pair?.status ?? "unbuilt")),
                        pair === undefined || pair.status === "unbuilt"
                          ? "Not built"
                          : pair.status,
                      ]
                    );
                  }),
                ]
              )
            )
          ),
        ]
      ),
    ]
  );

export const benchPage = (h: H): Html =>
  h.div(
    [],
    [
      h.h1([], ["Bench"]),
      h.p(
        [h.Class("lede")],
        [
          "Real mods, real ports, and how far each one got. A port is green only after the mod was seen working in the game.",
        ]
      ),
      facts(h, [
        fact(h, String(bench.facts.mods), "mods tried"),
        fact(h, String(bench.facts.versions), "Minecraft versions"),
        fact(h, String(bench.facts.runs), "port runs"),
        fact(h, String(bench.facts.working), "working in the game"),
      ]),
      section(h, "What we test", [
        table(
          h,
          ["Mod", "How", "Loaders", "Versions", "Best result"],
          bench.tested.map((mod) => [
            h.b([], [mod.mod]),
            mod.what,
            mod.loaders.join(", "),
            mod.versions.join(", "),
            mod.best === null
              ? h.span(
                  [h.Class("small")],
                  [mod.errors === null ? "no port yet" : `${mod.errors} errors`]
                )
              : h.span(
                  [],
                  [
                    status(h, mod.best.status),
                    h.span(
                      [h.Class("sub")],
                      [`${mod.best.from} → ${mod.best.to}`]
                    ),
                  ]
                ),
          ])
        ),
      ]),
      section(h, "Latest runs", [
        table(
          h,
          ["Mod", "Port", "Result", "What happened"],
          bench.runs.map((run) => [
            h.span(
              [],
              [
                h.b([], [run.mod]),
                h.span(
                  [h.Class("sub")],
                  [
                    `${run.loader}${run.backport ? ", backport" : ""}, ${run.date}`,
                  ]
                ),
              ]
            ),
            `${run.from} → ${run.to}`,
            status(h, run.status),
            h.span([h.Class("detail")], [run.detail]),
          ]),
          "runs"
        ),
      ]),
      section(h, "Earlier runs, by hand, for comparison", [
        table(
          h,
          ["Mod", "How", "Port", "Errors", "First build", "After the bump"],
          bench.earlier.map((run) => [
            h.b([], [run.mod]),
            run.how,
            run.port,
            run.errors === null ? "–" : String(run.errors),
            run.firstBuild,
            run.afterBump ?? "–",
          ])
        ),
      ]),
      section(h, "Speed over time", [
        para(h, `Machine: ${bench.speed.machine}.`, "small"),
        ...bench.speed.groups.map((group) =>
          h.div(
            [h.Class("speed")],
            [
              h.h3([], [group.name]),
              table(
                h,
                ["What", ...bench.speed.dates],
                group.rows.map((row) => [
                  row.name,
                  ...bench.speed.dates.map((date) => {
                    const cell = row.cells.find((one) => one.date === date);

                    return cell === undefined
                      ? h.span([h.Class("small")], ["–"])
                      : h.span(
                          [],
                          [
                            h.b([], [cell.text]),
                            cell.note === ""
                              ? ""
                              : h.span([h.Class("sub")], [cell.note]),
                          ]
                        );
                  }),
                ])
              ),
            ]
          )
        ),
      ]),
      section(h, "Changing loader", [
        para(
          h,
          "Moving a mod from one loader to another is a separate job from changing version. None of these pairs is built yet."
        ),
        loaderGrid(h),
      ]),
      section(h, "What counts as working", [
        h.ul(
          [h.Class("plain")],
          Object.keys(rule.word)
            .toSorted((a, b) => (rule.rank[b] ?? 0) - (rule.rank[a] ?? 0))
            .map((key) =>
              h.li([], [status(h, key), `: ${rule.said[key] ?? ""}.`])
            )
        ),
        para(
          h,
          "Green needs the mod loaded in a real game. Yellow is a packaged build that has not been loaded yet. Red stopped with errors that are named in the run."
        ),
      ]),
    ]
  );
