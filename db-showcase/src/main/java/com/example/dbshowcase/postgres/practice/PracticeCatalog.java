package com.example.dbshowcase.postgres.practice;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.example.dbshowcase.common.YamlContent;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 練習題與陷阱題的題庫。 */
@Component
public class PracticeCatalog {

    private final List<Exercise> exercises;
    private final List<Trap> traps;

    public PracticeCatalog(ObjectMapper mapper) {
        this.exercises = YamlContent.load("postgres/exercises.yml", Exercise.class, mapper);
        this.traps = YamlContent.load("postgres/traps.yml", Trap.class, mapper);
    }

    public List<Exercise> exercises() {
        return exercises;
    }

    public List<Trap> traps() {
        return traps;
    }

    public Exercise exercise(String id) {
        return exercises.stream().filter(e -> e.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到這題：" + id));
    }

    public Trap trap(String id) {
        return traps.stream().filter(t -> t.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "找不到這題：" + id));
    }
}
