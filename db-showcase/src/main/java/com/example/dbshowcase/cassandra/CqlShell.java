package com.example.dbshowcase.cassandra;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.datastax.oss.driver.api.core.ConsistencyLevel;
import com.datastax.oss.driver.api.core.CqlSession;
import com.datastax.oss.driver.api.core.DefaultConsistencyLevel;
import com.datastax.oss.driver.api.core.DriverException;
import com.datastax.oss.driver.api.core.cql.ColumnDefinition;
import com.datastax.oss.driver.api.core.cql.ResultSet;
import com.datastax.oss.driver.api.core.cql.Row;
import com.datastax.oss.driver.api.core.cql.SimpleStatement;
import com.datastax.oss.driver.api.core.data.CqlDuration;
import com.datastax.oss.driver.api.core.data.TupleValue;
import com.datastax.oss.driver.api.core.data.UdtValue;

/**
 * 執行使用者輸入的 CQL：用分號切成多句，依序執行，遇到錯誤就停。
 * 另外模擬 cqlsh 的兩個指令（它們不是 CQL，是 cqlsh 自己處理的）：
 * - CONSISTENCY QUORUM：之後的句子改用這個一致性等級
 * - TRACING ON / OFF：之後的句子附上查詢追蹤（伺服器內部做了哪些事）
 */
@Component
public class CqlShell {

    private static final int MAX_STATEMENTS = 20;
    private static final Pattern BATCH_BEGIN = Pattern.compile("(?is)^BEGIN\\s+(UNLOGGED\\s+|LOGGED\\s+|COUNTER\\s+)?BATCH\\b.*");
    private static final Pattern BATCH_END = Pattern.compile("(?is).*\\bAPPLY\\s+BATCH$");
    private static final Pattern CONSISTENCY = Pattern.compile("(?i)^(SERIAL\\s+)?CONSISTENCY(?:\\s+(\\w+))?$");
    private static final Pattern TRACING = Pattern.compile("(?i)^TRACING(?:\\s+(ON|OFF))?$");
    private static final Pattern READ_ROWS = Pattern.compile("Read (\\d+) live rows and (\\d+) tombstone cells");
    private static final int MAX_EVENTS = 80;
    private static final java.util.Set<String> AUTH_TABLES = java.util.Set.of(
            "roles", "role_permissions", "role_members", "network_permissions", "cidr_permissions", "cidr_groups");

    /**
     * 查詢追蹤的重點整理。partitions 是單一分區讀取的次數；rangeScan 代表走了範圍掃描（沒有分區鍵）；
     * indexNote 是 SAI 索引的摘要。events 最多保留 MAX_EVENTS 筆，eventCount 是實際的事件數。
     */
    public record Trace(String request, int durationMicros, long liveRows, long tombstones, int partitions,
                        boolean rangeScan, boolean indexUsed, String indexNote, int eventCount, List<TraceEvent> events) {
    }

    public record TraceEvent(String activity, int elapsedMicros, String thread) {
    }

    /**
     * 一句的結果。kind = "rows"（有欄位與資料列）、"ok"（寫入 / DDL 成功，沒有回傳資料）、"info"（cqlsh 指令）、"error"。
     * total 是實際筆數；truncated 時代表還有更多資料，total 只算到上限。
     */
    public record Result(String statement, String kind, List<String> columns, List<List<Object>> rows,
                         Integer total, boolean truncated, String message, List<String> warnings,
                         String consistency, Trace trace, double millis) {

        static Result error(String statement, String message, String consistency, double millis) {
            return new Result(statement, "error", null, null, null, false, message, List.of(), consistency, null, millis);
        }

        static Result info(String statement, String message) {
            return new Result(statement, "info", null, null, null, false, message, List.of(), null, null, 0);
        }
    }

    public record RunResult(List<Result> results, double totalMillis) {

        public Result last() {
            return results.get(results.size() - 1);
        }
    }

    private final CassandraSessions sessions;

    public CqlShell(CassandraSessions sessions) {
        this.sessions = sessions;
    }

    // ================================================================ 切句

