import type {
  ColourValue,
  LedgerRunValue,
  MapDataValue,
  RunStatusValue,
  StepValue,
  StepStatusValue,
} from "./schema.js";

export const runSaid: Readonly<Record<RunStatusValue, string>> = {
  behaviour: "behaviour checked in the game",
  build: "packaged build, not loaded yet",
  load: "loads in the real game",
  stop: "stopped with named errors",
};

const worstRank: Readonly<Record<ColourValue, number>> = {
  green: 2,
  red: 0,
  yellow: 1,
};

const runRank: Readonly<Record<RunStatusValue, number>> = {
  behaviour: 4,
  build: 2,
  load: 3,
  stop: 1,
};

export type Size = "tiny" | "small" | "medium" | "big";

const sizeOrder: readonly Size[] = ["tiny", "small", "medium", "big"];

export interface Leg {
  readonly a: string;
  readonly b: string;
  readonly broken: number;
  readonly forward: boolean;
  readonly left: number;
  readonly mapped: number;
  readonly note: number;
  readonly rule: number;
  readonly size: Size;
}

export interface Readiness {
  readonly kind: "ready" | "warn" | "blocked";
  readonly label: string;
}

export interface Hop {
  readonly a: string;
  readonly b: string;
  readonly broken: number;
  readonly handled: number;
  readonly left: number;
  readonly legs: readonly Leg[];
  readonly measured: boolean;
  readonly note: number;
  readonly readiness: Readiness;
  readonly size: Size | null;
  readonly status: StepStatusValue;
}

export interface PortMap {
  readonly hubs: readonly string[];
  readonly isHub: (version: string) => boolean;
  readonly measured: (version: string) => boolean;
  readonly position: (version: string) => number | undefined;
  readonly versions: readonly string[];
  readonly hopStatus: (from: string, to: string) => StepStatusValue;
  readonly jumpStatus: (from: string, to: string) => StepStatusValue;
  readonly route: (from: string, to: string) => readonly Hop[];
  readonly stepStatus: (index: number, forward: boolean) => StepStatusValue;
}

const sizeOf = (share: number): Size => {
  if (share < 0.005) {
    return "tiny";
  }

  if (share < 0.02) {
    return "small";
  }

  return share < 0.04 ? "medium" : "big";
};

const biggest = (sizes: readonly Size[]): Size | null => {
  let max: Size | null = null;

  for (const size of sizes) {
    if (max === null || sizeOrder.indexOf(size) > sizeOrder.indexOf(max)) {
      max = size;
    }
  }

  return max;
};

const blockedStatus: StepStatusValue = {
  c: "red",
  why: "No official mappings before 1.14.4, so nothing here is measured and modaccel cannot port it yet.",
  word: "Blocked",
};

const lower = (text: string) => text.toLowerCase();

const stripDot = (text: string) => text.replace(/\.+$/u, "");

const worstOf = (
  statuses: readonly StepStatusValue[],
  fallback: StepStatusValue
) => {
  let worst = fallback;

  for (const status of statuses) {
    if (worstRank[status.c] < worstRank[worst.c]) {
      worst = status;
    }
  }

  return worst;
};

const mixedWord: Readonly<Record<ColourValue, string>> = {
  green: "Works",
  red: "Not working yet",
  yellow: "Partly working",
};

interface Part {
  readonly from: number;
  readonly status: StepStatusValue;
  readonly to: number;
}

