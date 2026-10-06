package com.example.dbshowcase.mongo;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.bson.Document;
import org.bson.json.JsonParseException;
import org.bson.types.Decimal128;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Component;

import com.mongodb.MongoException;
import com.mongodb.client.AggregateIterable;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.ReturnDocument;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.InsertManyResult;
import com.mongodb.client.result.UpdateResult;

/**
 * 解析並執行 mongosh 風格的指令，例如：
 * <pre>db.orders.find({ status: "paid" }, { total: 1 }).sort({ orderDate: -1 }).limit(5)</pre>
 * 參數交給 Java Driver 的 JSON 解析器，它支援 mongosh 的寫法：不加引號的 key、單引號字串、
 * ISODate("…")、ObjectId("…")、/regex/i。
 */
@Component
public class MongoShell {

    private static final Set<String> COLLECTION_METHODS = Set.of(
            "find", "findOne", "countDocuments", "estimatedDocumentCount", "distinct", "aggregate",
            "insertOne", "insertMany", "updateOne", "updateMany", "replaceOne", "deleteOne", "deleteMany",
            "findOneAndUpdate", "findOneAndDelete", "createIndex", "dropIndex", "getIndexes", "drop");
    private static final Set<String> CURSOR_METHODS = Set.of(
            "sort", "limit", "skip", "project", "projection", "explain", "toArray", "pretty", "count", "hint");
    private static final int MAX_STATEMENTS = 20;

    public record Call(String name, List<Object> args) {
    }

    public record Statement(String text, String collection, List<Call> calls) {
    }

    /**
     * 一句指令的結果。kind = "docs"（文件清單）、"value"（單一值或物件）、"error"。
     * docs 的 total 是實際筆數，value 裡最多放 limit 筆。
     */
    public record Result(String statement, String kind, Object value, Integer total, boolean truncated,
                         double millis) {
    }

    public record RunResult(List<Result> results, double totalMillis) {

        public Result last() {
            return results.get(results.size() - 1);
        }
    }

    // ================================================================ 解析

    public List<Statement> parse(String script) {
        if (script == null || script.isBlank()) {
            throw new IllegalArgumentException("請輸入指令，例如 db.orders.find({ status: \"paid\" }).limit(5)");
        }
        List<String> texts = splitStatements(script);
        if (texts.isEmpty()) {
            throw new IllegalArgumentException("沒有可以執行的指令（// 開頭的是註解）。");
        }
        if (texts.size() > MAX_STATEMENTS) {
            throw new IllegalArgumentException("一次最多 " + MAX_STATEMENTS + " 句。");
        }
        return texts.stream().map(this::parseStatement).toList();
    }

    private Statement parseStatement(String text) {
        Cursor c = new Cursor(text);
        c.skipSpace();
        if (!c.consume("db.")) {
            throw new IllegalArgumentException("每一句都要用 db.集合名稱.方法(...) 開頭：" + abbreviate(text));
        }
        String collection = c.identifier();
        if (collection.isEmpty()) {
            throw new IllegalArgumentException("看不懂集合名稱：" + abbreviate(text));
        }
        List<Call> calls = new ArrayList<>();
        while (true) {
            c.skipSpace();
            if (c.atEnd()) {
                break;
            }
            if (!c.consume(".")) {
                throw new IllegalArgumentException("這裡應該是「.方法(...)」：" + abbreviate(c.rest()));
            }
            c.skipSpace();
            String name = c.identifier();
            c.skipSpace();
            if (!c.peek('(')) {
                throw new IllegalArgumentException("「" + name + "」後面要接括號：" + abbreviate(text));
            }
            String inside = c.balanced();
            calls.add(new Call(name, parseArgs(inside)));
        }
        if (calls.isEmpty()) {
            throw new IllegalArgumentException("缺少方法，例如 db." + collection + ".find()");
        }
        String first = calls.get(0).name();
        if (!COLLECTION_METHODS.contains(first)) {
            throw new IllegalArgumentException("不支援 " + first + "()。支援的方法：" + String.join("、", COLLECTION_METHODS.stream().sorted().toList()));
        }
        for (Call call : calls.subList(1, calls.size())) {
            if (!CURSOR_METHODS.contains(call.name())) {
                throw new IllegalArgumentException("不支援 ." + call.name() + "()。可以串接：sort、limit、skip、project、hint、explain、count。");
            }
        }
        return new Statement(text.strip(), collection, calls);
    }

