import { Effect, Schema } from "effect";
import { Command } from "foldkit";

import { Message } from "./message.js";

const readPair = (hash: string) => {
  const params = new URLSearchParams(hash.replace(/^#/u, ""));

  return { from: params.get("from") ?? "", to: params.get("to") ?? "" };
};

export const ReadHash = Command.define("ReadHash", {
  execute: Effect.sync(() =>
    Message.LoadedHash(readPair(window.location.hash))
  ),
  messages: [Message.LoadedHash],
});

export const WriteHash = Command.define("WriteHash", {
  args: { from: Schema.String, to: Schema.String },
  execute: ({ from, to }) =>
    Effect.sync(() => {
      const params = new URLSearchParams();

      if (from !== "") {
        params.set("from", from);
      }

      if (to !== "") {
        params.set("to", to);
      }

      window.history.replaceState(null, "", `#${params.toString()}`);

      return Message.CompletedWriteHash();
    }),
  messages: [Message.CompletedWriteHash],
});

export const ToggleTheme = Command.define("ToggleTheme", {
  execute: Effect.sync(() => {
    const root = document.documentElement;

    const dark =
      root.dataset.theme === "dark" ||
      (root.dataset.theme !== "light" &&
        window.matchMedia("(prefers-color-scheme: dark)").matches);

    const next = dark ? "light" : "dark";

    root.dataset.theme = next;

    try {
      window.localStorage.setItem("theme", next);
    } catch {
      return Message.CompletedThemeChange();
    }

    return Message.CompletedThemeChange();
  }),
  messages: [Message.CompletedThemeChange],
});

export const CopyText = Command.define("CopyText", {
  args: { text: Schema.String },
  execute: ({ text }) =>
    // @effect-diagnostics-next-line asyncFunction:off -- The Clipboard API only offers a Promise.
    Effect.tryPromise(async () => {
      await navigator.clipboard.writeText(text);
    }).pipe(Effect.ignore, Effect.as(Message.CompletedCopy())),
  messages: [Message.CompletedCopy],
});