    /** 依分號切句；字串、"識別字"、註解裡的分號不算；BEGIN BATCH … APPLY BATCH 合成一句。 */
    public static List<String> splitStatements(String script) {
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int n = script.length();
        for (int i = 0; i < n; i++) {
            char c = script.charAt(i);
            char next = i + 1 < n ? script.charAt(i + 1) : 0;
            if (c == '\'' || c == '"') {
                int j = i + 1;
                while (j < n) {
                    if (script.charAt(j) == c) {
                        if (j + 1 < n && script.charAt(j + 1) == c) {
                            j += 2;
                            continue;
                        }
                        break;
                    }
                    j++;
                }
                cur.append(script, i, Math.min(n, j + 1));
                i = j;
            } else if ((c == '-' && next == '-') || (c == '/' && next == '/')) {
                while (i < n && script.charAt(i) != '\n') {
                    i++;
                }
                cur.append('\n');
            } else if (c == '/' && next == '*') {
                int end = script.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 1;
                cur.append(' ');
            } else if (c == ';') {
                parts.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        parts.add(cur.toString());

        List<String> out = new ArrayList<>();
        StringBuilder batch = null;
        for (String p : parts) {
            String s = p.strip();
            if (s.isEmpty()) {
                continue;
            }
            if (batch != null) {
                batch.append(";\n").append(s);
                if (BATCH_END.matcher(s).matches()) {
                    out.add(batch.toString());
                    batch = null;
                }
            } else if (BATCH_BEGIN.matcher(s).matches() && !BATCH_END.matcher(s).matches()) {
                batch = new StringBuilder(s);
            } else {
                out.add(s);
            }
        }
        if (batch != null) {
            out.add(batch.toString());     // 少了 APPLY BATCH，交給 Cassandra 回報語法錯誤
        }
        return out;
    }

    // ================================================================ 執行

    /** 以 session 的身分在 keyspace 執行。maxRows 是每句最多回傳幾列。 */
    public RunResult run(CqlSession session, String keyspace, String script, int maxRows) {
        return run(session, keyspace, script, maxRows, DefaultConsistencyLevel.LOCAL_ONE, false);
    }

    public RunResult run(CqlSession session, String keyspace, String script, int maxRows,
                         ConsistencyLevel initialConsistency, boolean initialTracing) {
        List<String> statements = splitStatements(script == null ? "" : script);
        if (statements.isEmpty()) {
            throw new IllegalArgumentException("請輸入至少一句 CQL。");
        }
        if (statements.size() > MAX_STATEMENTS) {
            throw new IllegalArgumentException("一次最多執行 " + MAX_STATEMENTS + " 句。");
        }
        ConsistencyLevel cl = initialConsistency;
        ConsistencyLevel serial = DefaultConsistencyLevel.SERIAL;
        boolean tracing = initialTracing;
        List<Result> results = new ArrayList<>();
        long total = 0;
        for (String text : statements) {
            Matcher m;
            Result r;
            long start = System.nanoTime();
            if ((m = CONSISTENCY.matcher(text)).matches()) {
                boolean isSerial = m.group(1) != null;
                if (m.group(2) == null) {
                    r = Result.info(text, (isSerial ? "目前的 serial consistency 是 " + serial : "目前的一致性等級是 " + cl) + "。");
                } else {
                    try {
                        DefaultConsistencyLevel level = DefaultConsistencyLevel.valueOf(m.group(2).toUpperCase(Locale.ROOT));
                        if (isSerial) {
                            if (!level.isSerial()) {
                                throw new IllegalArgumentException();
                            }
                            serial = level;
                        } else {
                            cl = level;
                        }
                        r = Result.info(text, (isSerial ? "Serial consistency" : "Consistency level") + " set to " + level + ".");
                    } catch (IllegalArgumentException e) {
                        r = Result.error(text, "不認得的一致性等級：" + m.group(2)
                                + (isSerial ? "（只能是 SERIAL 或 LOCAL_SERIAL）" : "（ONE、TWO、THREE、QUORUM、ALL、LOCAL_ONE、LOCAL_QUORUM、EACH_QUORUM、ANY）"), null, 0);
                    }
                }
            } else if ((m = TRACING.matcher(text)).matches()) {
                if (m.group(1) != null) {
                    tracing = m.group(1).equalsIgnoreCase("ON");
                }
                r = Result.info(text, "Tracing is " + (tracing ? "enabled" : "disabled") + ".");
            } else if (text.regionMatches(true, 0, "USE ", 0, 4)) {
                r = Result.error(text, "這裡不支援 USE：每個畫面已經固定好 keyspace。要查其他 keyspace 的表，請寫成 keyspace.table。", null, 0);
            } else {
                r = execute(session, keyspace, text, maxRows, cl, serial, tracing, start);
            }
            total += System.nanoTime() - start;
            results.add(r);
            if (r.kind().equals("error")) {
                break;
            }
        }
        return new RunResult(results, total / 1_000_000.0);
    }

    private Result execute(CqlSession session, String keyspace, String text, int maxRows, ConsistencyLevel cl,
                           ConsistencyLevel serial, boolean tracing, long start) {
        String level = cl.name();
        try {
            SimpleStatement st = SimpleStatement.builder(text)
                    .setKeyspace(keyspace)
                    .setConsistencyLevel(cl)
                    .setSerialConsistencyLevel(serial)
                    .setTracing(tracing)
                    .setPageSize(Math.min(5000, maxRows + 1))
                    .build();
            ResultSet rs = session.execute(st);
            List<String> columns = new ArrayList<>();
            for (ColumnDefinition d : rs.getColumnDefinitions()) {
                columns.add(d.getName().asInternal());
            }
            List<List<Object>> rows = new ArrayList<>();
            boolean truncated = false;
            if (!columns.isEmpty()) {
                for (Row row : rs) {
                    if (rows.size() >= maxRows) {
                        truncated = true;
                        break;
                    }
                    List<Object> values = new ArrayList<>(columns.size());
                    for (int i = 0; i < columns.size(); i++) {
                        values.add(plain(row.getObject(i)));
                    }
                    rows.add(values);
                }
            }
            double millis = (System.nanoTime() - start) / 1e6;
            List<String> warnings = rs.getExecutionInfo().getWarnings();
            Trace trace = tracing ? trace(rs.getExecutionInfo().getTracingId()) : null;
            return new Result(text, columns.isEmpty() ? "ok" : "rows", columns, columns.isEmpty() ? null : rows,
                    columns.isEmpty() ? null : rows.size(), truncated, null, warnings, level, trace, millis);
        } catch (DriverException e) {
            return Result.error(text, describe(e), level, (System.nanoTime() - start) / 1e6);
        } catch (RuntimeException e) {
            return Result.error(text, e.getClass().getSimpleName() + "：" + e.getMessage(), level, (System.nanoTime() - start) / 1e6);
        }
    }

    /**
     * 錯誤訊息：只有一個節點時，AllNodesFailedException 包著的那個錯誤才是重點（例如 UnavailableException）；
     * ReadFailureException 再附上節點回報的原因代碼（墓碑太多是 1）。
     */
    public static String describe(DriverException e) {
        Throwable t = e;
        if (e instanceof com.datastax.oss.driver.api.core.AllNodesFailedException all && !all.getAllErrors().isEmpty()) {
            List<Throwable> errors = all.getAllErrors().values().iterator().next();
            if (!errors.isEmpty()) {
                t = errors.get(0);
            }
        }
        String msg = t.getClass().getSimpleName() + "：" + t.getMessage();
        if (t instanceof com.datastax.oss.driver.api.core.servererrors.ReadFailureException rf && !rf.getReasonMap().isEmpty()) {
            List<String> reasons = new ArrayList<>();
            rf.getReasonMap().values().forEach(code -> reasons.add(switch (code) {
                case 1 -> "READ_TOO_MANY_TOMBSTONES（讀到的墓碑超過 tombstone_failure_threshold）";
                case 2 -> "TIMEOUT";
                case 6 -> "INDEX_NOT_AVAILABLE";
                default -> "原因代碼 " + code;
            }));
            msg += "\n節點回報的原因：" + String.join("、", reasons);
        }
        return msg;
    }

    // ================================================================ 查詢追蹤

    /** 追蹤資料是非同步寫進 system_traces 的，等它寫完（duration 有值）再讀。用 admin 讀，一般角色沒有權限。 */
    public Trace trace(UUID id) {
        if (id == null) {
            return null;
        }
        CqlSession admin = sessions.admin();
        Row session = null;
        for (int i = 0; i < 40; i++) {
            session = admin.execute(SimpleStatement.newInstance(
                    "SELECT request, duration FROM system_traces.sessions WHERE session_id = ?", id)).one();
            if (session != null && !session.isNull("duration")) {
                break;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // 事件也是非同步寫入的：等事件數量不再增加
        long seen = -1;
        for (int i = 0; i < 10; i++) {
            long n = admin.execute(SimpleStatement.newInstance(
                    "SELECT COUNT(*) FROM system_traces.events WHERE session_id = ?", id)).one().getLong(0);
            if (n == seen) {
                break;
            }
            seen = n;
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        List<TraceEvent> events = new ArrayList<>();
        long live = 0, tombstones = 0;
        int partitions = 0, count = 0;
        boolean range = false, index = false;
        String indexNote = null;
        boolean auth = false;      // 目前這段事件是不是在讀權限表（roles、role_permissions…）
        for (Row e : admin.execute(SimpleStatement.newInstance(
                "SELECT activity, source_elapsed, thread FROM system_traces.events WHERE session_id = ?", id))) {
            String activity = e.getString("activity");
            if (activity.startsWith("Executing single-partition query on ")) {
                auth = AUTH_TABLES.contains(activity.substring("Executing single-partition query on ".length()).strip());
            } else if (activity.startsWith("Computing ranges") || activity.startsWith("Executing seq scan")
                    || activity.startsWith("Parsing") || activity.startsWith("Preparing")) {
                auth = false;
            }
            if (auth) {
                continue;          // 每次查詢前的權限檢查，跟查詢本身無關
            }
            count++;
            if (events.size() < MAX_EVENTS) {
                events.add(new TraceEvent(activity, e.getInt("source_elapsed"), e.getString("thread")));
            }
            Matcher m = READ_ROWS.matcher(activity);
            if (m.find()) {
                live += Long.parseLong(m.group(1));
                tombstones += Long.parseLong(m.group(2));
            }
            if (activity.startsWith("Executing single-partition query on ")) {
                partitions++;
            }
            range |= activity.startsWith("Computing ranges") || activity.startsWith("Executing seq scan");
            index |= activity.startsWith("Executing read on") && activity.contains("using index");
            if (activity.startsWith("Index query accessed")) {
                indexNote = activity;
            }
        }
        return new Trace(session == null ? null : session.getString("request"),
                session == null || session.isNull("duration") ? 0 : session.getInt("duration"),
                live, tombstones, partitions, range, index, indexNote, count, events);
    }

    // ================================================================ 值的轉換

    /** 把 driver 的值轉成 JSON 友善的型別：時間轉字串、UDT 轉物件、集合遞迴轉換。 */
    public static Object plain(Object v) {
        if (v == null || v instanceof Number || v instanceof Boolean || v instanceof String) {
            return v;
        }
        if (v instanceof UdtValue udt) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (int i = 0; i < udt.size(); i++) {
                out.put(udt.getType().getFieldNames().get(i).asInternal(), plain(udt.getObject(i)));
            }
            return out;
        }
        if (v instanceof TupleValue t) {
            List<Object> out = new ArrayList<>();
            for (int i = 0; i < t.size(); i++) {
                out.add(plain(t.getObject(i)));
            }
            return out;
        }
        if (v instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, val) -> out.put(String.valueOf(plain(k)), plain(val)));
            return out;
        }
        if (v instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>();
            for (Object o : c) {
                out.add(plain(o));
            }
            return out;
        }
        if (v instanceof ByteBuffer b) {
            StringBuilder sb = new StringBuilder("0x");
            ByteBuffer d = b.duplicate();
            while (d.hasRemaining()) {
                sb.append(String.format("%02x", d.get()));
            }
            return sb.toString();
        }
        if (v instanceof CqlDuration d) {
            return d.toString();
        }
        return v.toString();     // Instant、LocalDate、LocalTime、UUID、InetAddress
    }
}
