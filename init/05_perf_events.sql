-- =========================================================
-- 索引實驗室第二張大表：perf.order_events（200 萬筆訂單事件紀錄）
-- 依時間順序寫入，資料在磁碟上的順序和 event_time 一致 → 示範 BRIN
-- meta 是 JSONB，其中少數有優惠券代碼 → 示範 GIN
-- =========================================================
SELECT setseed(0.31);

CREATE TABLE perf.order_events (
    id         bigserial   PRIMARY KEY,
    order_id   bigint      NOT NULL,
    event_time timestamptz NOT NULL,     -- 只會往後增加，像 log 一樣一直附加在最後面
    event_type text        NOT NULL,     -- created / paid / shipped / delivered
    meta       jsonb       NOT NULL      -- {"channel": "app", "device": "ios", "coupon": "VIP2026"}
);
ALTER TABLE perf.order_events OWNER TO perf_lab;
COMMENT ON TABLE perf.order_events IS '依時間順序寫入的訂單事件紀錄';

INSERT INTO perf.order_events (order_id, event_time, event_type, meta)
SELECT 1 + (g - 1) / 4,
       timestamptz '2022-01-01' + g * interval '75 seconds',
       (ARRAY['created','paid','shipped','delivered'])[1 + (g - 1) % 4],
       jsonb_build_object(
           'channel', (ARRAY['app','app','web','line','store'])[1 + floor(random() * 5)::int],
           'device',  (ARRAY['ios','android','desktop'])[1 + floor(random() * 3)::int])
       || CASE WHEN r < 0.0005 THEN '{"coupon": "VIP2026"}'::jsonb
               WHEN r < 0.05   THEN '{"coupon": "FALL10"}'::jsonb
               ELSE '{}'::jsonb END
FROM (SELECT g, random() AS r FROM generate_series(1, 2000000) g) s
ORDER BY g;

VACUUM ANALYZE perf.order_events;
