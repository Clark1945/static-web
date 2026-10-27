package com.example.dbshowcase.common;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

import org.springframework.core.io.ClassPathResource;
import org.yaml.snakeyaml.Yaml;

import com.fasterxml.jackson.databind.ObjectMapper;

/** 讀取 classpath 上的 YAML 題庫，轉成指定的 record 清單。 */
public final class YamlContent {

    private YamlContent() {
    }

    public static <T> List<T> load(String path, Class<T> type, ObjectMapper mapper) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            List<?> raw = new Yaml().load(in);
            return raw.stream().map(item -> mapper.convertValue(item, type)).toList();
        } catch (IOException e) {
            throw new UncheckedIOException("讀不到題庫：" + path, e);
        }
    }
}
