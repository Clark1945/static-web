import { a } from "./cycle-a.js";

export const b = "B";

// Problem: when cycle-b.js runs FIRST during the cycle (because cycle-a.js is the
// entry point), cycle-a.js has not finished evaluating yet, so `a` is still in
// its temporal dead zone (TDZ).
let early;
try {
  early = a;
} catch (e) {
  early = e.name + ": " + e.message;
}
console.log("[cycle-b] reading `a` at top level ->", early);

// Fix: do not read the import at top level. Read it later, inside a function.
export function readA() {
  return a;
}
