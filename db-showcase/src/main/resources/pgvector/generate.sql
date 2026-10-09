-- pgvector 練習資料的產生規則（products、purchases 已經由展示台從 PostgreSQL 複製進來）。
-- 全部用 setseed 固定亂數種子，每次重建結果都一樣。句子之間用連續兩個分號分隔。

-- ================================================================ 1. 詞庫（迷你嵌入模型）
-- 每個「主題」是一個隨機的 64 維單位向量（64 維空間裡，隨機向量幾乎互相垂直）。
-- 每個詞 = 幾個主題的加權和 + 一點自己的隨機成分，再正規化。共用主題越多的詞，向量越接近。
DO $$
DECLARE
  topics text[] := ARRAY['音訊','手機','電腦','配件','電力','廚房','睡眠','收納','居家','服飾','鞋','運動','戶外',
    '零食','飲料','生鮮','保養','彩妝','保濕','控油','溫和','安靜','輕便','保暖','涼爽','健康','送禮','便宜','環保',
    '男','女','熱門','新品','限量','團購','獨家','黑','白','藍','紅','綠','灰','銀','紫','橘','透明'];
BEGIN
  PERFORM setseed(0.2026);
  CREATE TEMP TABLE topic_vec ON COMMIT DROP AS
  SELECT t.name, l2_normalize((SELECT array_agg(random_normal()) FROM generate_series(1, 64) WHERE t.ord > 0)::vector(64)) AS v
  FROM unnest(topics) WITH ORDINALITY AS t(name, ord);

  CREATE TEMP TABLE lexicon (word text, recipe text) ON COMMIT DROP;
  INSERT INTO lexicon VALUES
    -- 3C
    ('耳機', '音訊:1'), ('無線', '音訊:.4 輕便:.4 電力:.3'), ('藍牙', '音訊:.6 輕便:.3 電力:.2'),
    ('降噪', '音訊:.6 安靜:1'), ('音樂', '音訊:1'), ('聽歌', '音訊:1'), ('追劇', '音訊:.5 手機:.4 電腦:.4'),
    ('通勤', '安靜:.6 輕便:.6 音訊:.4 電力:.2'), ('捷運', '安靜:.5 輕便:.6 音訊:.3'),
    ('手機', '手機:1'), ('5G', '手機:.8'), ('螢幕', '手機:.5 電腦:.5'), ('拍照', '手機:.9'), ('自拍', '手機:.7 彩妝:.3'),
    ('筆電', '電腦:1'), ('電腦', '電腦:1'), ('記憶體', '電腦:.8 手機:.2'), ('輕薄', '輕便:1 電腦:.3'),
    ('辦公', '電腦:.9 安靜:.2'), ('上班', '電腦:.6 服飾:.3 輕便:.2 安靜:.2'), ('開會', '電腦:.7 音訊:.4 安靜:.2'),
    ('打電動', '電腦:.7 手機:.3 音訊:.3'), ('遊戲', '電腦:.7 手機:.4 音訊:.2'), ('學生', '電腦:.4 便宜:.6'),
    ('配件', '配件:1 手機:.3'), ('保護殼', '配件:.9 手機:.5'), ('傳輸線', '配件:.8 電力:.6'),
    ('充電器', '電力:1 配件:.5'), ('行動電源', '電力:1 輕便:.4 配件:.3'), ('充電', '電力:1'), ('沒電', '電力:1'),
    ('續航', '電力:.8 音訊:.2'), ('出國', '電力:.4 輕便:.6 戶外:.3'), ('旅行', '輕便:.7 戶外:.4 電力:.3'),
    -- 居家
    ('廚房', '廚房:1'), ('料理', '廚房:1'), ('煮飯', '廚房:1'), ('鍋', '廚房:.9'), ('早餐', '廚房:.6 飲料:.4 零食:.2'),
    ('保溫', '保暖:.6 廚房:.5 飲料:.3'), ('不鏽鋼', '廚房:.7'), ('鑄鐵', '廚房:.9'), ('陶瓷', '廚房:.7 居家:.3'), ('玻璃', '廚房:.6 居家:.3'),
    ('寢具', '睡眠:1'), ('睡覺', '睡眠:1 安靜:.4'), ('失眠', '睡眠:.8 安靜:.6 健康:.2'), ('床', '睡眠:.9 居家:.3'),
    ('枕頭', '睡眠:1'), ('棉被', '睡眠:.8 保暖:.6'), ('單人', '睡眠:.3 居家:.3 收納:.2'), ('雙人', '睡眠:.5 居家:.3'), ('加大', '睡眠:.3 居家:.3'),
    ('收納', '收納:1'), ('整理', '收納:.9 居家:.3'), ('搬家', '收納:.7 居家:.5'), ('小套房', '收納:.6 居家:.6 便宜:.2'),
    ('宿舍', '收納:.5 睡眠:.4 便宜:.4'), ('居家', '居家:1'), ('竹纖維', '涼爽:.6 睡眠:.3 環保:.4'), ('塑膠', '收納:.4 便宜:.3'), ('木頭', '居家:.6 收納:.3 環保:.3'),
    -- 服飾、鞋
    ('男裝', '服飾:1 男:.4'), ('女裝', '服飾:1 女:.4'), ('衣服', '服飾:1'), ('穿搭', '服飾:.8 鞋:.3'), ('外套', '服飾:.8 保暖:.6'),
    ('羊毛', '保暖:.9 服飾:.4'), ('棉', '涼爽:.4 服飾:.3 睡眠:.3'), ('麻', '涼爽:.9 服飾:.4'), ('聚酯纖維', '運動:.4 服飾:.4'),
    ('冬天', '保暖:1'), ('寒流', '保暖:1'), ('怕冷', '保暖:1'), ('夏天', '涼爽:1 飲料:.2'), ('透氣', '涼爽:.8 運動:.3 鞋:.2'),
    ('約會', '服飾:.5 彩妝:.5 送禮:.3'), ('男款', '男:1'), ('女款', '女:1'),
    ('運動鞋', '鞋:.8 運動:.6'), ('休閒鞋', '鞋:1 服飾:.2'), ('鞋', '鞋:1'), ('球鞋', '鞋:.8 運動:.4'), ('跑步', '運動:.9 鞋:.5'), ('慢跑', '運動:.9 鞋:.5'),
    -- 運動、戶外
    ('運動', '運動:1'), ('健身', '運動:1 健康:.4'), ('重訓', '運動:1'), ('減肥', '運動:.7 健康:.7'), ('瘦身', '運動:.7 健康:.6'),
    ('器材', '運動:.5 戶外:.2'), ('露營', '戶外:1'), ('登山', '戶外:.9 運動:.4 輕便:.3'), ('野餐', '戶外:.7 零食:.4 飲料:.3'),
    ('戶外', '戶外:1'), ('帳篷', '戶外:1'), ('輕量', '輕便:1 戶外:.2'),
    -- 食品
    ('零食', '零食:1'), ('餅乾', '零食:1'), ('點心', '零食:.8 送禮:.2'), ('下午茶', '零食:.6 飲料:.6'), ('宵夜', '零食:.7 生鮮:.2'),
    ('飲料', '飲料:1'), ('解渴', '飲料:1 涼爽:.4'), ('口渴', '飲料:1'), ('咖啡', '飲料:.9'), ('茶', '飲料:.9 健康:.2'),
    ('零卡', '健康:.8 飲料:.4'), ('低熱量', '健康:1 零食:.2'), ('生鮮', '生鮮:1'), ('肉', '生鮮:.9 廚房:.3'), ('雞肉', '生鮮:.9 廚房:.3 健康:.3'),
    ('火鍋', '生鮮:.7 廚房:.5 保暖:.3'), ('烤肉', '生鮮:.7 戶外:.5'), ('健康', '健康:1'), ('營養', '健康:.8 生鮮:.3'),
    -- 美妝
    ('保養', '保養:1'), ('乾性', '保養:.6 保濕:.8'), ('乾燥', '保濕:.9 保養:.4'), ('保濕', '保濕:1 保養:.5'),
    ('油性', '保養:.6 控油:.8'), ('控油', '控油:1 保養:.4'), ('痘痘', '控油:.7 保養:.5 溫和:.3'),
    ('敏感', '溫和:1 保養:.5'), ('溫和', '溫和:1 保養:.3'), ('混合性', '保養:.6 控油:.3 保濕:.3'),
    ('彩妝', '彩妝:1'), ('化妝', '彩妝:1'), ('口紅', '彩妝:.9'),
    ('送禮', '送禮:1'), ('禮物', '送禮:1'), ('情人節', '送禮:.8 彩妝:.3'), ('母親節', '送禮:.8 保養:.4'),
    -- 標籤
    ('特價', '便宜:1'), ('便宜', '便宜:1'), ('划算', '便宜:.9'), ('平價', '便宜:.9'), ('環保', '環保:1'),
    ('熱銷', '熱門:1'), ('新品', '新品:1'), ('限量', '限量:1 送禮:.2'), ('團購', '團購:1 便宜:.4'), ('獨家', '獨家:1'),
    -- 跟主題同名的常用詞
    ('安靜', '安靜:1'), ('保暖', '保暖:1'), ('輕便', '輕便:1'), ('涼爽', '涼爽:1'), ('清爽', '控油:.6 涼爽:.5'),
    ('皮膚', '保養:.7 保濕:.2 控油:.2'), ('電池', '電力:1'), ('喝', '飲料:.8'), ('吃', '零食:.5 生鮮:.5'),
    -- 顏色
    ('黑色', '黑:1'), ('白色', '白:1'), ('藍色', '藍:1'), ('紅色', '紅:1'), ('綠色', '綠:1'), ('灰色', '灰:1'),
    ('銀色', '銀:1'), ('紫色', '紫:1'), ('橘色', '橘:1'), ('透明', '透明:1');

  TRUNCATE vocab;
  -- 每個詞：主題加權和 + 0.3 倍自己的隨機方向，最後正規化
  INSERT INTO vocab (word, recipe, embedding)
  SELECT l.word,
         (SELECT string_agg(split_part(x, ':', 1) || ' ' || split_part(x, ':', 2), ' + ') FROM regexp_split_to_table(l.recipe, ' ') x),
         l2_normalize(
           (SELECT sum(t.v * array_fill(split_part(x, ':', 2)::real, ARRAY[64])::vector(64))
            FROM regexp_split_to_table(l.recipe, ' ') x JOIN topic_vec t ON t.name = split_part(x, ':', 1))
           + l2_normalize((SELECT array_agg(random_normal()) FROM generate_series(1, 64) WHERE l.word IS NOT NULL)::vector(64))
             * array_fill(0.3::real, ARRAY[64])::vector(64))
  FROM (SELECT * FROM lexicon ORDER BY word) l;