export const portMapFrom = (data: MapDataValue): PortMap => {
  const versions = data.versions.map((release) => release.id);
  const positions = new Map(versions.map((id, index) => [id, index]));

  const measuredSet = new Set(
    data.versions.filter((release) => release.measured).map((r) => r.id)
  );

  const hubSet = new Set(data.hubs);

  const steps = new Map<string, StepValue>(
    data.steps.map((step) => [`${step.from}__${step.to}`, step])
  );

  const statuses = new Map(
    data.stepStatus.map((entry) => [`${entry.from}__${entry.to}`, entry])
  );

  const position = (version: string) => positions.get(version);
  const at = (version: string) => positions.get(version) ?? 0;
  const name = (index: number) => versions[index] ?? "";

  const stepStatus = (index: number, forward: boolean): StepStatusValue => {
    const entry = statuses.get(`${name(index)}__${name(index + 1)}`);

    if (entry === undefined) {
      return blockedStatus;
    }

    return forward ? entry.fwd : entry.back;
  };

  const hopStatus = (from: string, to: string): StepStatusValue => {
    const forward = at(from) < at(to);
    const low = Math.min(at(from), at(to));
    const high = Math.max(at(from), at(to));
    const parts: Part[] = [];

    for (let index = low; index < high; index += 1) {
      const status = stepStatus(index, forward);
      const last = parts.at(-1);

      if (
        last !== undefined &&
        last.status.word === status.word &&
        last.status.why === status.why
      ) {
        parts[parts.length - 1] = { ...last, to: index + 1 };
      } else {
        parts.push({ from: index, status, to: index + 1 });
      }
    }

    if (!forward) {
      parts.reverse();
    }

    const [first] = parts;

    if (first === undefined) {
      return blockedStatus;
    }

    const worst = worstOf(
      parts.map((part) => part.status),
      first.status
    );

    if (parts.length === 1) {
      return worst;
    }

    const label = (part: Part) =>
      forward
        ? `${name(part.from)} → ${name(part.to)}`
        : `${name(part.to)} → ${name(part.from)}`;

    const why = parts
      .map(
        (part) =>
          `${label(part)}: ${lower(part.status.word)} (${stripDot(part.status.why)}).`
      )
      .join(" ");

    const sameColour = parts.every((part) => part.status.c === worst.c);

    const onlyUntriedRed =
      worst.c === "red" &&
      parts.some((part) => part.status.c !== "red") &&
      parts.every(
        (part) => part.status.c !== "red" || part.status.word === "Not tried"
      );

    if (onlyUntriedRed) {
      return { c: worst.c, why, word: "Partly tried" };
    }

    return {
      c: worst.c,
      why,
      word: sameColour ? worst.word : mixedWord[worst.c],
    };
  };

  const leg = (a: string, b: string): Leg => {
    const forward = at(a) < at(b);
    const step = forward ? steps.get(`${a}__${b}`) : steps.get(`${b}__${a}`);

    if (step === undefined) {
      return {
        a,
        b,
        broken: 0,
        forward,
        left: 0,
        mapped: 0,
        note: 0,
        rule: 0,
        size: "tiny",
      };
    }

    return forward
      ? {
          a,
          b,
          broken: step.listed,
          forward,
          left: step.left,
          mapped: step.mappings,
          note: step.note,
          rule: step.rule,
          size: sizeOf(step.breakingShare),
        }
      : {
          a,
          b,
          broken: step.backListed,
          forward,
          left: step.backLeft,
          mapped: step.backMappings,
          note: 0,
          rule: 0,
          size: sizeOf(step.backListed / Math.max(1, step.oldSize)),
        };
  };

  const walk = (a: string, b: string): readonly Leg[] => {
    const direction = at(a) < at(b) ? 1 : -1;
    const legs: Leg[] = [];

    for (let index = at(a); index !== at(b); index += direction) {
      legs.push(leg(name(index), name(index + direction)));
    }

    return legs;
  };

  const span = (run: LedgerRunValue) =>
    [
      Math.min(at(run.from), at(run.to)),
      Math.max(at(run.from), at(run.to)),
    ] as const;

  const readiness = (a: string, b: string, measured: boolean): Readiness => {
    if (!measured) {
      return { kind: "blocked", label: "Blocked" };
    }

    const forward = at(a) < at(b);
    const low = Math.min(at(a), at(b));
    const high = Math.max(at(a), at(b));

    const runs = data.runs.filter(
      (run) =>
        positions.has(run.from) &&
        positions.has(run.to) &&
        at(run.from) < at(run.to) === forward
    );

    const full = runs
      .filter((run) => span(run)[0] <= low && high <= span(run)[1])
      .toSorted((x, y) => runRank[y.status] - runRank[x.status]);

    const [best] = full;

    if (best !== undefined && best.status !== "stop") {
      return {
        kind: "ready",
        label: `Ran before: ${best.mod} ${best.from} → ${best.to}, ${runSaid[best.status]}`,
      };
    }

    if (best !== undefined) {
      return {
        kind: "warn",
        label: `Tried: ${best.mod} ${best.from} → ${best.to} stopped with named errors. Expect errors`,
      };
    }

    const part = runs.find((run) => span(run)[0] < high && span(run)[1] > low);

    if (part !== undefined) {
      return {
        kind: "warn",
        label: `Not tried across the whole hop (${part.mod} ran ${part.from} → ${part.to}). Expect errors`,
      };
    }

    return { kind: "warn", label: "Not tried yet: expect errors" };
  };

  const route = (from: string, to: string): readonly Hop[] => {
    const direction = Math.sign(at(to) - at(from));

    const middle = data.hubs.filter(
      (hub) =>
        (at(hub) - at(from)) * direction > 0 &&
        (at(to) - at(hub)) * direction > 0
    );

    const stops = [from, ...(direction < 0 ? middle.toReversed() : middle), to];
    const hops: Hop[] = [];

    for (let index = 0; index + 1 < stops.length; index += 1) {
      const a = stops[index] ?? from;
      const b = stops[index + 1] ?? to;
      const measured = measuredSet.has(a) && measuredSet.has(b);
      const legs = measured ? walk(a, b) : [];

      const sum = (pick: (one: Leg) => number) =>
        legs.reduce((total, one) => total + pick(one), 0);

      hops.push({
        a,
        b,
        broken: sum((one) => one.broken),
        handled: sum((one) => one.mapped + one.rule),
        left: sum((one) => one.left),
        legs,
        measured,
        note: sum((one) => one.note),
        readiness: readiness(a, b, measured),
        size: biggest(legs.map((one) => one.size)),
        status: hopStatus(a, b),
      });
    }

    return hops;
  };

  const jumpStatus = (from: string, to: string): StepStatusValue => {
    const status = hopStatus(from, to);
    const hubSpan = Math.abs(data.hubs.indexOf(to) - data.hubs.indexOf(from));

    if (hubSpan < 2) {
      return status;
    }

    return {
      c: status.c === "green" ? "yellow" : status.c,
      why: `One port command does this jump, but no single run has covered all of it yet. Along the way: ${status.why}`,
      word: status.c === "red" ? status.word : "Not run end to end yet",
    };
  };

  return {
    hopStatus,
    hubs: data.hubs,
    isHub: (version) => hubSet.has(version),
    jumpStatus,
    measured: (version) => measuredSet.has(version),
    position,
    route,
    stepStatus,
    versions,
  };
};
