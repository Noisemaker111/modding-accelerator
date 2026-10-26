import { Schema } from "effect";

export class UnknownMod extends Schema.TaggedError<UnknownMod>()("UnknownMod", {
  known: Schema.Array(Schema.String),
  message: Schema.String,
  mod: Schema.String,
}) {}
