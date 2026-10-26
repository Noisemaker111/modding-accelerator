import { Schema } from "effect";

export const Colour = Schema.Literals(["green", "yellow", "red"]);

export type ColourValue = typeof Colour.Type;

export const RunStatus = Schema.Literals([
  "behaviour",
  "load",
  "build",
  "stop",
]);

export type RunStatusValue = typeof RunStatus.Type;

export const StepStatus = Schema.Struct({
  c: Colour,
  run: Schema.optionalKey(Schema.String),
  why: Schema.String,
  word: Schema.String,
});

export type StepStatusValue = typeof StepStatus.Type;

export const Rule = Schema.Struct({
  colour: Schema.Record(Schema.String, Colour),
  rank: Schema.Record(Schema.String, Schema.Number),
  said: Schema.Record(Schema.String, Schema.String),
  word: Schema.Record(Schema.String, Schema.String),
});

const Tested = Schema.Struct({
  best: Schema.NullOr(
    Schema.Struct({ from: Schema.String, status: RunStatus, to: Schema.String })
  ),
  date: Schema.String,
  errors: Schema.NullOr(Schema.Number),
  loaders: Schema.Array(Schema.String),
  mod: Schema.String,
  versions: Schema.Array(Schema.String),
  what: Schema.String,
});

export const BenchRun = Schema.Struct({
  backport: Schema.Boolean,
  date: Schema.String,
  detail: Schema.String,
  from: Schema.String,
  loader: Schema.String,
  mod: Schema.String,
  status: RunStatus,
  to: Schema.String,
});

export type BenchRunValue = typeof BenchRun.Type;

const Earlier = Schema.Struct({
  afterBump: Schema.NullOr(Schema.String),
  date: Schema.String,
  errors: Schema.NullOr(Schema.Number),
  firstBuild: Schema.String,
  how: Schema.String,
  mod: Schema.String,
  port: Schema.String,
});

const SpeedCell = Schema.Struct({
  date: Schema.String,
  note: Schema.String,
  seconds: Schema.Number,
  text: Schema.String,
});

export type SpeedCellValue = typeof SpeedCell.Type;

const Speed = Schema.Struct({
  dates: Schema.Array(Schema.String),
  groups: Schema.Array(
    Schema.Struct({
      name: Schema.String,
      rows: Schema.Array(
        Schema.Struct({ cells: Schema.Array(SpeedCell), name: Schema.String })
      ),
    })
  ),
  machine: Schema.String,
});

const LoaderPair = Schema.Struct({
  detail: Schema.String,
  from: Schema.String,
  status: Schema.String,
  to: Schema.String,
});

export const Bench = Schema.Struct({
  earlier: Schema.Array(Earlier),
  facts: Schema.Struct({
    mods: Schema.Number,
    runs: Schema.Number,
    versions: Schema.Number,
    working: Schema.Number,
  }),
  loaderKeys: Schema.Array(Schema.String),
  loaderPairs: Schema.Array(LoaderPair),
  loaders: Schema.Array(Schema.String),
  runs: Schema.Array(BenchRun),
  speed: Speed,
  tested: Schema.Array(Tested),
  versionsTouched: Schema.Array(Schema.String),
});

export type BenchValue = typeof Bench.Type;

export const Release = Schema.Struct({
  family: Schema.String,
  id: Schema.String,
  measured: Schema.Boolean,
  released: Schema.String,
});

export const Step = Schema.Struct({
  backLeft: Schema.Number,
  backListed: Schema.Number,
  backMappings: Schema.Number,
  breakingShare: Schema.Number,
  from: Schema.String,
  left: Schema.Number,
  linked: Schema.String,
  listed: Schema.Number,
  mappings: Schema.Number,
  note: Schema.Number,
  oldSize: Schema.Number,
  rule: Schema.Number,
  to: Schema.String,
});

export type StepValue = typeof Step.Type;

