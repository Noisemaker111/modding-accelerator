import { portMapFrom, siteData } from "@modaccel-site/core/browser";
import type {
  ColourValue,
  Hop,
  StepStatusValue,
} from "@modaccel-site/core/browser";
import type { Html } from "foldkit/html";

import { Message } from "../message.js";
import type { SiteMessage } from "../message.js";
import type { SiteModel } from "../model.js";
import { dot, para, section } from "../ui.js";
import type { H } from "../ui.js";

const { facts: numbers, map } = siteData;

const portMap = portMapFrom(map);

const releases = map.versions;

const count = releases.length;

const newestFirst = releases.toReversed();

const rowHeight = 48;

const gutter = { edge: 58, lanes: [14, 26], route: 44 } as const;

const yOf = (version: string) =>
  (count - 1 - (portMap.position(version) ?? 0)) * rowHeight + rowHeight / 2;

const number = (value: number) => value.toLocaleString("en-US");

const stepInto = (version: string) =>
  map.steps.find((step) => step.to === version);

const launcher = (model: SiteModel) =>
  model.shell === "win" ? "modaccel.cmd" : "./modaccel";

const portCommand = (model: SiteModel, from: string, to: string) =>
  `${launcher(model)} port --root "/path/to/mod" --from ${from} --to ${to} --fix`;

const versionSelect = (
  h: H,
  label: string,
  value: string,
  toMessage: (version: string) => SiteMessage
): Html =>
  h.label(
    [h.Class("pick")],
    [
      h.span([], [label]),
      h.select(
        [h.OnChange(toMessage), h.AriaLabel(`${label} version`)],
        [
          h.option([h.Value(""), h.Selected(value === "")], ["Pick a version"]),
          ...newestFirst.map((release) =>
            h.option(
              [h.Value(release.id), h.Selected(release.id === value)],
              [portMap.isHub(release.id) ? `${release.id} (hub)` : release.id]
            )
          ),
        ]
      ),
    ]
  );

const statusLine = (h: H, status: StepStatusValue): Html =>
  h.span(
    [h.Class("status")],
    [
      dot(h, status.c),
      h.b([], [status.word]),
      h.span([h.Class("why")], [status.why]),
    ]
  );

const hopRow = (model: SiteModel, h: H, hop: Hop): Html =>
  h.tr(
    [],
    [
      h.td(
        [h.DataAttribute("k", "Hop")],
        [
          h.b([], [`${hop.a} → ${hop.b}`]),
          h.span(
            [h.Class("sub")],
            [
              hop.measured
                ? `${hop.legs.length} update${hop.legs.length === 1 ? "" : "s"}`
                : `before ${numbers.firstIndexed}`,
            ]
          ),
        ]
      ),
      h.td([h.DataAttribute("k", "Status")], [statusLine(h, hop.status)]),
      h.td(
        [h.DataAttribute("k", "Size")],
        [
          h.span(
            [h.Class(`size ${hop.size ?? "none"}`)],
            [hop.size ?? "unknown"]
          ),
        ]
      ),
      h.td(
        [h.DataAttribute("k", "Names that break")],
        [hop.measured ? number(hop.broken) : "–"]
      ),
      h.td(
        [h.Class("hi"), h.DataAttribute("k", "modaccel handles")],
        [hop.measured ? number(hop.handled) : "–"]
      ),
      h.td(
        [h.DataAttribute("k", "Left for you")],
        [hop.measured ? number(hop.left) : "–"]
      ),
      h.td(
        [h.DataAttribute("k", "Ready?")],
        [
          h.span(
            [h.Class(`ready ${hop.readiness.kind}`)],
            [hop.readiness.label]
          ),
          ...(hop.measured
            ? [h.code([h.Class("hopcmd")], [portCommand(model, hop.a, hop.b)])]
            : []),
        ]
      ),
    ]
  );

const tally = (h: H, hops: readonly Hop[]): Html => {
  const words: readonly (readonly [ColourValue, string])[] = [
    ["green", "work"],
    ["yellow", "being worked on"],
    ["red", "not working yet or blocked"],
  ];

  return h.p(
    [h.Class("summary")],
    [
      `${hops.length} hop${hops.length === 1 ? "" : "s"}: `,
      ...words.flatMap(([colour, word]) => {
        const many = hops.filter((hop) => hop.status.c === colour).length;

        return many === 0 ? [] : [dot(h, colour), `${many} ${word}  `];
      }),
    ]
  );
};

