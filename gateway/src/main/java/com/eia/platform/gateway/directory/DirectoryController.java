package com.eia.platform.gateway.directory;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public read-only people and team directory.
 *
 * <p>The shell uses it to label assignees and work queues before a tenant is
 * known, so no {@code X-EIA-Tenant} header is required. The path does not
 * overlap the proxied {@code /api/v1/*} route families in
 * {@code application.yml}; it is answered locally by the gateway.
 */
@RestController
@RequestMapping("/api/v1/directory")
public class DirectoryController {

    private final DirectoryCatalog catalog;

    public DirectoryController(DirectoryCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public DirectoryResponse directory() {
        return catalog.snapshot();
    }
}
