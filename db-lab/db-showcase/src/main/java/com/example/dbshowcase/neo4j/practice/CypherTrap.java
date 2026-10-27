package com.example.dbshowcase.neo4j.practice;

import java.util.List;

/** Neo4j 陷阱題（內容來自 neo4j/traps.yml）。每段 Cypher 各自在一個交易裡執行，最後 ROLLBACK。 */
public record CypherTrap(
        String id,
        String title,
        String question,
        List<Script> scripts,
        List<String> options,
        int answer,
        String explanation) {

    public record Script(String label, String commands) {
    }

    public record View(String id, String title, String question, List<Script> scripts, List<String> options) {
    }

    public View view() {
        return new View(id, title, question, scripts, options);
    }
}
