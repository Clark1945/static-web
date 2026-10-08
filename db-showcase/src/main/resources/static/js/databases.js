// 所有資料庫的清單。串接好一個，就把 status 改成 "ready" 並加上 page。
export const DATABASES = [
  {
    id: "postgres", name: "PostgreSQL", kind: "關聯式", mono: "Pg", color: "#336791", ink: "#FFFFFF",
    tagline: "功能最完整的開源關聯式資料庫：SQL、JOIN、交易、索引的標準教材。",
    status: "ready", page: () => import("./pages/postgres/index.js"),
  },
  {
    id: "redis", name: "Redis", kind: "鍵值", mono: "Rd", color: "#D82C20", ink: "#FFFFFF",
    tagline: "資料放在記憶體的鍵值資料庫，常用在快取、排行榜、Session、限流。",
    status: "ready", page: () => import("./pages/redis/index.js"),
  },
  {
    id: "mongodb", name: "MongoDB", kind: "文件", mono: "Mg", color: "#13AA52", ink: "#FFFFFF",
    tagline: "以類 JSON 文件儲存資料，欄位結構可以彈性變動。",
    status: "ready", page: () => import("./pages/mongodb/index.js"),
  },
  {
    id: "cassandra", name: "Apache Cassandra", kind: "寬欄", mono: "Ca", color: "#1287B1", ink: "#FFFFFF",
    tagline: "分散式寬欄資料庫，擅長大量寫入、多機房且不停機。",
    status: "ready", page: () => import("./pages/cassandra/index.js"),
  },
  {
    id: "neo4j", name: "Neo4j", kind: "圖", mono: "N4", color: "#018BFF", ink: "#FFFFFF",
    tagline: "用節點與關係儲存資料，適合社群關係、推薦、路徑查詢。",
    status: "ready", page: () => import("./pages/neo4j/index.js"),
  },
  {
    id: "timescaledb", name: "TimescaleDB", kind: "時序", mono: "Ts", color: "#F5B800", ink: "#1E1700",
    tagline: "PostgreSQL 的時序擴充：自動依時間分區，照樣寫 SQL。",
    status: "ready", page: () => import("./pages/timescale/index.js"),
  },
  {
    id: "influxdb", name: "InfluxDB", kind: "時序", mono: "If", color: "#7A65F2", ink: "#FFFFFF",
    tagline: "專為監控指標、IoT 感測資料設計的時序資料庫。",
    status: "planned",
  },
  {
    id: "elasticsearch", name: "Elasticsearch", kind: "搜尋引擎", mono: "Es", color: "#00BFB3", ink: "#002A27",
    tagline: "全文檢索與日誌分析，用倒排索引做模糊搜尋和相關性排序。",
    status: "planned",
  },
  {
    id: "pgvector", name: "pgvector", kind: "向量", mono: "Vec", color: "#5B4FC4", ink: "#FFFFFF",
    tagline: "PostgreSQL 的向量擴充：相似度搜尋，AI 語意搜尋與 RAG 的基礎。",
    status: "planned",
  },
];

export const findDatabase = (id) => DATABASES.find((d) => d.id === id);