END $$;;

-- ================================================================ 2. 商品描述與向量
-- 描述 = 商品名稱 + 依規格產生的幾個詞 + 標籤。品牌、型號不在詞庫裡，embed() 會忽略它們。
UPDATE products p SET description = concat_ws(' ', p.name,
  CASE WHEN p.specs->>'color' = '透明' THEN '透明' WHEN p.specs ? 'color' THEN (p.specs->>'color') || '色' END,
  nullif(CASE p.category
    WHEN '手機' THEN concat_ws(' ', (p.specs->>'screen_inch') || '吋螢幕', (p.specs->>'storage_gb') || 'GB', CASE WHEN (p.specs->>'5g')::boolean THEN '5G' END)
    WHEN '筆電' THEN concat_ws(' ', p.specs->>'cpu', (p.specs->>'ram_gb') || 'GB記憶體', (p.specs->>'weight_kg') || '公斤',
                               CASE WHEN (p.specs->>'weight_kg')::numeric <= 1.3 THEN '輕薄' END)
    WHEN '無線耳機' THEN concat_ws(' ', CASE WHEN (p.specs->>'noise_cancelling')::boolean THEN '主動降噪' END, '續航' || (p.specs->>'battery_hours') || '小時')
    WHEN '有線耳機' THEN '線長' || (p.specs->>'cable_m') || '公尺'
    WHEN '手機配件' THEN p.specs->>'type'
    WHEN '廚房用品' THEN concat_ws(' ', p.specs->>'material', '容量' || (p.specs->>'capacity_ml') || '毫升')
    WHEN '寢具' THEN concat_ws(' ', p.specs->>'material', p.specs->>'size')
    WHEN '收納' THEN concat_ws(' ', p.specs->>'material', p.specs->>'size')
    WHEN '男裝' THEN p.specs->>'material'
    WHEN '女裝' THEN p.specs->>'material'
    WHEN '運動鞋' THEN CASE p.specs->>'gender' WHEN '男' THEN '男款' WHEN '女' THEN '女款' ELSE '中性' END
    WHEN '休閒鞋' THEN CASE p.specs->>'gender' WHEN '男' THEN '男款' WHEN '女' THEN '女款' ELSE '中性' END
    WHEN '零食' THEN concat_ws(' ', (p.specs->>'origin') || '產', (p.specs->>'weight_g') || '公克')
    WHEN '生鮮' THEN concat_ws(' ', (p.specs->>'origin') || '產', (p.specs->>'weight_g') || '公克')
    WHEN '飲料' THEN concat_ws(' ', (p.specs->>'origin') || '產', (p.specs->>'volume_ml') || '毫升',
                               CASE WHEN (p.specs->'nutrition'->>'calories')::int = 0 THEN '零卡' END)
    WHEN '保養' THEN concat_ws(' ', (p.specs->>'skin_type') || '肌膚', (p.specs->>'volume_ml') || '毫升')
    WHEN '彩妝' THEN concat_ws(' ', (p.specs->>'skin_type') || '肌膚', (p.specs->>'volume_ml') || '毫升')
    WHEN '健身器材' THEN concat_ws(' ', (p.specs->>'weight_kg') || '公斤', CASE WHEN (p.specs->>'weight_kg')::numeric <= 1 THEN '輕量' END)
    WHEN '露營用品' THEN concat_ws(' ', (p.specs->>'weight_kg') || '公斤', CASE WHEN (p.specs->>'weight_kg')::numeric <= 1 THEN '輕量' END)
  END, ''),
  nullif(array_to_string(p.tags, ' '), ''));;

