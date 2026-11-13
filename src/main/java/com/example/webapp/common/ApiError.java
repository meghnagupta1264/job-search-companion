package com.example.webapp.common;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/** One error shape for MVC validation and Spring Security's earlier filter errors. */
public record ApiError(String code, String message, Map<String, String> fieldErrors) {
    public ApiError(String code, String message) { this(code, message, Map.of()); }

    public static void write(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(JsonMapper.builder().build().writeValueAsString(new ApiError(code, message)));
    }
}
