-- =========================================================
-- products 加上 JSONB 規格與 text[] 標籤（練習 JSONB / Array 用）
-- 用 id 的雜湊值決定內容（不用亂數），重建資料庫結果完全一樣
-- =========================================================
ALTER TABLE products
    ADD COLUMN specs jsonb,                          -- 規格：各分類的鍵不同；約 3% 是 NULL
    ADD COLUMN tags  text[] NOT NULL DEFAULT '{}';   -- 標籤：熱銷、特價、限量…，可能是空陣列
COMMENT ON COLUMN products.specs IS '商品規格（JSONB），不同分類有不同的鍵';
COMMENT ON COLUMN products.tags  IS '商品標籤（text[]）';

CREATE FUNCTION pg_temp.h(id int, k text) RETURNS int LANGUAGE sql IMMUTABLE AS $$
  SELECT abs(hashtext(id::text || ':' || k))
$$;
CREATE FUNCTION pg_temp.hp(id int, k text, a anyarray) RETURNS anyelement LANGUAGE sql IMMUTABLE AS $$
  SELECT a[1 + pg_temp.h(id, k) % array_length(a, 1)]
$$;
CREATE FUNCTION pg_temp.warranty(id int) RETURNS jsonb LANGUAGE sql IMMUTABLE AS $$
  SELECT jsonb_build_object('years', 1 + pg_temp.h(id, 'wy') % 2,
                            'type', CASE WHEN pg_temp.h(id, 'wt') % 3 = 0 THEN '店家' ELSE '原廠' END)
$$;

UPDATE products p SET specs = CASE
  WHEN category_id = 2 THEN jsonb_build_object(            -- 手機
       'color', pg_temp.hp(id, 'c', ARRAY['黑','白','銀','藍','紫']),
       'storage_gb', pg_temp.hp(id, 's', ARRAY[64,128,256,512]),
       'screen_inch', round(6.1 + (pg_temp.h(id, 'sc') % 9) / 10.0, 1),
       '5g', pg_temp.h(id, '5g') % 10 < 8,
       'warranty', pg_temp.warranty(id))
  WHEN category_id = 3 THEN jsonb_build_object(            -- 筆電
       'cpu', pg_temp.hp(id, 'cpu', ARRAY['Intel i5','Intel i7','Apple M3','AMD Ryzen 7']),
       'ram_gb', pg_temp.hp(id, 'r', ARRAY[8,16,32]),
       'storage_gb', pg_temp.hp(id, 's', ARRAY[256,512,1024]),
       'weight_kg', round(1.1 + (pg_temp.h(id, 'w') % 14) / 10.0, 1),
       'warranty', pg_temp.warranty(id))
  WHEN category_id = 5 THEN jsonb_build_object(            -- 無線耳機
       'color', pg_temp.hp(id, 'c', ARRAY['黑','白','銀','藍']),
       'noise_cancelling', pg_temp.h(id, 'nc') % 2 = 0,
       'battery_hours', pg_temp.hp(id, 'b', ARRAY[6,8,10,24,30]),
       'warranty', pg_temp.warranty(id))
  WHEN category_id = 6 THEN jsonb_build_object(            -- 有線耳機
       'color', pg_temp.hp(id, 'c', ARRAY['黑','白','銀']),
       'cable_m', pg_temp.hp(id, 'cm', ARRAY[1.2,1.5,2.0]),
       'warranty', pg_temp.warranty(id))
  WHEN category_id = 7 THEN jsonb_build_object(            -- 手機配件
       'type', pg_temp.hp(id, 't', ARRAY['充電器','行動電源','保護殼','傳輸線']),
       'color', pg_temp.hp(id, 'c', ARRAY['黑','白','透明']))
  WHEN category_id = 9 THEN jsonb_build_object(            -- 廚房用品
       'material', pg_temp.hp(id, 'm', ARRAY['不鏽鋼','玻璃','陶瓷','鑄鐵']),
       'capacity_ml', pg_temp.hp(id, 'cap', ARRAY[350,500,750,1000,1500]))
  WHEN category_id IN (10, 11) THEN jsonb_build_object(    -- 寢具、收納
       'material', pg_temp.hp(id, 'm', ARRAY['棉','竹纖維','聚酯纖維','木頭','塑膠']),
       'size', pg_temp.hp(id, 'sz', ARRAY['單人','雙人','加大']))
  WHEN category_id IN (13, 14) THEN jsonb_build_object(    -- 男裝、女裝：sizes 是 JSON 陣列
       'color', pg_temp.hp(id, 'c', ARRAY['黑','白','灰','藍','紅','綠']),
       'material', pg_temp.hp(id, 'm', ARRAY['棉','聚酯纖維','麻','羊毛']),
       'sizes', to_jsonb((ARRAY['S','M','L','XL','XXL'])[1 + pg_temp.h(id, 'a') % 2 : 3 + pg_temp.h(id, 'b') % 3]))
  WHEN category_id IN (16, 17) THEN jsonb_build_object(    -- 鞋子
       'color', pg_temp.hp(id, 'c', ARRAY['黑','白','灰','藍']),
       'gender', pg_temp.hp(id, 'g', ARRAY['男','女','中性']))
  WHEN category_id IN (19, 21) THEN jsonb_build_object(    -- 零食、生鮮
       'origin', pg_temp.hp(id, 'o', ARRAY['台灣','台灣','台灣','日本','美國','泰國']),
       'weight_g', pg_temp.hp(id, 'wg', ARRAY[50,100,200,500,1000]),
       'nutrition', jsonb_build_object('calories', 50 + pg_temp.h(id, 'cal') % 450))
  WHEN category_id = 20 THEN jsonb_build_object(           -- 飲料
       'origin', pg_temp.hp(id, 'o', ARRAY['台灣','台灣','日本','美國']),
       'volume_ml', pg_temp.hp(id, 'v', ARRAY[250,330,600,1250]),
       'nutrition', jsonb_build_object('calories', pg_temp.h(id, 'cal') % 250))
  WHEN category_id IN (23, 24) THEN jsonb_build_object(    -- 保養、彩妝
       'volume_ml', pg_temp.hp(id, 'v', ARRAY[15,30,50,100]),
       'skin_type', pg_temp.hp(id, 'sk', ARRAY['乾性','油性','混合性','敏感性']))
  WHEN category_id IN (26, 27) THEN jsonb_build_object(    -- 健身器材、露營用品
       'color', pg_temp.hp(id, 'c', ARRAY['黑','灰','綠','橘']),
       'weight_kg', round(0.5 + (pg_temp.h(id, 'w') % 150) / 10.0, 1))
END
WHERE pg_temp.h(id, 'null') % 100 >= 3;                     -- 約 3% 的商品沒有規格（NULL）

UPDATE products p SET tags = array_remove(ARRAY[
    CASE WHEN id > 1400                         THEN '新品' END,
    CASE WHEN pg_temp.h(id, 'hot') % 100 < 25   THEN '熱銷' END,
    CASE WHEN pg_temp.h(id, 'sale') % 100 < 20  THEN '特價' END,
    CASE WHEN pg_temp.h(id, 'ltd') % 100 < 8    THEN '限量' END,
    CASE WHEN pg_temp.h(id, 'eco') % 100 < 10   THEN '環保' END,
    CASE WHEN pg_temp.h(id, 'grp') % 100 < 12   THEN '團購' END,
    CASE WHEN pg_temp.h(id, 'exc') % 100 < 5    THEN '獨家' END
], NULL);

ANALYZE products;
