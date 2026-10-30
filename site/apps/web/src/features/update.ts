import { siteData } from "@modaccel-site/core/browser";
import type { Update } from "foldkit";

import { CopyText, ReadHash, ToggleTheme, WriteHash } from "./command.js";
import { Message } from "./message.js";
import type { SiteMessage } from "./message.js";
import { defaultFrom, defaultTo } from "./model.js";
import type { SiteFlags, SiteModel } from "./model.js";

type Return = Update.Return<SiteModel, SiteMessage>;

const known = new Set(siteData.map.versions.map((release) => release.id));

const pick = (version: string) => (known.has(version) ? version : "");

const withPair = (model: SiteModel, from: string, to: string): Return => {
  const next = { ...model, from: pick(from), to: pick(to) };

  return {
    commands: [WriteHash({ from: next.from, to: next.to })],
    model: next,
  };
};

export const init = (flags: SiteFlags): Return => ({
  commands: flags.page === "map" ? [ReadHash()] : [],
  model: { from: defaultFrom, page: flags.page, shell: "win", to: defaultTo },
});

export const update = (model: SiteModel, message: SiteMessage): Return =>
  Message.match<Return>(message, {
    ChangedFrom: ({ version }) => withPair(model, version, model.to),
    ChangedTo: ({ version }) => withPair(model, model.from, version),
    ClickedCopy: ({ text }) => ({ commands: [CopyText({ text })], model }),
    ClickedRelease: ({ version }) =>
      model.from !== "" && model.to === "" && model.from !== version
        ? withPair(model, model.from, version)
        : withPair(model, version, ""),
    ClickedShell: ({ shell }) => ({ model: { ...model, shell } }),
    ClickedSwap: () => withPair(model, model.to, model.from),
    ClickedTheme: () => ({ commands: [ToggleTheme()], model }),
    CompletedCopy: () => ({ model }),
    CompletedThemeChange: () => ({ model }),
    CompletedWriteHash: () => ({ model }),
    LoadedHash: ({ from, to }) =>
      known.has(from) || known.has(to)
        ? { model: { ...model, from: pick(from), to: pick(to) } }
        : { model },
  });
