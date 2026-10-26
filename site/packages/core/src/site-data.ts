import { Schema } from "effect";

import raw from "./generated/site-data.json" with { type: "json" };
import { SiteData } from "./schema.js";
import type { SiteDataValue } from "./schema.js";

export const siteData: SiteDataValue = Schema.decodeUnknownSync(SiteData)(raw);