    /** 參數之間用最外層的逗號分開，每個參數交給 BSON 的 JSON 解析器。 */
    private static List<Object> parseArgs(String inside) {
        List<Object> args = new ArrayList<>();
        for (String raw : splitTopLevel(inside, ',')) {
            if (raw.isBlank()) {
                continue;
            }
            try {
                args.add(Document.parse("{\"_v\": " + raw.strip() + "}").get("_v"));
            } catch (JsonParseException e) {
                throw new IllegalArgumentException("參數格式錯誤：" + abbreviate(raw.strip()) + "（" + e.getMessage() + "）");
            }
        }
        return args;
    }

    /** 用分號，或「換行後接著 db.」切開多句；字串、括號、註解裡面的不算。 */
    static List<String> splitStatements(String script) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        char quote = 0;
        for (int i = 0; i < script.length(); i++) {
            char ch = script.charAt(i);
            if (quote != 0) {
                cur.append(ch);
                if (ch == '\\' && i + 1 < script.length()) {
                    cur.append(script.charAt(++i));
                } else if (ch == quote) {
                    quote = 0;
                }
                continue;
            }
            if (ch == '/' && i + 1 < script.length() && script.charAt(i + 1) == '/') {
                while (i < script.length() && script.charAt(i) != '\n') {
                    i++;
                }
                i--;
                continue;
            }
            if (ch == '/' && i + 1 < script.length() && script.charAt(i + 1) == '*') {
                int end = script.indexOf("*/", i + 2);
                i = end < 0 ? script.length() : end + 1;
                continue;
            }
            if (ch == '"' || ch == '\'') {
                quote = ch;
            } else if (ch == '(' || ch == '{' || ch == '[') {
                depth++;
            } else if (ch == ')' || ch == '}' || ch == ']') {
                depth--;
            }
            if (depth == 0 && ch == ';') {
                flush(out, cur);
                continue;
            }
            if (depth == 0 && ch == '\n' && script.startsWith("db.", firstNonSpace(script, i + 1))) {
                flush(out, cur);
                continue;
            }
            cur.append(ch);
        }
        if (quote != 0) {
            throw new IllegalArgumentException("字串的引號沒有成對。");
        }
        flush(out, cur);
        return out;
    }

    private static void flush(List<String> out, StringBuilder cur) {
        if (!cur.toString().isBlank()) {
            out.add(cur.toString().strip());
        }
        cur.setLength(0);
    }

    private static int firstNonSpace(String s, int from) {
        int i = from;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    private static List<String> splitTopLevel(String s, char sep) {
        List<String> parts = new ArrayList<>();
        int depth = 0, start = 0;
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (quote != 0) {
                if (ch == '\\') {
                    i++;
                } else if (ch == quote) {
                    quote = 0;
                }
            } else if (ch == '"' || ch == '\'') {
                quote = ch;
            } else if (ch == '(' || ch == '{' || ch == '[') {
                depth++;
            } else if (ch == ')' || ch == '}' || ch == ']') {
                depth--;
            } else if (ch == sep && depth == 0) {
                parts.add(s.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(s.substring(start));
        return parts;
    }

    private static String abbreviate(String s) {
        String one = s.replaceAll("\\s+", " ");
        return one.length() > 80 ? one.substring(0, 80) + "…" : one;
    }

    /** 逐字掃描一句指令。 */
    private static final class Cursor {
        private final String s;
        private int i;

        Cursor(String s) {
            this.s = s;
        }

        boolean atEnd() {
            return i >= s.length();
        }

        String rest() {
            return s.substring(i);
        }

        void skipSpace() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        boolean peek(char ch) {
            return i < s.length() && s.charAt(i) == ch;
        }

        boolean consume(String token) {
            if (s.startsWith(token, i)) {
                i += token.length();
                return true;
            }
            return false;
        }

        String identifier() {
            int start = i;
            while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) {
                i++;
            }
            return s.substring(start, i);
        }

        /** 讀出成對的 ( … )，回傳括號裡的內容。 */
        String balanced() {
            int start = i + 1, depth = 0;
            char quote = 0;
            for (; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (quote != 0) {
                    if (ch == '\\') {
                        i++;
                    } else if (ch == quote) {
                        quote = 0;
                    }
                } else if (ch == '"' || ch == '\'') {
                    quote = ch;
                } else if (ch == '(' || ch == '{' || ch == '[') {
                    depth++;
                } else if (ch == ')' || ch == '}' || ch == ']') {
                    depth--;
                    if (depth == 0) {
                        String inside = s.substring(start, i);
                        i++;
                        return inside;
                    }
                }
            }
            throw new IllegalArgumentException("括號沒有成對：" + abbreviate(s));
        }
    }

    // ================================================================ 執行

    /** 依序執行；遇到錯誤就停下來（跟 mongosh 執行腳本一樣）。maxDocs = 每句最多回傳幾份文件。 */
    public RunResult run(MongoDatabase db, List<Statement> statements, int maxDocs) {
        List<Result> results = new ArrayList<>();
        long total = 0;
        for (Statement st : statements) {
            long start = System.nanoTime();
            Result r;
            try {
                r = execute(db, st, maxDocs, start);
            } catch (com.mongodb.MongoCommandException e) {
                r = new Result(st.text(), "error", e.getErrorCodeName() + "：" + e.getErrorMessage(), null, false, ms(start));
            } catch (com.mongodb.MongoWriteException e) {
                r = new Result(st.text(), "error", e.getError().getCategory() + "：" + e.getError().getMessage(), null, false, ms(start));
            } catch (MongoException e) {
                r = new Result(st.text(), "error", e.getMessage(), null, false, ms(start));
            } catch (IllegalArgumentException | ClassCastException e) {
                r = new Result(st.text(), "error", "參數不正確：" + e.getMessage(), null, false, ms(start));
            } catch (RuntimeException e) {
                r = new Result(st.text(), "error", e.getClass().getSimpleName() + "：" + e.getMessage(), null, false, ms(start));
            }
            total += System.nanoTime() - start;
            results.add(r);
            if (r.kind().equals("error")) {
                break;
            }
        }
        return new RunResult(results, total / 1_000_000.0);
    }

    private Result execute(MongoDatabase db, Statement st, int maxDocs, long start) {
        MongoCollection<Document> coll = db.getCollection(st.collection());
        Call first = st.calls().get(0);
        List<Object> a = first.args();
        List<Call> chain = st.calls().subList(1, st.calls().size());
        return switch (first.name()) {
            case "find" -> find(db, coll, st, doc(a, 0), doc(a, 1), chain, maxDocs, start);
            case "aggregate" -> aggregate(db, coll, st, pipeline(a), chain, maxDocs, start);
            case "findOne" -> value(st, plain(project(coll.find(doc(a, 0)), doc(a, 1)).first()), start);
            case "countDocuments" -> value(st, coll.countDocuments(doc(a, 0)), start);
            case "estimatedDocumentCount" -> value(st, coll.estimatedDocumentCount(), start);
            case "distinct" -> {
                Document cmd = new Document("distinct", st.collection()).append("key", string(a, 0)).append("query", doc(a, 1));
                yield docs(st, db.runCommand(cmd).getList("values", Object.class), maxDocs, start);
            }
            case "insertOne" -> value(st, ack("insertedId", plain(coll.insertOne(doc(a, 0)).getInsertedId())), start);
            case "insertMany" -> {
                InsertManyResult r = coll.insertMany(documents(a, 0));
                yield value(st, ack("insertedCount", r.getInsertedIds().size()), start);
            }
            case "updateOne", "updateMany" -> {
                UpdateOptions opt = new UpdateOptions().upsert(doc(a, 2).getBoolean("upsert", false));
                Object update = a.size() > 1 ? a.get(1) : null;
                UpdateResult r;
                if (update instanceof List<?>) {
                    r = first.name().equals("updateOne") ? coll.updateOne(doc(a, 0), pipeline(a, 1), opt) : coll.updateMany(doc(a, 0), pipeline(a, 1), opt);
                } else {
                    r = first.name().equals("updateOne") ? coll.updateOne(doc(a, 0), doc(a, 1), opt) : coll.updateMany(doc(a, 0), doc(a, 1), opt);
                }
                yield value(st, update(r), start);
            }
            case "replaceOne" -> value(st, update(coll.replaceOne(doc(a, 0), doc(a, 1),
                    new ReplaceOptions().upsert(doc(a, 2).getBoolean("upsert", false)))), start);
            case "deleteOne" -> value(st, delete(coll.deleteOne(doc(a, 0))), start);
            case "deleteMany" -> value(st, delete(coll.deleteMany(doc(a, 0))), start);
            case "findOneAndUpdate" -> {
                Document o = doc(a, 2);
                FindOneAndUpdateOptions opt = new FindOneAndUpdateOptions()
                        .upsert(o.getBoolean("upsert", false))
                        .returnDocument("after".equals(o.getString("returnDocument")) || o.getBoolean("returnNewDocument", false)
                                ? ReturnDocument.AFTER : ReturnDocument.BEFORE);
                if (o.get("projection") instanceof Document p) {
                    opt.projection(p);
                }
                if (o.get("sort") instanceof Document s) {
                    opt.sort(s);
                }
                yield value(st, plain(coll.findOneAndUpdate(doc(a, 0), doc(a, 1), opt)), start);
            }
            case "findOneAndDelete" -> value(st, plain(coll.findOneAndDelete(doc(a, 0))), start);
            case "createIndex" -> {
                Document o = doc(a, 1);
                IndexOptions opt = new IndexOptions();
                if (o.getString("name") != null) {
                    opt.name(o.getString("name"));
                }
                opt.unique(o.getBoolean("unique", false));
                opt.sparse(o.getBoolean("sparse", false));
                if (o.get("partialFilterExpression") instanceof Document p) {
                    opt.partialFilterExpression(p);
                }
                if (o.get("expireAfterSeconds") instanceof Number n) {
                    opt.expireAfter(n.longValue(), java.util.concurrent.TimeUnit.SECONDS);
                }
                yield value(st, coll.createIndex(doc(a, 0), opt), start);
            }
            case "dropIndex" -> {
                if (a.get(0) instanceof String name) {
                    coll.dropIndex(name);
                } else {
                    coll.dropIndex(doc(a, 0));
                }
                yield value(st, Map.of("ok", 1), start);
            }
            case "getIndexes" -> docs(st, plainList(coll.listIndexes().into(new ArrayList<>())), maxDocs, start);
            case "drop" -> {
                coll.drop();
                yield value(st, true, start);
            }
            default -> throw new IllegalArgumentException("不支援 " + first.name());
        };
    }

    private Result find(MongoDatabase db, MongoCollection<Document> coll, Statement st, Document filter, Document projection,
                        List<Call> chain, int maxDocs, long start) {
        Document sort = null;
        Integer limit = null, skip = null;
        Object hint = null;
        boolean explain = false, count = false;
        String verbosity = "executionStats";
        for (Call c : chain) {
            switch (c.name()) {
                case "sort" -> sort = doc(c.args(), 0);
                case "limit" -> limit = number(c.args(), 0);
                case "skip" -> skip = number(c.args(), 0);
                case "project", "projection" -> projection = doc(c.args(), 0);
                case "explain" -> {
                    explain = true;
                    if (!c.args().isEmpty() && c.args().get(0) instanceof String v) {
                        verbosity = v;
                    }
                }
                case "count" -> count = true;
                case "hint" -> hint = c.args().isEmpty() ? null : c.args().get(0);
                default -> { }      // toArray、pretty：不影響結果
            }
        }
        if (explain) {
            Document cmd = new Document("find", coll.getNamespace().getCollectionName()).append("filter", filter);
            if (!projection.isEmpty()) cmd.append("projection", projection);
            if (sort != null) cmd.append("sort", sort);
            if (limit != null) cmd.append("limit", limit);
            if (skip != null) cmd.append("skip", skip);
            if (hint != null) cmd.append("hint", hint);
            return value(st, plain(db.runCommand(new Document("explain", cmd).append("verbosity", verbosity))), start);
        }
        if (count) {
            return value(st, coll.countDocuments(filter), start);
        }
        FindIterable<Document> it = project(coll.find(filter), projection);
        if (sort != null) it.sort(sort);
        if (skip != null) it.skip(skip);
        if (limit != null) it.limit(limit);
        if (hint instanceof Document h) it.hint(h);
        if (hint instanceof String h) it.hintString(h);
        return docs(st, plainList(it.into(new ArrayList<>())), maxDocs, start);
    }

    private Result aggregate(MongoDatabase db, MongoCollection<Document> coll, Statement st, List<Document> pipeline,
                             List<Call> chain, int maxDocs, long start) {
        boolean explain = chain.stream().anyMatch(c -> c.name().equals("explain"));
        if (explain) {
            Document cmd = new Document("aggregate", coll.getNamespace().getCollectionName())
                    .append("pipeline", pipeline).append("cursor", new Document());
            return value(st, plain(db.runCommand(new Document("explain", cmd).append("verbosity", "executionStats"))), start);
        }
        AggregateIterable<Document> it = coll.aggregate(pipeline).allowDiskUse(true);
        return docs(st, plainList(it.into(new ArrayList<>())), maxDocs, start);
    }

    private static FindIterable<Document> project(FindIterable<Document> it, Document projection) {
        return projection.isEmpty() ? it : it.projection(projection);
    }

    // ---------------------------------------------------------------- 結果

    private static Result docs(Statement st, List<?> all, int maxDocs, long start) {
        boolean truncated = all.size() > maxDocs;
        List<?> shown = truncated ? all.subList(0, maxDocs) : all;
        return new Result(st.text(), "docs", plainList(new ArrayList<>(shown)), all.size(), truncated, ms(start));
    }

    private static Result value(Statement st, Object value, long start) {
        return new Result(st.text(), "value", value, null, false, ms(start));
    }

    private static Map<String, Object> ack(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("acknowledged", true);
        m.put(key, value);
        return m;
    }

    private static Map<String, Object> update(UpdateResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("acknowledged", r.wasAcknowledged());
        m.put("matchedCount", r.getMatchedCount());
        m.put("modifiedCount", r.getModifiedCount());
        m.put("upsertedId", r.getUpsertedId() == null ? null : plain(r.getUpsertedId()));
        return m;
    }

    private static Map<String, Object> delete(DeleteResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("acknowledged", r.wasAcknowledged());
        m.put("deletedCount", r.getDeletedCount());
        return m;
    }

    /** BSON 值轉成 JSON 友善的 Java 物件：ObjectId → {$oid}、日期 → {$date}、Decimal128 → BigDecimal。 */
    public static Object plain(Object v) {
        if (v == null || v instanceof String || v instanceof Boolean || v instanceof Integer
                || v instanceof Long || v instanceof Double) {
            return v;
        }
        if (v instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, val) -> out.put(String.valueOf(k), plain(val)));
            return out;
        }
        if (v instanceof List<?> list) {
            return plainList(new ArrayList<>(list));
        }
        if (v instanceof ObjectId id) {
            return Map.of("$oid", id.toHexString());
        }
        if (v instanceof Date d) {
            return Map.of("$date", Instant.ofEpochMilli(d.getTime()).toString());
        }
        if (v instanceof Decimal128 d) {
            return d.bigDecimalValue();
        }
        if (v instanceof BigDecimal || v instanceof Number) {
            return v;
        }
        if (v instanceof org.bson.BsonValue b) {
            return b.toString();
        }
        return v.toString();
    }

    private static List<Object> plainList(List<?> list) {
        List<Object> out = new ArrayList<>(list.size());
        for (Object o : list) {
            out.add(plain(o));
        }
        return out;
    }

    // ---------------------------------------------------------------- 參數

    private static Document doc(List<Object> args, int i) {
        if (args.size() <= i || args.get(i) == null) {
            return new Document();
        }
        if (args.get(i) instanceof Document d) {
            return d;
        }
        throw new IllegalArgumentException("第 " + (i + 1) + " 個參數應該是物件 { … }");
    }

    @SuppressWarnings("unchecked")
    private static List<Document> pipeline(List<Object> args) {
        return pipeline(args, 0);
    }

    @SuppressWarnings("unchecked")
    private static List<Document> pipeline(List<Object> args, int i) {
        if (args.size() > i && args.get(i) instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Document)) {
                    throw new IllegalArgumentException("pipeline 陣列裡的每個 stage 都要是物件，例如 { $match: { … } }");
                }
            }
            return (List<Document>) list;
        }
        throw new IllegalArgumentException("aggregate 的參數要是陣列 [ { $match: … }, … ]");
    }

    private static List<Document> documents(List<Object> args, int i) {
        return pipeline(args, i);
    }

    private static String string(List<Object> args, int i) {
        if (args.size() > i && args.get(i) instanceof String s) {
            return s;
        }
        throw new IllegalArgumentException("第 " + (i + 1) + " 個參數應該是字串");
    }

    private static Integer number(List<Object> args, int i) {
        if (args.size() > i && args.get(i) instanceof Number n) {
            return n.intValue();
        }
        throw new IllegalArgumentException("應該是數字");
    }

    private static double ms(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000.0;
    }
}
