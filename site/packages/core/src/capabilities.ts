import { implement } from "@modaccel-site/capability/implement";
import { Effect } from "effect";

import { modStatusContract, portRouteContract } from "./contracts.js";
import { portMapFrom } from "./route.js";
import { siteData } from "./site-data.js";
import { UnknownMod } from "./unknown-mod.js";
import { UnknownVersion } from "./unknown-version.js";

const portMap = portMapFrom(siteData.map);

const wordOf = (status: string) => siteData.rule.word[status] ?? status;

const portCommand = (from: string, to: string) =>
  `modaccel port --root <your mod folder> --from ${from} --to ${to} --fix`;

const knownVersion = (version: string) =>
  portMap.position(version) === undefined
    ? Effect.fail(
        new UnknownVersion({
          message: `${version} is not a Minecraft Java release in the change index (${portMap.versions[0] ?? ""} to ${portMap.versions.at(-1) ?? ""}).`,
          version,
        })
      )
    : Effect.succeed(version);

export const portRoute = implement(portRouteContract, (input) =>
  Effect.gen(function* portRouteHandler() {
    const from = yield* knownVersion(input.from);
    const to = yield* knownVersion(input.to);

    if (from === to) {
      return yield* new UnknownVersion({
        message: "Pick two different releases.",
        version: to,
      });
    }

    const hops = portMap.route(from, to).map((hop) => ({
      broken: hop.measured ? hop.broken : null,
      colour: hop.status.c,
      command: hop.measured ? portCommand(hop.a, hop.b) : null,
      from: hop.a,
      handled: hop.measured ? hop.handled : null,
      left: hop.measured ? hop.left : null,
      ranBefore: hop.readiness.label,
      size: hop.size,
      status: hop.status.word,
      to: hop.b,
      updates: hop.legs.length,
      why: hop.status.why,
    }));

    return {
      backport: (portMap.position(to) ?? 0) < (portMap.position(from) ?? 0),
      command: portCommand(from, to),
      from,
      hops,
      map: `/porting-map#from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`,
      to,
    };
  })
);

const testedMods = siteData.bench.tested.map((row) => row.mod);

export const modStatus = implement(modStatusContract, (input) =>
  Effect.gen(function* modStatusHandler() {
    const row = siteData.bench.tested.find(
      (one) => one.mod.toLowerCase() === input.mod.toLowerCase()
    );

    if (row === undefined) {
      return yield* new UnknownMod({
        known: testedMods,
        message: `${input.mod} is not one of the tested mods.`,
        mod: input.mod,
      });
    }

    return {
      furthest:
        row.best === null
          ? null
          : { ...row.best, word: wordOf(row.best.status) },
      loaders: row.loaders,
      mod: row.mod,
      runs: siteData.bench.runs
        .filter((run) => run.mod === row.mod)
        .map((run) => ({
          date: run.date,
          detail: run.detail,
          from: run.from,
          loader: run.loader,
          status: run.status,
          to: run.to,
          word: wordOf(run.status),
        })),
      versions: row.versions,
    };
  })
);

export const capabilities = [portRoute, modStatus] as const;
