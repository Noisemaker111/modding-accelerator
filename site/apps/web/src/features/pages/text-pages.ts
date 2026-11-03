import { siteData } from "@modaccel-site/core/browser";
import type { RoadmapStatusValue } from "@modaccel-site/core/browser";
import type { Html } from "foldkit/html";

import { Message } from "../message.js";
import type { SiteModel } from "../model.js";
import {
  commandLine,
  dot,
  inline,
  link,
  para,
  repo,
  repoFile,
  section,
  table,
  tag,
} from "../ui.js";
import type { H } from "../ui.js";
import { ladderCell } from "./features.js";
import { limits } from "./home.js";

const { facts: numbers, help, readme, roadmap, rules, scope } = siteData;

const steps: readonly {
  readonly command: string;
  readonly proves: string;
  readonly not: string;
  readonly title: string;
}[] = [
  {
    command: "support",
    not: "Nothing about your mod yet.",
    proves: "The tool runs on this machine and lists what it supports.",
    title: "Check the tool",
  },
  {
    command: 'setup --root "/path/to/mod"',
    not: "That the mod builds.",
    proves:
      "modaccel can read your Gradle project: its loader, Minecraft version, Java and modules.",
    title: "Read your project",
  },
  {
    command: 'compile --root "/path/to/mod"',
    not: "That anything works when the game runs.",
    proves: "Your code compiles, through a warm build that stays running.",
    title: "Compile",
  },
  {
    command: 'fast --root "/path/to/mod"',
    not: "Anything that needs the game. Only tests that never touch Minecraft run here.",
    proves:
      "Your code compiles and your plain logic tests pass, in about a second when warm.",
    title: "Logic tests",
  },
  {
    command: 'ui-check --root "/path/to/mod"',
    not: "That the feature behaves right. A screen that draws can still be wrong; add --assert METHOD for that.",
    proves:
      "The mod loads in a real game client. You get a 1280×720 picture from Minecraft's own framebuffer to look at.",
    title: "Look at it in the game",
  },
  {
    command: 'world-check --root "/path/to/mod" --world lab',
    not: "Multiplayer. Singleplayer hides data the server never sends.",
    proves:
      "The mod runs inside a loaded save, and your check method passes there.",
    title: "Check it in a world",
  },
  {
    command: 'mp-check --root "/path/to/mod"',
    not: "Every platform: Fabric and NeoForge only so far.",
    proves:
      "A dedicated server and a client both run the mod and talk to each other.",
    title: "Check multiplayer",
  },
  {
    command: 'port --root "/path/to/mod" --from 1.21.5 --to 1.21.6 --fix',
    not: "That the ported mod works. Run the game checks above on the result before you trust it.",
    proves:
      "Your source now builds for the new version, or you get a list of what is left, each tagged with the update that caused it. --undo puts everything back.",
    title: "Port to another version",
  },
];

const launcher = (model: SiteModel) =>
  model.shell === "win" ? "modaccel.cmd" : "./modaccel";

const shellPicker = (model: SiteModel, h: H): Html =>
  h.div(
    [h.Class("switch"), h.Role("group"), h.AriaLabel("Commands for")],
    [
      h.span([h.Class("small")], ["Commands for"]),
      h.button(
        [
          h.Type("button"),
          h.AriaPressed(model.shell === "win" ? "true" : "false"),
          h.OnClick(Message.ClickedShell({ shell: "win" })),
        ],
        ["Windows"]
      ),
      h.button(
        [
          h.Type("button"),
          h.AriaPressed(model.shell === "nix" ? "true" : "false"),
          h.OnClick(Message.ClickedShell({ shell: "nix" })),
        ],
        ["macOS / Linux"]
      ),
    ]
  );

