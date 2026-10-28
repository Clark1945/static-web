// A imports B, B imports A: a circular dependency.
import { b, readA } from "./cycle-b.js";

export const a = "A";

console.log("[cycle-a] b =", b);
console.log("[cycle-a] readA() =", readA());
