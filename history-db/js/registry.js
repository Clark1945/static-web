/* 國家資料註冊表。每個 data/*.js 都會呼叫 HistoryDB.register()。 */
(function (global) {
  "use strict";

  var countries = [];

  global.HistoryDB = {
    countries: countries,
    register: function (country) {
      if (!country || !country.id) {
        throw new Error("HistoryDB.register：缺少 country.id");
      }
      countries.push(country);
    },
  };
})(window);
