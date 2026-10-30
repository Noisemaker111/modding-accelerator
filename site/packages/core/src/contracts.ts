import { defineContract } from "@modaccel-site/capability/contract";
import { Schema } from "effect";

import { Colour, RunStatus } from "./schema.js";
import { UnknownMod } from "./unknown-mod.js";
import { UnknownVersion } from "./unknown-version.js";

const HopAnswer = Schema.Struct({
  broken: Schema.NullOr(Schema.Number),
  colour: Colour,
  command: Schema.NullOr(Schema.String),
  from: Schema.String,
  handled: Schema.NullOr(Schema.Number),
  left: Schema.NullOr(Schema.Number),
  ranBefore: Schema.String,
  size: Schema.NullOr(Schema.String),
  status: Schema.String,
  to: Schema.String,
  updates: Schema.Number,
  why: Schema.String,
});

export const RouteAnswer = Schema.Struct({
  backport: Schema.Boolean,
  command: Schema.String,
  from: Schema.String,
  hops: Schema.Array(HopAnswer),
  map: Schema.String,
  to: Schema.String,
});

export type RouteAnswerValue = typeof RouteAnswer.Type;

export const ModAnswer = Schema.Struct({
  furthest: Schema.NullOr(
    Schema.Struct({
      from: Schema.String,
      status: RunStatus,
      to: Schema.String,
      word: Schema.String,
    })
  ),
  loaders: Schema.Array(Schema.String),
  mod: Schema.String,
  runs: Schema.Array(
    Schema.Struct({
      date: Schema.String,
      detail: Schema.String,
      from: Schema.String,
      loader: Schema.String,
      status: RunStatus,
      to: Schema.String,
      word: Schema.String,
    })
  ),
  versions: Schema.Array(Schema.String),
});

export type ModAnswerValue = typeof ModAnswer.Type;

export const portRouteContract = defineContract("portRoute", {
  annotations: { idempotent: true, readOnly: true },
  description:
    "The hub-to-hub route modaccel takes from one Minecraft Java release to another, with what was really seen working on each hop",
  failure: UnknownVersion,
  input: Schema.Struct({
    from: Schema.String.annotate({
      description: "The release the mod is on, for example 1.20.1",
    }),
    to: Schema.String.annotate({
      description: "The release to port it to, for example 1.21.6",
    }),
  }),
  output: RouteAnswer,
});

export const modStatusContract = defineContract("modStatus", {
  annotations: { idempotent: true, readOnly: true },
  description:
    "Every recorded modaccel port of one tested mod, and the furthest it got",
  failure: UnknownMod,
  input: Schema.Struct({
    mod: Schema.String.annotate({
      description: "A tested mod's name, for example AppleSkin",
    }),
  }),
  output: ModAnswer,
});
