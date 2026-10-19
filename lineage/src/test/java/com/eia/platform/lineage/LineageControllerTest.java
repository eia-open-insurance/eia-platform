package com.eia.platform.lineage;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(LineageController.class)
class LineageControllerTest {
    @Autowired MockMvc mvc;

    @Test
    void returnsEmptyGraph() throws Exception {
        mvc.perform(get("/api/v1/lineage/party/123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.root.type").value("party"))
                .andExpect(jsonPath("$.nodes").isArray());
    }
}