const routeCard = (model: SiteModel, h: H): Html => {
  if (model.from === "" || model.to === "") {
    return h.p(
      [h.Class("summary")],
      [
        `Pick ${model.from === "" ? "both versions" : "where you want to go"} above, or tap two versions in the list below.`,
      ]
    );
  }

  if (model.from === model.to) {
    return h.p([h.Class("notice")], ["Pick two different versions."]);
  }

  const hops = portMap.route(model.from, model.to);

  const back =
    (portMap.position(model.from) ?? 0) > (portMap.position(model.to) ?? 0);

  const blocked = hops.some((hop) => !hop.measured);
  const command = portCommand(model, model.from, model.to);

  return h.div(
    [],
    [
      tally(h, hops),
      ...(blocked
        ? [
            h.p(
              [h.Class("notice")],
              [
                h.b([], ["Part of this route is blocked. "]),
                numbers.blockedReason,
              ]
            ),
          ]
        : []),
      ...(back
        ? [
            para(
              h,
              "Backport: the rules are written for forward ports, so only renames from the mappings count as handled. What the newer game added is missing on the way back.",
              "small"
            ),
          ]
        : []),
      h.p(
        [h.Class("small")],
        [
          "One command walks the whole route: hub to hub, one joined map, only the rules for the updates it crosses, one build, and each error left named by its update. The rows below are the same route hop by hop, if you would rather check each hop before the next.",
        ]
      ),
      h.div(
        [h.Class("cmdrow")],
        [
          h.code([], [command]),
          h.button(
            [
              h.Type("button"),
              h.OnClick(Message.ClickedCopy({ text: command })),
            ],
            ["Copy"]
          ),
        ]
      ),
      h.div(
        [h.Class("scroll")],
        [
          h.table(
            [h.Class("tbl stack hops")],
            [
              h.thead(
                [],
                [
                  h.tr(
                    [],
                    [
                      "Hop",
                      "Status",
                      "Size",
                      "Names that break",
                      "modaccel handles",
                      "Left for you",
                      "Ready?",
                    ].map((name) =>
                      h.th(name === "modaccel handles" ? [h.Class("hi")] : [], [
                        name,
                      ])
                    )
                  ),
                ]
              ),
              h.tbody(
                [],
                hops.map((hop) => hopRow(model, h, hop))
              ),
            ]
          ),
        ]
      ),
    ]
  );
};

const bracket = (a: string, b: string, lane: number) =>
  `M${gutter.edge} ${yOf(a)} H${lane} V${yOf(b)} H${gutter.edge}`;

const gutterSvg = (model: SiteModel, h: H): Html => {
  const back =
    model.from !== "" &&
    model.to !== "" &&
    (portMap.position(model.from) ?? 0) > (portMap.position(model.to) ?? 0);

  const pairs = map.hubs
    .slice(1)
    .map((hub, index) => [map.hubs[index] ?? hub, hub] as const);

  const base = pairs.map(([a, b], index) => {
    const status = back ? portMap.hopStatus(b, a) : portMap.hopStatus(a, b);

    return h.path(
      [
        h.Class(`base ${status.c}`),
        h.D(bracket(a, b, gutter.lanes[index % 2] ?? gutter.lanes[0])),
      ],
      [h.title([], [`${a} ↔ ${b}: ${status.word}`])]
    );
  });

  const hops =
    model.from === "" || model.to === "" || model.from === model.to
      ? []
      : portMap.route(model.from, model.to);

  const route = hops.map((hop) =>
    h.path(
      [
        h.Class(`route ${hop.status.c}`),
        h.D(bracket(hop.a, hop.b, gutter.route)),
      ],
      [h.title([], [`${hop.a} → ${hop.b}: ${hop.status.word}`])]
    )
  );

  const stops = [...new Set(hops.flatMap((hop) => [hop.a, hop.b]))].map(
    (version) =>
      h.circle(
        [
          h.Class("stop"),
          h.Cx(String(gutter.route)),
          h.Cy(String(yOf(version))),
          h.R("5"),
        ],
        [h.title([], [`Stops at ${version}`])]
      )
  );

  return h.svg(
    [
      h.Class("gutter"),
      h.Width(String(gutter.edge + 4)),
      h.Height(String(count * rowHeight)),
      h.ViewBox(`0 0 ${gutter.edge + 4} ${count * rowHeight}`),
      h.AriaHidden(true),
    ],
    [h.g([], base), h.g([], route), h.g([], stops)]
  );
};

interface Span {
  readonly active: boolean;
  readonly back: boolean;
  readonly high: number;
  readonly low: number;
}

export const spanOf = (model: SiteModel): Span => {
  const from = portMap.position(model.from) ?? -1;
  const to = portMap.position(model.to) ?? -1;
  const active = model.from !== "" && model.to !== "";

  return {
    active,
    back: active && from > to,
    high: Math.max(from, to),
    low: Math.min(from, to),
  };
};

const pickTagOf = (model: SiteModel, version: string) => {
  if (version === model.from) {
    return "from";
  }

  return version === model.to ? "to" : "";
};

