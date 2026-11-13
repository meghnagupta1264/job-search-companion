package com.example.webapp.system;

import com.example.webapp.config.SecurityConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SystemController.class)
@Import(SecurityConfiguration.class)
class SystemControllerTests {
    @Autowired
    MockMvc mvc;

    @Test
    void healthIsPublicAndReturnsJson() throws Exception {
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.application").value("Job Search Companion"));
    }

    @Test
    void otherApiRoutesRequireAuthentication() throws Exception {
        mvc.perform(get("/api/applications"))
                .andExpect(status().isUnauthorized());
    }
}
