package com.example.dbshowcase.postgres;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** PostgreSQL 的 API，全部掛在 /api/postgres 底下。 */
@RestController
@RequestMapping("/api/postgres")
public class PostgresController {

    public record SqlRequest(String sql) {
    }

    private final SchemaService schemaService;
    private final DemoQueryCatalog catalog;
    private final QueryService queryService;
    private final SandboxService sandboxService;

    public PostgresController(SchemaService schemaService, DemoQueryCatalog catalog,
                              QueryService queryService, SandboxService sandboxService) {
        this.schemaService = schemaService;
        this.catalog = catalog;
        this.queryService = queryService;
        this.sandboxService = sandboxService;
    }

    @GetMapping("/schema")
    public List<SchemaService.TableInfo> schema() {
        return schemaService.tables();
    }

    @GetMapping("/queries")
    public List<DemoQuery> queries() {
        return catalog.all();
    }

    @PostMapping("/queries/{id}/run")
    public QueryResult runDemo(@PathVariable String id) {
        DemoQuery q = catalog.find(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到這個查詢：" + id));
        return queryService.run(q.sql());
    }

    @PostMapping("/sql")
    public QueryResult runCustom(@RequestBody SqlRequest request) {
        return queryService.run(request.sql());
    }

    /** 寫入沙盒：可以多句、可以 INSERT / UPDATE / DELETE，最後一律 ROLLBACK。 */
    @PostMapping("/sandbox")
    public SandboxService.SandboxResult sandbox(@RequestBody SqlRequest request) {
        return sandboxService.run(request.sql());
    }
}
