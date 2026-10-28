# ES Modules demo (multi-file)

Companion to chapter 10.3 of the HTML/CSS/JS handout.

## Run it

Browsers load module files under CORS rules, so this must be served over HTTP.
Opening `index.html` by double-click (`file://`) fails with a CORS error.

From this folder, run any one of:

```bash
npx http-server -p 8080        # Node
python -m http.server 8080     # Python 3
```

Then open <http://localhost:8080/> and the browser Console.

## What to look at

| File | Shows |
| --- | --- |
| `counter.js` + `main.js` | live bindings: `count` updates after `inc()` |
| `api.js` | a module is evaluated once, importers share one instance |
| `chart.js` | dynamic `import()`: downloaded only on click (see the Network tab) |
| `cycle-a.js` / `cycle-b.js` | circular import, TDZ error at top level, and the fix (read lazily inside a function) |

Exercises: (1) change `cycle-b.js` so it reads `a` at top level only after
moving the shared value to a third module; (2) add `export default` to
`counter.js` and see what changes in `main.js`.