export const LedgerRun = Schema.Struct({
  date: Schema.String,
  from: Schema.String,
  loader: Schema.String,
  mod: Schema.String,
  status: RunStatus,
  to: Schema.String,
});

export type LedgerRunValue = typeof LedgerRun.Type;

export const MapData = Schema.Struct({
  generated: Schema.String,
  hubs: Schema.Array(Schema.String),
  runs: Schema.Array(LedgerRun),
  stepStatus: Schema.Array(
    Schema.Struct({
      back: StepStatus,
      from: Schema.String,
      fwd: StepStatus,
      to: Schema.String,
    })
  ),
  steps: Schema.Array(Step),
  versions: Schema.Array(Release),
});

export type MapDataValue = typeof MapData.Type;

export const Table = Schema.Struct({
  head: Schema.Array(Schema.String),
  heading: Schema.String,
  rows: Schema.Array(Schema.Array(Schema.String)),
});

export type TableValue = typeof Table.Type;

export const Numbered = Schema.Struct({
  points: Schema.Array(Schema.String),
  text: Schema.String,
  title: Schema.String,
});

export type NumberedValue = typeof Numbered.Type;

export const Scope = Schema.Struct({
  intake: Schema.Array(Numbered),
  ladder: Table,
  platforms: Table,
  policy: Schema.Array(Numbered),
  sides: Table,
  status: Schema.String,
  who: Schema.String,
});

export type ScopeValue = typeof Scope.Type;

const Titled = Schema.Struct({ text: Schema.String, title: Schema.String });

export const Readme = Schema.Struct({
  beta: Schema.String,
  goal: Schema.String,
  limits: Schema.Array(Titled),
  quickAfter: Schema.String,
  quickCommands: Schema.Array(Schema.String),
  quickIntro: Schema.String,
  release: Schema.String,
  use: Schema.Array(Titled),
});

export type ReadmeValue = typeof Readme.Type;

export const RoadmapStatus = Schema.Literals([
  "done",
  "doing",
  "next",
  "later",
]);

export type RoadmapStatusValue = typeof RoadmapStatus.Type;

export const Roadmap = Schema.Struct({
  decisions: Schema.Array(
    Schema.Struct({
      choice: Schema.String,
      date: Schema.String,
      question: Schema.String,
    })
  ),
  goal: Schema.String,
  phases: Schema.Array(
    Schema.Struct({
      id: Schema.String,
      steps: Schema.Array(
        Schema.Struct({
          id: Schema.String,
          status: RoadmapStatus,
          text: Schema.String,
        })
      ),
      title: Schema.String,
    })
  ),
});

export type RoadmapValue = typeof Roadmap.Type;

export const Help = Schema.Struct({
  commands: Schema.Array(Schema.String),
  topics: Schema.Array(
    Schema.Struct({ command: Schema.String, text: Schema.String })
  ),
  usage: Schema.String,
});

export type HelpValue = typeof Help.Type;

export const Facts = Schema.Struct({
  allReleases: Schema.Number,
  blockedReason: Schema.String,
  families: Schema.Number,
  firstIndexed: Schema.String,
  indexedReleases: Schema.Number,
  lastIndexed: Schema.String,
  measuredFamilies: Schema.Number,
  rules: Schema.Number,
  stonecutterMods: Schema.Number,
  stonecutterRules: Schema.Number,
  stonecutterSwitches: Schema.Number,
});

export type FactsValue = typeof Facts.Type;

export const HopCheck = Schema.Struct({
  from: Schema.String,
  status: StepStatus,
  to: Schema.String,
});

export const SiteData = Schema.Struct({
  bench: Bench,
  commit: Schema.String,
  facts: Facts,
  help: Help,
  map: MapData,
  readme: Readme,
  roadmap: Roadmap,
  rule: Rule,
  rules: Schema.Array(Schema.String),
  scope: Scope,
});

export type SiteDataValue = typeof SiteData.Type;
