// A module-level variable that other modules can read as a LIVE binding.
export let count = 0;

export function inc() {
  count++;
}

// Evaluated once, no matter how many modules import it.
console.log("[counter.js] evaluated");
