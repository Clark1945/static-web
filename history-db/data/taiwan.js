/*
 * 台灣
 * 欄位說明見 README.md 的「資料格式」。
 *   year / yearEnd : 整數西元年，西元前用負數（沒有 0 年）
 *   yearLabel      : 覆寫顯示文字，例如「1720 年代」
 *   precision      : exact（預設）/ approx（約略）/ disputed（說法不一）
 *   major          : true 代表時間軸上的重大節點
 *   wiki           : 維基百科條目名稱，頁面會連到維基搜尋；名稱精確就直接跳到條目
 */
HistoryDB.register({
  id: "taiwan",
  name: "台灣",
  nameEn: "Taiwan",
  color: "#0e7c86",
  eras: [
    { id: "pre",       name: "大航海前夕",   start: 1600, end: 1624 },
    { id: "dutch",     name: "荷西時期",     start: 1624, end: 1662 },
    { id: "zheng",     name: "明鄭時期",     start: 1662, end: 1683 },
    { id: "qing",      name: "清朝時期",     start: 1683, end: 1895 },
    { id: "japan",     name: "日治時期",     start: 1895, end: 1945 },
    { id: "postwar",   name: "戰後威權時期", start: 1945, end: 1987 },
    { id: "democracy", name: "民主化時期",   start: 1987, end: null },
  ],
  events: [
    // ───── 大航海前夕 ─────
    {
      id: "tw-1603-dongfan-ji", year: 1603, era: "pre",
      title: "陳第來台，寫下〈東番記〉",
      summary: "隨明朝水師到台灣，留下早期描述台灣原住民生活的紀錄。",
      wiki: "東番記",
    },

    // ───── 荷西時期 ─────
    {
      id: "tw-1624-dutch-anping", year: 1624, era: "dutch", major: true,
      title: "荷蘭人在台南安平建立據點",
      summary: "荷蘭撤出澎湖，轉往大員（今台南安平）建立據點，常被視為台灣信史時代的起點。",
      wiki: "荷蘭統治時期的臺灣",
    },
    {
      id: "tw-1626-spanish-north", year: 1626, era: "dutch",
      title: "西班牙人占領北台灣",
      summary: "占領雞籠（基隆），1628 年再進駐淡水，在北部建立據點。",
      wiki: "西班牙統治時期的臺灣",
    },
    {
      id: "tw-1642-dutch-expel-spanish", year: 1642, era: "dutch",
      title: "荷蘭人趕走西班牙人",
      summary: "攻下雞籠，西班牙人退出，荷蘭勢力擴及北台灣。",
      wiki: "聖薩爾瓦多城",
    },
    {
      id: "tw-1652-guo-huaiyi", year: 1652, era: "dutch",
      title: "郭懷一事件",
      summary: "漢人農民不滿荷蘭課稅而起義，失敗後遭大規模鎮壓。",
      wiki: "郭懷一事件",
    },

    // ───── 明鄭時期 ─────
    {
      id: "tw-1662-zheng-defeats-dutch", year: 1662, era: "zheng", major: true,
      title: "鄭成功擊敗荷蘭人",
      summary: "攻下熱蘭遮城，荷蘭人投降，鄭氏建立以漢人為主的政權。",
      wiki: "東寧王國",
    },
    {
      id: "tw-1665-chen-yonghua", year: 1665, era: "zheng",
      title: "陳永華設孔廟、學校",
      summary: "推行儒學教育與屯田開墾，奠定明鄭的治理基礎。",
      wiki: "陳永華",
    },

    // ───── 清朝時期 ─────
    {
      id: "tw-1683-qing-conquest", year: 1683, era: "qing", major: true,
      title: "清朝擊敗鄭氏，台灣納入版圖",
      summary: "施琅在澎湖海戰擊敗鄭軍，鄭克塽投降。",
      wiki: "施琅",
    },
    {
      id: "tw-1684-taiwan-prefecture", year: 1684, era: "qing",
      title: "設台灣府，頒布渡台禁令",
      summary: "台灣府隸屬福建省；渡台禁令限制移民，但偷渡始終不斷。",
      wiki: "臺灣府",
    },
    {
      id: "tw-1697-yu-yonghe", year: 1697, era: "qing",
      title: "郁永河來台採硫磺",
      summary: "寫下《裨海紀遊》，留下北台灣的珍貴紀錄。",
      wiki: "裨海紀遊",
    },
    {
      id: "tw-1709-chen-laizhang", year: 1709, era: "qing",
      title: "陳賴章墾號獲准開墾",
      summary: "開墾範圍是台北盆地，是漢人大規模開發北台灣的開端。",
      wiki: "陳賴章墾號",
    },
    {
      id: "tw-1720s-migration", year: 1720, yearLabel: "1720 年代", precision: "approx", era: "qing",
      title: "移民大量湧入，開墾重心北移",
      summary: "漢人移民增加，開墾重心從南部往中、北部擴展。",
      wiki: "臺灣清治時期",
    },
    {
      id: "tw-1721-zhu-yigui", year: 1721, era: "qing", major: true,
      title: "朱一貴事件",
      summary: "漢人起義，一度占領台灣府城，數月後被清軍平定。",
      wiki: "朱一貴事件",
    },
    {
      id: "tw-1731-dajiaxi", year: 1731, yearEnd: 1732, era: "qing",
      title: "大甲西社事件",
      summary: "原住民不滿勞役而反抗，清廷出兵鎮壓。",
      wiki: "大甲西社事件",
    },
    {
      id: "tw-1732-family-migration", year: 1732, era: "qing",
      title: "放寬渡台禁令",
      summary: "允許移民攜眷來台，不過禁令後來有鬆有緊，反覆多次。",
      wiki: "渡臺禁令",
    },
    {
      id: "tw-1784-lukang-port", year: 1784, era: "qing",
      title: "開放鹿港與蚶江對渡",
      summary: "開放鹿港與福建蚶江之間的官方航線，中部成為重要港口。",
      wiki: "鹿港",
    },
    {
      id: "tw-1786-lin-shuangwen", year: 1786, yearEnd: 1788, era: "qing", major: true,
      title: "林爽文事件",
      summary: "天地會領導的大規模民變，是清治時期規模最大的民變之一，清廷動用大軍才平定。",
      wiki: "林爽文事件",
    },
    {
      id: "tw-1788-bali-port", year: 1788, era: "qing",
      title: "開放八里坌與福州對渡",
      summary: "八里坌與福州五虎門之間開放對渡，北部港口開始興起。",
      wiki: "八里坌",
    },
    {
      id: "tw-1840s-western-powers", year: 1840, yearLabel: "1840 年代", precision: "approx", era: "qing",
      title: "西方勢力開始覬覦台灣",
      summary: "鴉片戰爭後，英國船艦多次到基隆一帶探勘。",
      wiki: "鴉片戰爭",
    },
    {
      id: "tw-1858-tianjin-treaty", year: 1858, era: "qing",
      title: "《天津條約》列台灣為通商口岸",
      summary: "清廷與英、法簽約，台灣被列入開放通商的口岸之一。",
      wiki: "天津條約",
    },
    {
      id: "tw-1860-ports-opened", year: 1860, yearEnd: 1865, era: "qing", major: true,
      title: "港口陸續開放通商",
      summary: "淡水（1862）、基隆（1863）、打狗、安平等港陸續開放，茶、糖、樟腦成為主要外銷品。",
      wiki: "臺灣開港",
    },
    {
      id: "tw-1865-tea-export", year: 1865, era: "qing",
      title: "英商陶德推動茶葉外銷",
      summary: "北台灣茶業從此崛起，成為重要出口產業。",
      wiki: "約翰·陶德",
    },
    {
      id: "tw-1867-rover-incident", year: 1867, era: "qing",
      title: "羅妹號事件",
      summary: "美國商船在南台灣遇難，船員遭原住民殺害，美軍出兵一度失利。",
      wiki: "羅妹號事件",
    },
    {
      id: "tw-1871-mudan-incident", year: 1871, yearEnd: 1874, era: "qing",
      title: "牡丹社事件",
      summary: "琉球漁民漂流到南台灣遇害，日本藉此出兵（1874），清廷轉而重視台灣防務。",
      wiki: "牡丹社事件",
    },
    {
      id: "tw-1875-shen-baozhen", year: 1875, era: "qing",
      title: "沈葆楨來台，開山撫番",
      summary: "整頓台灣防務與行政，增設台北府，並廢除渡台禁令。",
      wiki: "沈葆楨",
    },
    {
      id: "tw-1884-sino-french-war", year: 1884, yearEnd: 1885, era: "qing",
      title: "清法戰爭",
      summary: "法軍進攻基隆、淡水並封鎖台灣，淡水之役擊退法軍。",
      wiki: "清法戰爭",
    },
    {
      id: "tw-1885-province", year: 1885, era: "qing", major: true,
      title: "台灣建省",
      summary: "劉銘傳出任首任巡撫，台灣成為獨立的行省。",
      wiki: "臺灣建省",
    },
    {
      id: "tw-1887-liu-mingchuan", year: 1887, yearEnd: 1891, era: "qing",
      title: "劉銘傳推動現代化建設",
      summary: "興建鐵路（後來通到新竹）、電報、郵政，並設新式學堂與清丈土地。",
      wiki: "劉銘傳",
    },
    {
      id: "tw-1894-first-sino-japanese-war", year: 1894, era: "qing",
      title: "甲午戰爭爆發",
      summary: "清朝與日本開戰，戰敗後台灣的命運就此改變。",
      wiki: "甲午戰爭",
    },

    // ───── 日治時期 ─────
    {
      id: "tw-1895-cession", year: 1895, era: "japan", major: true,
      title: "《馬關條約》，台灣割讓給日本",
      summary: "清廷戰敗，將台灣與澎湖割讓給日本，進入日治時期。",
      wiki: "馬關條約",
    },
    {
      id: "tw-1895-republic-of-formosa", year: 1895, era: "japan",
      title: "台灣民主國成立",
      summary: "割讓後由地方士紳宣布成立，僅維持數個月，日軍登陸後平定。",
      wiki: "臺灣民主國",
    },
    {
      id: "tw-1915-xilaian", year: 1915, era: "japan",
      title: "西來庵事件",
      summary: "武裝抗日的最後一次大規模行動，此後抗日轉為非武力路線。",
      wiki: "西來庵事件",
    },
    {
      id: "tw-1921-cultural-association", year: 1921, era: "japan",
      title: "台灣文化協會成立",
      summary: "蔣渭水等人推動啟蒙運動與議會設置請願。",
      wiki: "臺灣文化協會",
    },
    {
      id: "tw-1930-wushe", year: 1930, era: "japan",
      title: "霧社事件",
      summary: "賽德克族不滿日方壓迫而起義，遭日軍鎮壓。",
      wiki: "霧社事件",
    },
    {
      id: "tw-1937-kominka", year: 1937, era: "japan",
      title: "皇民化運動",
      summary: "中日戰爭爆發後，日本在台推行同化政策，推廣日語與日式生活。",
      wiki: "皇民化運動",
    },
    {
      id: "tw-1941-pacific-war", year: 1941, yearEnd: 1945, era: "japan",
      title: "太平洋戰爭時期",
      summary: "台灣被徵調人力物資，戰爭後期遭美軍空襲。",
      wiki: "臺灣日治時期",
    },

    // ───── 戰後威權時期 ─────
    {
      id: "tw-1945-roc-takeover", year: 1945, era: "postwar", major: true,
      title: "日本戰敗，中華民國接收台灣",
      summary: "戰後台灣由中華民國政府接收，進入戰後時期。",
      wiki: "臺灣戰後時期",
    },
    {
      id: "tw-1947-228", year: 1947, era: "postwar",
      title: "二二八事件",
      summary: "緝菸衝突引發全台民變，政府派軍鎮壓，死傷慘重。",
      wiki: "二二八事件",
    },
    {
      id: "tw-1949-martial-law", year: 1949, era: "postwar",
      title: "國民政府遷台，頒布戒嚴令",
      summary: "台灣進入長達 38 年的戒嚴時期。",
      wiki: "臺灣戒嚴時期",
    },
    {
      id: "tw-1950s-land-reform", year: 1950, yearLabel: "1950 年代", precision: "approx", era: "postwar",
      title: "美國協防與土地改革",
      summary: "韓戰後美國協防台灣；政府推行三七五減租、耕者有其田。",
      wiki: "臺灣土地改革",
    },
    {
      id: "tw-1966-export-zone", year: 1966, era: "postwar",
      title: "加工出口區設立",
      summary: "高雄設立加工出口區，吸引外資與出口導向工業，台灣經濟起飛。",
      wiki: "高雄加工出口區",
    },
    {
      id: "tw-1971-un-exit", year: 1971, era: "postwar",
      title: "退出聯合國",
      summary: "中華人民共和國取得中國在聯合國的代表權。",
      wiki: "聯合國大會第2758號決議",
    },
    {
      id: "tw-1974-ten-projects", year: 1974, yearEnd: 1979, era: "postwar",
      title: "十大建設",
      summary: "興建高速公路、鐵路電氣化、港口、煉鋼廠等基礎與重工業建設。",
      wiki: "十大建設",
    },
    {
      id: "tw-1979-us-derecognition", year: 1979, era: "postwar",
      title: "台美斷交",
      summary: "美國與中華民國終止外交關係，轉與中華人民共和國建交。",
      wiki: "臺美斷交",
    },
    {
      id: "tw-1979-formosa-incident", year: 1979, era: "postwar",
      title: "美麗島事件",
      summary: "黨外運動人士遭逮捕審判，成為民主化的重要轉折。",
      wiki: "美麗島事件",
    },
    {
      id: "tw-1986-dpp", year: 1986, era: "postwar",
      title: "民主進步黨成立",
      summary: "在戒嚴時期組成的反對黨，推動政治改革。",
      wiki: "民主進步黨",
    },

    // ───── 民主化時期 ─────
    {
      id: "tw-1987-lifting-martial-law", year: 1987, era: "democracy", major: true,
      title: "解除戒嚴",
      summary: "結束長達 38 年的戒嚴，民主化進程加速。",
      wiki: "臺灣戒嚴時期",
    },
    {
      id: "tw-1988-lee-teng-hui", year: 1988, era: "democracy",
      title: "蔣經國過世，李登輝接任",
      summary: "李登輝成為首位本土出身的總統。",
      wiki: "李登輝",
    },
    {
      id: "tw-1991-end-of-emergency", year: 1991, era: "democracy",
      title: "終止動員戡亂時期",
      summary: "萬年國會走入歷史，國會陸續全面改選。",
      wiki: "動員戡亂時期臨時條款",
    },
    {
      id: "tw-1996-direct-election", year: 1996, era: "democracy", major: true,
      title: "首次總統直選",
      summary: "李登輝當選，台灣人民首次直接選出總統。",
      wiki: "1996年中華民國總統選舉",
    },
    {
      id: "tw-2000-first-turnover", year: 2000, era: "democracy",
      title: "第一次政黨輪替",
      summary: "陳水扁當選總統，國民黨結束長期執政。",
      wiki: "2000年中華民國總統選舉",
    },
    {
      id: "tw-2008-second-turnover", year: 2008, era: "democracy",
      title: "第二次政黨輪替",
      summary: "馬英九當選總統，國民黨重新執政。",
      wiki: "2008年中華民國總統選舉",
    },
    {
      id: "tw-2014-sunflower", year: 2014, era: "democracy",
      title: "太陽花學運",
      summary: "學生與民眾占領立法院，反對服貿協議。",
      wiki: "太陽花學運",
    },
    {
      id: "tw-2016-third-turnover", year: 2016, era: "democracy",
      title: "第三次政黨輪替",
      summary: "蔡英文當選，成為首位女性總統。",
      wiki: "2016年中華民國總統選舉",
    },
    {
      id: "tw-2024-lai-ching-te", year: 2024, era: "democracy",
      title: "賴清德當選總統",
      summary: "民進黨首度連續執政三任。",
      wiki: "2024年中華民國總統選舉",
    },
  ],
});