export const startPage = (model: SiteModel, h: H): Html =>
  h.div(
    [],
    [
      h.h1([], ["Get started"]),
      h.p(
        [h.Class("lede")],
        [
          "You need Java 17 or newer and a mod's Gradle source checkout (a folder with ",
          h.code([], ["gradlew"]),
          "). A compiled jar will not work.",
        ]
      ),
      shellPicker(model, h),
      section(h, "1. Install from main", [
        h.p(
          [],
          [
            "Clone the repo. The launcher script at its root runs the tool straight from the source.",
          ]
        ),
        commandLine(
          h,
          `git clone ${repo}.git\ncd modding-accelerator\n${launcher(model)} support`
        ),
        h.p(
          [],
          [
            "Or build the portable jar yourself with ",
            h.code([], ["java tools/cli/Bootstrap.java . package --json"]),
            ". It lands in ",
            h.code([], ["build/distributions/"]),
            ". A ready-made ZIP is on the ",
            link(h, readme.release, "release page"),
            ` (beta ${readme.beta}).`,
          ]
        ),
        para(
          h,
          readme.quickAfter.replace(/ To build from source:.*$/u, ""),
          "small"
        ),
      ]),
      section(h, "2. First commands, and what each one proves", [
        h.ol(
          [h.Class("steps")],
          steps.map((step) =>
            h.li(
              [],
              [
                h.h3([], [step.title]),
                commandLine(h, `${launcher(model)} ${step.command}`),
                h.p([], [h.b([], ["Proves: "]), step.proves]),
                h.p(
                  [h.Class("not")],
                  [h.b([], ["Does not prove: "]), step.not]
                ),
              ]
            )
          )
        ),
      ]),
      section(h, "3. Read the receipt", [
        para(
          h,
          "Every command prints JSON with `--json` and saves a receipt in your mod's `.runs/portable/` folder, with the log, timings and any pictures. Builds and game launches always go through modaccel, so the receipt is the record."
        ),
        h.p(
          [],
          [
            "Next: the ",
            link(h, "/docs", "full command list"),
            ", or pick your versions on the ",
            link(h, "/porting-map", "porting map"),
            ".",
          ]
        ),
      ]),
    ]
  );

export const supportedPage = (h: H): Html =>
  h.div(
    [],
    [
      h.h1([], ["What is supported"]),
      h.p(
        [h.Class("lede")],
        [
          "Supported means a real project passed the rung, not that it should work. Each rung needs the one before it.",
        ]
      ),
      section(h, "The ladder", [
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
        para(h, `Scope ${scope.status}`, "small"),
      ]),
      section(h, "Kinds of projects", [
        table(
          h,
          scope.platforms.head,
          scope.platforms.rows.map((row) =>
            row.map((cell) => h.span([], inline(h, cell)))
          )
        ),
      ]),
      section(h, "Which check proves what", [
        table(
          h,
          scope.sides.head,
          scope.sides.rows.map((row) =>
            row.map((cell) => h.span([], inline(h, cell)))
          )
        ),
      ]),
      section(h, "Not supported, and why", [
        h.ul(
          [h.Class("plain")],
          [
            h.li(
              [],
              [
                dot(h, "red"),
                h.b([], ["Compiled jars. "]),
                "modaccel ports source code. A built mod jar has lost the names and structure a port needs. Bring the Gradle source checkout instead.",
              ]
            ),
            h.li(
              [],
              [
                dot(h, "red"),
                h.b([], ["Plugin ↔ mod conversion. "]),
                "A Paper plugin uses the Bukkit API, while a mod changes the game directly. Converting one into the other is a rewrite, not a port. Hybrid servers already run both side by side.",
              ]
            ),
            h.li(
              [],
              [
                dot(h, "red"),
                h.b([], [`Versions before ${numbers.firstIndexed}. `]),
                numbers.blockedReason,
              ]
            ),
            h.li(
              [],
              [
                dot(h, "yellow"),
                h.b([], ["Changing loader. "]),
                "Fabric to NeoForge and the like is planned, as a separate job from changing version. Nothing is built yet.",
              ]
            ),
            ...limits
              .filter((item) => item.title !== "Source checkouts only")
              .map((item) =>
                h.li(
                  [],
                  [
                    dot(h, "yellow"),
                    h.b([], [`${item.title}. `]),
                    ...inline(h, item.text),
                  ]
                )
              ),
          ]
        ),
      ]),
      section(h, "How a platform earns the next rung", [
        h.ul(
          [h.Class("plain")],
          scope.policy.map((item) =>
            h.li(
              [],
              [
                h.b([], [item.title]),
                item.text === "" ? "" : ": ",
                ...inline(h, item.text.replace(/^, /u, "")),
                ...(item.points.length === 0
                  ? []
                  : [
                      h.ul(
                        [],
                        item.points.map((point) => h.li([], inline(h, point)))
                      ),
                    ]),
              ]
            )
          )
        ),
        h.p([], ["From ", link(h, repoFile("SCOPE.md"), "SCOPE.md"), "."]),
      ]),
    ]
  );