const releaseCell = (model: SiteModel, h: H, version: string): Html => {
  const hub = portMap.isHub(version);
  const pickTag = pickTagOf(model, version);

  return h.div(
    [h.Class("rel")],
    [
      h.button(
        [
          h.Type("button"),
          h.Class(`chip${hub ? " hub" : ""}${pickTag === "" ? "" : " picked"}`),
          h.Title(hub ? `${version}: a hub, good to port through` : version),
          h.OnClick(Message.ClickedRelease({ version })),
        ],
        [version]
      ),
      ...(pickTag === ""
        ? []
        : [h.span([h.Class(`picktag ${pickTag}`)], [pickTag])]),
    ]
  );
};

const nextCell = (h: H, index: number, back: boolean): Html => {
  const release = releases[index];

  if (index === 0 || release === undefined) {
    return h.div(
      [h.Class("next")],
      [h.span([h.Class("small")], ["first release"])]
    );
  }

  const status = portMap.stepStatus(index - 1, !back);

  return h.div(
    [h.Class("next")],
    [
      dot(h, status.c),
      h.span([h.Class("word")], [status.word]),
      ...(release.measured ? [h.span([h.Class("why")], [status.why])] : []),
    ]
  );
};

const breaksCell = (h: H, version: string, back: boolean): Html => {
  const step = stepInto(version);

  if (step === undefined) {
    return h.div(
      [h.Class("breaks")],
      [
        h.span(
          [h.Class("small")],
          [portMap.measured(version) ? "–" : "not measured"]
        ),
      ]
    );
  }

  return h.div(
    [h.Class("breaks")],
    [number(back ? step.backListed : step.listed)]
  );
};

const releaseRow = (
  model: SiteModel,
  h: H,
  span: Span,
  index: number
): Html => {
  const release = releases[index];

  if (release === undefined) {
    return null;
  }

  const onRoute = span.active && index >= span.low && index <= span.high;

  return h.div(
    [h.Class(`mrow${onRoute ? " on" : ""}`)],
    [
      releaseCell(model, h, release.id),
      h.div([h.Class("date")], [release.released]),
      nextCell(h, index, span.back),
      breaksCell(h, release.id, span.back),
    ]
  );
};

const legend = (h: H): Html =>
  h.ul(
    [h.Class("legend")],
    [
      h.li(
        [],
        [
          h.span([h.Class("chip hub")], ["1.20.1"]),
          " boxed: a hub, a popular version to port through",
        ]
      ),
      h.li(
        [],
        [
          h.span([h.Class("ln green")], []),
          "a mod was moved across and works in the game",
        ]
      ),
      h.li(
        [],
        [
          h.span([h.Class("ln yellow")], []),
          "it builds, or it is being worked on",
        ]
      ),
      h.li(
        [],
        [h.span([h.Class("ln red")], []), "not tried yet, or not possible yet"]
      ),
      h.li(
        [],
        [
          h.span([h.Class("ln thick")], []),
          "thick line: your route, a dot at each stop",
        ]
      ),
    ]
  );

export const mapPage = (model: SiteModel, h: H): Html => {
  const span = spanOf(model);

  return h.div(
    [],
    [
      h.h1([], ["Porting map"]),
      h.p(
        [h.Class("lede")],
        [
          "Pick the version your mod is on and the one you want. Long ports go through hubs, and each line's colour says what has really been tried.",
        ]
      ),
      h.div(
        [h.Class("card picker")],
        [
          h.div(
            [h.Class("picks")],
            [
              versionSelect(h, "From", model.from, (version) =>
                Message.ChangedFrom({ version })
              ),
              h.button(
                [
                  h.Type("button"),
                  h.Class("swap"),
                  h.AriaLabel("Swap the two versions"),
                  h.OnClick(Message.ClickedSwap()),
                ],
                ["⇄"]
              ),
              versionSelect(h, "To", model.to, (version) =>
                Message.ChangedTo({ version })
              ),
            ]
          ),
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
          ),
          routeCard(model, h),
        ]
      ),
      section(
        h,
        `Every release, ${releases[0]?.id ?? ""} to ${releases.at(-1)?.id ?? ""}`,
        [
          legend(h),
          h.div(
            [h.Class("maphead")],
            [
              h.span([], ["Release"]),
              h.span([h.Class("date")], ["Out"]),
              h.span(
                [],
                [
                  `Update from the release below${span.back ? " (backport)" : ""}`,
                ]
              ),
              h.span([h.Class("breaks")], ["Names that break"]),
            ]
          ),
          h.div(
            [h.Class("maptable")],
            [
              gutterSvg(model, h),
              h.div(
                [h.Class("mrows")],
                newestFirst.map((release) =>
                  releaseRow(model, h, span, portMap.position(release.id) ?? 0)
                )
              ),
            ]
          ),
          para(
            h,
            `Before ${numbers.firstIndexed} Mojang published no official names, so those releases are not measured and modaccel cannot port them yet. Made from coverage/changes/index.json and coverage/ledger.json on ${map.generated}.`,
            "small"
          ),
        ]
      ),
    ]
  );
};
