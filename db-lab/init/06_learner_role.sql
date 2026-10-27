-- =========================================================
-- learner：展示台執行「使用者寫的 SQL」時使用的角色
-- lab 是超級使用者；唯讀交易擋得住寫入，卻擋不住 pg_read_file() 這類超級使用者函數。
-- 改用權限最小的角色執行，才是正確的做法（最小權限原則）。
-- =========================================================
CREATE ROLE learner NOLOGIN;
GRANT learner TO lab;

GRANT USAGE ON SCHEMA public, perf TO learner;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO learner;   -- 寫入沙盒用（一律 ROLLBACK）
GRANT USAGE ON ALL SEQUENCES IN SCHEMA public TO learner;                         -- INSERT 時取得新的 id
GRANT SELECT ON ALL TABLES IN SCHEMA perf TO learner;                             -- 索引實驗室只能查詢
