// A singleton: every importer shares this one object.
export const api = {
  baseUrl: "/api",
  calls: 0,
  // Pretend request: replace with fetch(`${api.baseUrl}${path}`) against your own backend.
  async get(path) {
    api.calls++;
    return { path, calls: api.calls };
  },
};

export default api;
console.log("[api.js] evaluated");
