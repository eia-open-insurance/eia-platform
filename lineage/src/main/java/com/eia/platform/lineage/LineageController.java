package com.eia.platform.lineage;

import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/lineage")
public class LineageController {
    @GetMapping("/{type}/{id}")
    public Map<String, Object> lineage(@PathVariable String type, @PathVariable String id) {
        return Map.of(
                "root", Map.of("type", type, "id", id),
                "nodes", List.of(),
                "edges", List.of()
        );
    }
}