const statusColour: Readonly<
  Record<RoadmapStatusValue, "green" | "yellow" | "plain">
> = {
  doing: "yellow",
  done: "green",
  later: "plain",
  next: "plain",
};

const statusWord: Readonly<Record<RoadmapStatusValue, string>> = {
  doing: "in progress",
  done: "done",
  later: "later",
  next: "next",
};

export const roadmapPage = (h: H): Html =>
  h.div(
    [],
    [
      h.h1([], ["Roadmap"]),
      h.p([h.Class("lede")], ["The goal: ", ...inline(h, roadmap.goal)]),
      ...roadmap.phases.map((phase) => {
        const done = phase.steps.filter(
          (step) => step.status === "done"
        ).length;

        return section(h, `${phase.id}: ${phase.title}`, [
          h.p(
            [h.Class("small")],
            [`${done} of ${phase.steps.length} steps done`]
          ),
          h.ul(
            [h.Class("roadmap")],
            phase.steps.map((step) =>
              h.li(
                [h.Class(step.status)],
                [
                  tag(h, statusWord[step.status], statusColour[step.status]),
                  h.b([], [`Step ${step.id}. `]),
                  ...inline(h, step.text),
                ]
              )
            )
          ),
        ]);
      }),
      section(h, "Decisions so far", [
        table(
          h,
          ["Question", "Answer", "Date"],
          roadmap.decisions.map((item) => [
            h.b([], [item.question]),
            h.span([], inline(h, item.choice)),
            item.date,
          ])
        ),
      ]),
      h.p(
        [],
        [
          "From ",
          link(h, repoFile("ROADMAP.md"), "ROADMAP.md"),
          ". Only the statuses there count as claims.",
        ]
      ),
    ]
  );

const commandText = new Map<string, string>([
  ["compile", "Compile the project through a warm build."],
  [
    "dependencies",
    "Report what each dependency needs for the target version, without editing.",
  ],
  ["discover", "Read the project: loader, game version, Java, modules."],
  ["doctor", "Check the project and the Java runtimes on this machine."],
  ["eval", "Run a line of Java inside the running game."],
  ["fast", "Compile and run logic tests that do not need Minecraft."],
  ["game-status", "Show the game this project has running."],
  ["game-stop", "Stop that game."],
  ["mp-check", "Start a dedicated server and a client, and check both."],
  ["package", "Build the portable modaccel jar and ZIP."],
  ["port", "Move the source to another Minecraft version, or undo a port."],
  ["reload", "Swap recompiled code into the running game."],
  ["self-test", "Check the modaccel install itself."],
  ["setup", "Summarise the project and what modaccel supports for it."],
  ["support", "List what this build of modaccel supports."],
  ["test", "Run the project's own test task."],
  ["ui-check", "Open a screen in a real client and save a picture."],
  ["world-check", "Check the mod inside a loaded save."],
]);

