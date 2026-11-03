import { Schema } from "effect";

export class UnknownVersion extends Schema.TaggedError<UnknownVersion>()(
  "UnknownVersion",
  {
    message: Schema.String,
    version: Schema.String,
  }
) {}
