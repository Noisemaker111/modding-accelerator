import { Runtime } from "foldkit";

import { Message } from "../features/message.js";
import { Flags, Model } from "../features/model.js";
import { init, update } from "../features/update.js";
import { view } from "../features/view.js";

import "./site.css";

const application = Runtime.makeApplication({
  Flags,
  Model,
  container: document.querySelector("#root"),
  devTools: import.meta.env.DEV ? { Message } : false,
  init,
  update,
  view,
});

Runtime.hydrate(application);