export const docsPage = (h: H): Html =>
  h.div(
    [],
    [
      h.h1([], ["Commands"]),
      h.p(
        [h.Class("lede")],
        [
          "Read from the help text in ",
          h.code([], ["tools/cli/Modaccel.java"]),
          ", so it matches the tool.",
        ]
      ),
      commandLine(h, help.usage),
      section(h, "Every command", [
        table(
          h,
          ["Command", "What it does"],
          help.commands.map((command) => [
            h.code([], [command]),
            commandText.get(command) ?? "",
          ]),
          "commands"
        ),
      ]),
      ...help.topics.map((topic) =>
        h.section(
          [h.Class("block"), h.Id(topic.command)],
          [
            h.h2([], [h.code([], [`modaccel help ${topic.command}`])]),
            commandLine(h, topic.text),
          ]
        )
      ),
      section(h, "For tools and agents", [
        para(
          h,
          "This site answers two questions as an HTTP API and as an MCP server, from the same data as these pages: which route a port takes, and how a tested mod did."
        ),
        commandLine(
          h,
          'POST /api/portRoute   {"from": "1.20.1", "to": "1.21.6"}\nPOST /api/modStatus   {"mod": "AppleSkin"}\nGET  /api/openapi     the OpenAPI description\nPOST /mcp             MCP tools portRoute and modStatus'
        ),
      ]),
      section(
        h,
        "House rules",
        rules.map((item) => para(h, item))
      ),
    ]
  );

export const contributePage = (h: H): Html =>
  h.div(
    [],
    [
      h.h1([], ["Contribute"]),
      h.p(
        [h.Class("lede")],
        [
          "The best thing you can bring is a real mod. Every new mod either proves something works or shows exactly where it breaks.",
        ]
      ),
      section(h, "Bring a mod", [
        h.p(
          [],
          [
            "Open an issue with a link to the mod's source repo. This is what happens next:",
          ]
        ),
        h.ol(
          [h.Class("steps")],
          scope.intake.map((item) =>
            h.li(
              [],
              [
                h.h3([], [item.title]),
                ...(item.text === ""
                  ? []
                  : [
                      para(
                        h,
                        item.text.replace(
                          "Run the `bench-new-mod` skill",
                          "We run the `bench-new-mod` flow"
                        )
                      ),
                    ]),
                ...(item.points.length === 0
                  ? []
                  : [
                      h.ul(
                        [h.Class("plain")],
                        item.points.map((point) => h.li([], inline(h, point)))
                      ),
                    ]),
              ]
            )
          )
        ),
        para(
          h,
          "In plain words: we run the mod's own build and our tool side by side, time both, find the highest rung it passes, and record the result on the bench. If it fails, the smallest fix goes on a branch and the exact blocker goes on the roadmap."
        ),
      ]),
      section(h, "Pull requests", [
        h.ul(
          [h.Class("plain")],
          [
            h.li(
              [],
              [
                "Run builds and game launches through modaccel, never raw Gradle or a hand-started game.",
              ]
            ),
            h.li(
              [],
              [
                "Any port run goes in ",
                h.code([], ["coverage/ledger.json"]),
                ", and the bench and porting map pages are regenerated in the same change. A run that is not in the ledger did not happen.",
              ]
            ),
            h.li(
              [],
              [
                "Show only 1280×720 pictures from Minecraft's framebuffer, never desktop screenshots.",
              ]
            ),
            h.li(
              [],
              [
                "No silent feature removal and no weaker checks to make a port pass.",
              ]
            ),
            h.li(
              [],
              [
                "Report port/build, game start and behaviour separately. A passing build is not proof.",
              ]
            ),
            h.li(
              [],
              [
                "Only statuses in ",
                h.code([], ["ROADMAP.md"]),
                " count as claims.",
              ]
            ),
          ]
        ),
        h.p(
          [],
          [
            "The full rules are in ",
            link(h, repoFile("AGENTS.md"), "AGENTS.md"),
            " and ",
            link(h, repoFile("SCOPE.md"), "SCOPE.md"),
            ". ",
            link(h, `${repo}/issues`, "Open an issue"),
            " or ",
            link(h, `${repo}/pulls`, "send a pull request"),
            ".",
          ]
        ),
      ]),
    ]
  );
