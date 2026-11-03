import { siteData } from "@modaccel-site/core/browser";
import type { Html } from "foldkit/html";

import type { PageKey } from "../model.js";
import {
  card,
  cards,
  commandLine,
  dot,
  fact,
  facts,
  inline,
  link,
  para,
  section,
  table,
} from "../ui.js";
import type { H } from "../ui.js";

const { bench, facts: numbers, map, rule, scope } = siteData;

const tabs: readonly {
  readonly id: PageKey;
  readonly label: string;
  readonly path: string;
}[] = [
  { id: "checks", label: "Fast checks", path: "/features/checks" },
  { id: "porting", label: "Porting", path: "/features/porting" },
  { id: "platforms", label: "Platforms", path: "/features/platforms" },
];

const featureTabs = (h: H, active: PageKey): Html =>
  h.nav(
    [h.Class("tabs"), h.AriaLabel("Features")],
    tabs.map((item) =>
      h.a(
        [
          h.Href(item.path),
          ...(item.id === active ? [h.AriaCurrent("page")] : []),
        ],
        [item.label]
      )
    )
  );

const speedCell = (name: string) => {
  const row = bench.speed.groups
    .flatMap((group) => group.rows)
    .find((one) => one.name === name);

  return row?.cells.at(-1);
};

const speed = (name: string) => speedCell(name)?.text ?? "not measured";

const speedRows = (h: H, names: readonly string[]) =>
  table(
    h,
    ["What", "Time", "Measured on"],
    names.map((name) => {
      const cell = speedCell(name);

      return [
        name,
        h.b([], [cell?.text ?? "not measured"]),
        cell === undefined
          ? ""
          : `${cell.date}${cell.note === "" ? "" : `, ${cell.note}`}`,
      ];
    })
  );

export const checksPage = (h: H): Html =>
  h.div(
    [],
    [
      featureTabs(h, "checks"),
      h.h1([], ["Fast checks"]),
      h.p(
        [h.Class("lede")],
        [
          "Most of a modder's day is edit, build, start the game, look. modaccel keeps one game running and swaps your change in, so each look takes seconds.",
        ]
      ),
      facts(h, [
        fact(h, speed("Logic tests, no Minecraft"), "logic tests, no game"),
        fact(
          h,
          speed("Screen change and a screenshot"),
          "screen edit and a screenshot"
        ),
        fact(
          h,
          speed("Block entity in a live world"),
          "block change in a live world"
        ),
        fact(h, speed("Client start, cold"), "a cold game start, for scale"),
      ]),
      section(h, "Logic tests in seconds", [
        para(
          h,
          "`modaccel fast` compiles your code and runs the tests that do not need Minecraft. A warm repo answers in about a second. Use it for maths, parsing, config and anything else you can test without the game."
        ),
        commandLine(h, 'modaccel fast --root "/path/to/mod"'),
      ]),
      section(h, "Game checks with real screenshots", [
        para(
          h,
          "`modaccel ui-check` starts or reuses a real game client, opens the screen you name, and saves a 1280×720 picture straight from Minecraft's own framebuffer. `world-check` does the same inside a loaded save, and can call a check method of yours. `reload` swaps changed method bodies into the running game."
        ),
        commandLine(
          h,
          'modaccel ui-check --root "/path/to/mod" --screen net.minecraft.client.gui.screen.TitleScreen'
        ),
        para(
          h,
          "Pictures are saved for a person or an agent to look at. Nothing compares them with last week's pixels yet. A screen that draws is not the same as a feature that works, so behaviour checks use `--assert METHOD`."
        ),
      ]),
      section(h, "Multiplayer check", [
        para(
          h,
          "`modaccel mp-check` starts a dedicated server and a hidden client that joins it. Singleplayer hides bugs where the server never sends data to the player; this is the check that shows them."
        ),
        commandLine(h, 'modaccel mp-check --root "/path/to/mod"'),
        h.p(
          [],
          [
            dot(h, "yellow"),
            "Partly done: it works for Fabric and NeoForge, not yet for Forge, Stonecutter or Paper. See ",
            link(h, "/supported", "what is supported"),
            ".",
          ]
        ),
      ]),
      section(h, "All measured times", [
        speedRows(h, [
          "Logic tests, no Minecraft",
          "Screen change and a screenshot",
          "Block entity in a live world",
          "Scripted scenario and a screenshot",
          "One-file edit, modaccel fast",
          "One-file edit, the mod's own build",
          "Client start, cold",
          "Dedicated server start",
        ]),
        para(
          h,
          `Machine: ${bench.speed.machine}. The full history is on the bench page.`,
          "small"
        ),
      ]),
    ]
  );

const stonecutterLine = `Rules learned from ${numbers.stonecutterMods} open-source multi-version mods (${numbers.stonecutterSwitches.toLocaleString("en-US")} version switches, ${numbers.stonecutterRules} rules).`;

