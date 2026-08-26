package com.kmultan.platform.web;

import java.io.IOException;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

import com.fasterxml.jackson.databind.ObjectMapper;

/** RFC 9457 problem responses, built the same way everywhere. */
public final class ProblemDetails {

    private ProblemDetails() {}

    public static ProblemDetail of(HttpStatus status, String title, String detail) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        problemDetail.setTitle(title);
        return problemDetail;
    }

    /** Writes a problem response directly, for filters and security handlers that run outside MVC. */
    public static void write(
            HttpServletResponse response, ObjectMapper objectMapper, HttpStatus status, String title, String detail)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), of(status, title, detail));
    }
}
