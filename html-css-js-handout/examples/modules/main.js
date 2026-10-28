import { count, inc } from "./counter.js";
import api from "./api.js";
import { api as sameApi } from "./api.js"; // same module, evaluated once

const out = document.getElementById("out");
const log = (line) => { out.textContent += line + "\n"; console.log(line); };

log(`count at start: ${count}`);

document.getElementById("inc").addEventListener("click", () => {
  inc();
  // `count` is a live binding: it reflects the exporter's current value.
  log(`after inc(): ${count}`);
});

document.getElementById("lazy").addEventListener("click", async () => {
  const { renderChart } = await import("./chart.js"); // downloaded now, cached afterwards
  renderChart(out);
});

log(`api === sameApi: ${api === sameApi}`);
api.get("/users").then((r) => log(`api.get -> ${JSON.stringify(r)}`));

// Circular import demo (check the Console for the TDZ message).
import("./cycle-a.js");
