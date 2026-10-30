import { siteData } from "@modaccel-site/core/browser";
import type { Html } from "foldkit/html";

import {
  card,
  cards,
  fact,
  facts,
  inline,
  link,
  para,
  section,
  tag,
} from "../ui.js";
import type { H } from "../ui.js";

const { bench, facts: numbers, readme, scope } = siteData;

const rungOf = [
  { colour: "green", label: "works today" },
  { colour: "green", label: "works today" },
  { colour: "green", label: "works today" },
  { colour: "yellow", label: "some versions" },
] as const;

export const limits = readme.limits.filter(
  (item) => item.title !== "One hop, one loader"
);

export const homePage = (h: H): Html =>
  h.div(
    [],
    [
      h.section(
        [h.Class("hero")],
        [
          h.h1(
            [],
            ["Port Minecraft mods to another version, and check them fast."]
          ),
          h.p(
            [h.Class("lede")],
            [
              "Modding Accelerator (",
              h.code([], ["modaccel"]),
              ") is a command-line tool for people who write mods or plugins from source. It moves a mod to another Minecraft version, and it checks your edits in seconds instead of restarting the game.",
            ]
          ),
          h.div(
            [h.Class("actions")],
            [
              h.a(
                [h.Class("button main"), h.Href("/get-started")],
                ["Get started"]
              ),
              h.a([h.Class("button"), h.Href("/porting-map")], ["Porting map"]),
              h.a([h.Class("button"), h.Href("/bench")], ["See the bench"]),
            ]
          ),
          h.p(
            [h.Class("small")],
            [
              `Beta ${readme.beta}. `,
              link(h, readme.release, "Release notes"),
              ".",
            ]
          ),
        ]
      ),
      facts(h, [
        fact(h, String(bench.facts.mods), "real mods tried"),
        fact(h, String(bench.facts.runs), "port runs recorded"),
        fact(h, String(bench.facts.working), "seen working in the game"),
        fact(h, String(numbers.indexedReleases), "releases diffed"),
        fact(h, String(numbers.rules), "port rules"),
      ]),
      section(h, "Who it is for", [
        para(h, scope.who),
        h.p(
          [],
          [
            "If you keep a Fabric, NeoForge, Forge or Paper project and want it on more Minecraft versions, or you are tired of waiting for the game to restart after every edit, this is for you.",
          ]
        ),
      ]),
      section(h, "The problem", [
        cards(h, [
          card(h, "Porting is slow", [
            h.p(
              [],
              [
                "Each Minecraft update renames and changes thousands of names in the game's code. Moving a mod across a few versions means hundreds of compile errors, fixed by hand, one at a time.",
              ]
            ),
          ]),
          card(h, "Testing is slow", [
            h.p(
              [],
              [
                "Checking a small change usually means a rebuild and a full game restart: 22 to 28 seconds each time, many times an hour.",
              ]
            ),
          ]),
        ]),
      ]),
      section(h, "What it does today", [
        h.ul(
          [h.Class("checklist")],
          readme.use.map((item, index) =>
            h.li(
              [],
              [
                tag(
                  h,
                  rungOf[index]?.label ?? "in progress",
                  rungOf[index]?.colour ?? "yellow"
                ),
                h.b([], [item.title]),
                " ",
                ...inline(h, item.text),
              ]
            )
          )
        ),
        h.p(
          [],
          [
            "A build that passes is not proof a port works. A port only turns green on the ",
            link(h, "/bench", "bench"),
            " once the mod was seen working in a real game.",
          ]
        ),
      ]),
      section(h, "What it does not do yet", [
        h.ul(
          [h.Class("plain")],
          [
            h.li(
              [],
              [
                h.b([], ["Changing loader"]),
                " (for example Fabric to NeoForge) is not built yet. It is a separate job from changing version.",
              ]
            ),
            ...limits.map((item) =>
              h.li([], [h.b([], [item.title]), ": ", ...inline(h, item.text)])
            ),
          ]
        ),
        h.p(
          [],
          [
            "See ",
            link(h, "/supported", "what is supported"),
            " for the full ladder, and the ",
            link(h, "/roadmap", "roadmap"),
            " for what comes next.",
          ]
        ),
      ]),
      section(h, "The goal", [para(h, readme.goal)]),
    ]
  );
