import * as Cloudflare from "@alchemy.run/cloudflare";
import * as Alchemy from "alchemy";
import * as Effect from "effect/Effect";

export default Alchemy.Stack(
  "ModaccelSite",
  {
    providers: Cloudflare.providers(),
    state: Cloudflare.state(),
  },
  Effect.gen(function* stack() {
    const website = yield* Cloudflare.Website.Foldkit("Website", {
      assets: { htmlHandling: "drop-trailing-slash" },
      compatibility: { date: "2026-07-28" },
      rootDir: "../web",
    });

    return { websiteUrl: website.url };
  })
);