-- 向量 = embed(描述) + 長度 0.15 的隨機雜訊（讓同類商品不會完全一樣），再正規化
-- （雜訊依 generate_series 的順序產生再用 id 對應，跟表的實體順序無關）
DO $$ BEGIN
  PERFORM setseed(0.7);
  CREATE TEMP TABLE noise ON COMMIT DROP AS
  SELECT i AS id, l2_normalize((SELECT array_agg(random_normal()) FROM generate_series(1, 64) WHERE i > 0)::vector(64)) AS v
  FROM generate_series(1, (SELECT max(id) FROM products)) i;
  UPDATE products p SET embedding = l2_normalize(embed(p.description) + n.v * array_fill(0.15::real, ARRAY[64])::vector(64))
  FROM noise n WHERE n.id = p.id;
END $$;;

-- ================================================================ 3. 索引實驗用的 10 萬筆向量
-- 2000 個隨機的「主題中心」，每筆 = 某個中心 + 常態雜訊，再正規化（模擬真實嵌入：一群一群，但彼此有重疊）。
-- tenant_id 跟向量無關，平均分給 50 個租戶。
DO $$ BEGIN
  PERFORM setseed(0.42);
  CREATE TEMP TABLE centers ON COMMIT DROP AS
  SELECT c AS id, (SELECT array_agg(random_normal()) FROM generate_series(1, 128) WHERE c > 0)::vector(128) AS v
  FROM generate_series(1, 2000) c;
  TRUNCATE passages, questions;
  -- 屬於哪一群、哪個租戶，各用一個雜湊值決定，兩者互相獨立
  INSERT INTO passages
  SELECT i, 1 + (hashint4(i + 1000000) & 2147483647) % 50,
         l2_normalize(c.v + (SELECT array_agg(random_normal()) FROM generate_series(1, 128) WHERE i > 0)::vector(128))
  FROM generate_series(1, 100000) i JOIN centers c ON c.id = 1 + (hashint4(i) & 2147483647) % 2000
  ORDER BY i;
  INSERT INTO questions
  SELECT q, l2_normalize(c.v + (SELECT array_agg(random_normal()) FROM generate_series(1, 128) WHERE q > 0)::vector(128))
  FROM generate_series(1, 100) q JOIN centers c ON c.id = 1 + (q * 37) % 2000
  ORDER BY q;
END $$;;

ANALYZE vocab;;
ANALYZE products;;
ANALYZE purchases;;
ANALYZE passages;;
ANALYZE questions;;
