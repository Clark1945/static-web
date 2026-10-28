// Only downloaded when the user clicks "Lazy-load chart.js" (dynamic import()).
console.log("[chart.js] evaluated");

export function renderChart(el) {
  el.textContent += "chart.js loaded and rendered\n";
}
