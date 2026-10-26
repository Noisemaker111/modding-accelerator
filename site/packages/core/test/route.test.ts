import { describe, expect, it } from "@effect/vitest";
import { Effect, Schema } from "effect";
import * as Arbitrary from "effect/Arbitrary";

import { modStatus, portRoute } from "../src/capabilities.js";
import rawCheck from "../src/generated/hop-check.json" with { type: "json" };
import { portMapFrom } from "../src/route.js";
import { HopCheck } from "../src/schema.js";
import { siteData } from "../src/site-data.js";

const hopCheck = Schema.decodeUnknownSync(Schema.Array(HopCheck))(rawCheck);

const portMap = portMapFrom(siteData.map);

const last = portMap.versions.length - 1;

const releaseIndex = Arbitrary.schema(
  Schema.Int.check(Schema.isBetween({ maximum: last, minimum: 0 }))
);

const release = (index: number) => portMap.versions[index] ?? "";

describe("porting map rule", () => {
  it("colours every checked hop exactly as bench/ledger_rule.py does", () => {
    expect(hopCheck.length).toBeGreaterThan(40);

    for (const hop of hopCheck) {
      expect(portMap.hopStatus(hop.from, hop.to)).toStrictEqual(hop.status);
    }
  });

  it.prop(
    "a route starts at the first pick, ends at the second, and joins up hop to hop",
    { a: releaseIndex, b: releaseIndex },
    ({ a, b }) => {
      if (a === b) {
        return;
      }

      const hops = portMap.route(release(a), release(b));

      expect(hops[0]?.a).toBe(release(a));
      expect(hops.at(-1)?.b).toBe(release(b));

      for (const [index, hop] of hops.entries()) {
        const next = hops[index + 1];

        if (next !== undefined) {
          expect(next.a).toBe(hop.b);
          expect(portMap.isHub(hop.b)).toBe(true);
        }
      }
    }
  );

  it.prop(
    "a hop is never shown better than its worst update",
    { a: releaseIndex, b: releaseIndex },
    ({ a, b }) => {
      if (a === b) {
        return;
      }

      const rank = { green: 2, red: 0, yellow: 1 } as const;
      const hop = portMap.hopStatus(release(a), release(b));
      const low = Math.min(a, b);
      const high = Math.max(a, b);

      for (let index = low; index < high; index += 1) {
        expect(rank[hop.c]).toBeLessThanOrEqual(
          rank[portMap.stepStatus(index, a < b).c]
        );
      }
    }
  );
});

describe("capabilities", () => {
  it.effect("portRoute names an unknown release", () =>
    Effect.gen(function* unknownRelease() {
      const failure = yield* portRoute
        .handler({ from: "1.99", to: "1.21.6" })
        .pipe(Effect.flip);

      expect(failure.version).toBe("1.99");
    })
  );

  it.effect("modStatus returns every ledger run of a tested mod", () =>
    Effect.gen(function* appleSkin() {
      const answer = yield* modStatus.handler({ mod: "appleskin" });

      const ledgerRuns = siteData.bench.runs.filter(
        (run) => run.mod === "AppleSkin"
      );

      expect(answer.mod).toBe("AppleSkin");
      expect(answer.runs).toHaveLength(ledgerRuns.length);
    })
  );
});