export const portingPage = (h: H): Html =>
  h.div(
    [],
    [
      featureTabs(h, "porting"),
      h.h1([], ["Porting"]),
      h.p(
        [h.Class("lede")],
        [
          "One command moves a mod's source to another Minecraft version: ",
          h.code([], ["modaccel port --from 1.21.5 --to 1.21.6 --fix"]),
          ". It does the renames, applies known fixes, builds once, and names what is left.",
        ]
      ),
      facts(h, [
        fact(
          h,
          String(numbers.indexedReleases),
          `releases diffed, ${numbers.firstIndexed} to ${numbers.lastIndexed}`
        ),
        fact(h, String(map.hubs.length), "hub versions to route through"),
        fact(h, String(numbers.rules), "port rules"),
        fact(
          h,
          String(numbers.stonecutterRules),
          "rules learned from real mods"
        ),
      ]),
      section(h, "The change index", [
        para(
          h,
          `Every neighbouring pair of releases from ${numbers.firstIndexed} to ${numbers.lastIndexed} is diffed in Mojang's own names: which classes, methods and fields were renamed, moved or removed. That is ${numbers.indexedReleases} releases in ${numbers.families} families, ${numbers.measuredFamilies} of them measured.`
        ),
      ]),
      section(h, "Hub routing", [
        para(
          h,
          "Long ports go through popular versions (hubs), the way many mods already do. A port from 1.20.1 to 1.21.6 walks 1.20.1 → 1.20.2 → … → 1.21.6 and joins every update's renames into one map, so you build once. Only the rules for the updates it crosses run, and each error left is tagged with the update that caused it."
        ),
        h.p(
          [],
          [
            "Try any pair on the ",
            link(h, "/porting-map", "porting map"),
            ". Each line's colour says what has really been tried.",
          ]
        ),
      ]),
      section(h, "Rules mined from Stonecutter mods", [
        para(
          h,
          "Stonecutter mods keep one source for many versions, with switches like `//? if >=1.21.2`. Each switch is a person showing the code before and after an update. modaccel turns changes that repeat across mods into rules, and a rule runs only on its own update, and only when the compiler reports the old name."
        ),
        para(h, stonecutterLine),
      ]),
      section(h, "Undo", [
        para(
          h,
          "Every port is a transaction. `--undo` puts your source back exactly as it was, including any dependency that was disabled for the port."
        ),
        commandLine(
          h,
          'modaccel port --root "/path/to/mod" --undo TRANSACTION'
        ),
      ]),
      section(h, "What has been seen working", [
        table(
          h,
          ["Mod", "Loader", "Port", "Result"],
          bench.runs
            .filter(
              (run) => run.status === "behaviour" || run.status === "load"
            )
            .map((run) => [
              run.mod,
              run.loader,
              `${run.from} → ${run.to}`,
              h.span(
                [],
                [
                  dot(h, rule.colour[run.status] ?? "yellow"),
                  rule.word[run.status] ?? run.status,
                ]
              ),
            ])
        ),
        h.p(
          [],
          [
            "Bigger jumps still stop with named errors. Every run, good or bad, is on the ",
            link(h, "/bench", "bench"),
            ".",
          ]
        ),
      ]),
    ]
  );

const ladderWord = (cell: string) => {
  const lowered = cell.toLowerCase();

  if (lowered.startsWith("yes") || lowered.startsWith("via")) {
    return "green";
  }

  if (lowered.startsWith("partial") || lowered.startsWith("needs")) {
    return "yellow";
  }

  if (lowered.startsWith("n/a") || lowered.startsWith("later")) {
    return "plain";
  }

  return "red";
};

export const ladderCell = (h: H, cell: string): Html =>
  h.span([h.Class(`rung ${ladderWord(cell)}`)], inline(h, cell));

export const platformsPage = (h: H): Html =>
  h.div(
    [],
    [
      featureTabs(h, "platforms"),
      h.h1([], ["Platforms"]),
      h.p(
        [h.Class("lede")],
        [
          "modaccel works on Gradle source projects. Fabric and NeoForge come first, then Forge for older modpacks, then Paper for servers.",
        ]
      ),
      section(h, "What each kind of project is", [
        table(
          h,
          scope.platforms.head,
          scope.platforms.rows.map((row) =>
            row.map((cell) => h.span([], inline(h, cell)))
          )
        ),
      ]),
      section(h, "Where each platform is today", [
        table(
          h,
          scope.ladder.head,
          scope.ladder.rows.map((row) =>
            row.map((cell, index) =>
              index < 2 ? h.span([], inline(h, cell)) : ladderCell(h, cell)
            )
          ),
          "ladder"
        ),
        para(
          h,
          "`yes` means a real project passed that rung. `partial` means some of it works. Rungs build on each other: a port is only trusted after the run check passes."
        ),
      ]),
      section(h, "Mods tried, by loader", [
        cards(
          h,
          bench.loaders.map((loader) => {
            const mods = bench.tested.filter((mod) =>
              mod.loaders.includes(loader)
            );

            return card(h, loader, [
              mods.length === 0
                ? h.p([], ["None tried yet."])
                : h.ul(
                    [h.Class("plain")],
                    mods.map((mod) =>
                      h.li(
                        [],
                        [
                          dot(
                            h,
                            mod.best === null
                              ? "red"
                              : (rule.colour[mod.best.status] ?? "yellow")
                          ),
                          h.b([], [mod.mod]),
                          mod.best === null
                            ? ` ${mod.what}`
                            : ` ${mod.best.from} → ${mod.best.to}: ${rule.said[mod.best.status] ?? mod.best.status}`,
                        ]
                      )
                    )
                  ),
            ]);
          })
        ),
        para(
          h,
          "Stonecutter (multi-version) and Paper projects are tried too; see the Supported page. Quilt rides on Fabric.",
          "small"
        ),
      ]),
    ]
  );
