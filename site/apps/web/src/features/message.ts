import { Schema } from "effect";
import { defineMessageUnion } from "foldkit/message";

import { Shell } from "./model.js";

export const Message = defineMessageUnion({
  ChangedFrom: { version: Schema.String },
  ChangedTo: { version: Schema.String },
  ClickedCopy: { text: Schema.String },
  ClickedRelease: { version: Schema.String },
  ClickedShell: { shell: Shell },
  ClickedSwap: {},
  ClickedTheme: {},
  CompletedCopy: {},
  CompletedThemeChange: {},
  CompletedWriteHash: {},
  LoadedHash: { from: Schema.String, to: Schema.String },
});

export type SiteMessage = typeof Message.Type;
