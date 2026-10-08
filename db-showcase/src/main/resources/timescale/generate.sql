-- 產生點擊流與感測器資料。全部用 hashint8 算出「看起來隨機」但每次都一樣的值。
-- 句子之間用連續兩個分號分隔。

-- ---------- 商品瀏覽：2026-04-01 ～ 2026-09-30，每小時的量依時段變化（晚上 8～9 點最多），週末多 3 成，9/9 購物節 3 倍
INSERT INTO page_views (view_time, product_id, customer_id, device, referrer)
SELECT h + make_interval(secs => abs(hashint8(k)) % 3600),
       1 + floor(1500 * power((abs(hashint8(k * 3 + 1)) % 100000) / 100000.0, 2))::int,     -- 編號越小越熱門
       CASE WHEN abs(hashint8(k * 5 + 2)) % 10 < 4 THEN NULL ELSE 1 + abs(hashint8(k * 7 + 3)) % 20000 END,
       (ARRAY['mobile','mobile','mobile','mobile','mobile','mobile','desktop','desktop','desktop','tablet'])[1 + abs(hashint8(k * 11 + 4)) % 10],
       (ARRAY['search','direct','social','ads','email'])[1 + abs(hashint8(k * 13 + 5)) % 5]
FROM (
  SELECT h, (hi * 10000 + i)::bigint AS k
  FROM generate_series(timestamptz '2026-04-01 00:00+08', timestamptz '2026-09-30 23:00+08', interval '1 hour') WITH ORDINALITY AS g(h, hi)
  CROSS JOIN LATERAL generate_series(1, (
      (ARRAY[120,70,40,30,30,50,110,220,340,420,450,470,520,480,450,460,500,560,680,820,900,860,640,330])
        [1 + extract(hour FROM h AT TIME ZONE 'Asia/Taipei')::int]
      * CASE WHEN extract(isodow FROM h AT TIME ZONE 'Asia/Taipei') IN (6, 7) THEN 1.3 ELSE 1 END
      * CASE WHEN (h AT TIME ZONE 'Asia/Taipei')::date = date '2026-09-09' THEN 3 ELSE 1 END
  )::int) AS i
) s;;

-- ---------- 感測器：3 個倉庫，各 2 個冷藏、2 個常溫
INSERT INTO sensors (sensor_id, warehouse, zone, kind)
SELECT id,
       (ARRAY['桃園倉','台中倉','高雄倉'])[1 + (id - 1) / 4],
       (ARRAY['A 區','B 區','C 區','D 區'])[1 + (id - 1) % 4],
       CASE WHEN (id - 1) % 4 < 2 THEN '冷藏' ELSE '常溫' END
FROM generate_series(1, 12) AS id
ON CONFLICT (sensor_id) DO NOTHING;;

-- ---------- 感測器讀數：2026-09 整個月，每分鐘一筆
-- 每日溫度循環 ＋ 雜訊；感測器 7 在 9/10 10:00～14:00 離線；隨機掉 0.5% 的資料；
-- 感測器 2（桃園倉 B 區冷藏）在 9/18 02:00～02:40 溫度飆高（冷藏室的門沒關好）
INSERT INTO sensor_readings (time, sensor_id, temperature, humidity)
SELECT t, s.sensor_id,
       round((CASE WHEN s.kind = '冷藏' THEN 4 ELSE 24 END
              + CASE WHEN s.kind = '冷藏' THEN 0.8 ELSE 3 END * sin(2 * pi() * (extract(epoch FROM t) / 86400.0 - 0.375))
              + ((abs(hashint8(extract(epoch FROM t)::bigint / 60 * 13 + s.sensor_id)) % 1000) / 1000.0 - 0.5) * 0.6
              + CASE WHEN s.sensor_id = 2 AND t >= timestamptz '2026-09-18 02:00+08' AND t < timestamptz '2026-09-18 02:40+08'
                     THEN 8 * (1 - abs(extract(epoch FROM t - timestamptz '2026-09-18 02:20+08')) / 1200.0) ELSE 0 END
             )::numeric, 2)::float8,
       round((CASE WHEN s.kind = '冷藏' THEN 85 ELSE 60 END
              + ((abs(hashint8(extract(epoch FROM t)::bigint / 60 * 17 + s.sensor_id)) % 1000) / 1000.0 - 0.5) * 6
             )::numeric, 1)::float8
FROM sensors s
CROSS JOIN generate_series(timestamptz '2026-09-01 00:00+08', timestamptz '2026-09-30 23:59+08', interval '1 minute') AS t
WHERE NOT (s.sensor_id = 7 AND t >= timestamptz '2026-09-10 10:00+08' AND t < timestamptz '2026-09-10 14:00+08')
  AND abs(hashint8(extract(epoch FROM t)::bigint / 60 * 31 + s.sensor_id)) % 200 <> 0;;

ANALYZE page_views;;
ANALYZE sensor_readings;;
