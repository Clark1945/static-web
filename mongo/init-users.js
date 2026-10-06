// MongoDB 第一次啟動時執行（docker-entrypoint-initdb.d），建立權限受限的帳號
// 比照 PostgreSQL 的 learner 角色與 Redis 的 ACL：使用者輸入的指令一律用受限帳號執行
//   admin  ：root，展示台用來載入資料、重置、跑實驗室（由 MONGO_INITDB_ROOT_* 建立）
//   reader ：只能讀 shop，批改「查詢題」時使用，寫入會被拒絕
//   learner：可以讀寫 shop 與 scratch（批改寫入題用的隔離區），不能管理使用者、不能刪資料庫

const admin = db.getSiblingDB("admin");

admin.createUser({
  user: "reader",
  pwd: "reader-lab",
  roles: [{ role: "read", db: "shop" }],
});

admin.createUser({
  user: "learner",
  pwd: "learner-lab",
  roles: [
    { role: "readWrite", db: "shop" },
    { role: "readWrite", db: "scratch" },
  ],
});
