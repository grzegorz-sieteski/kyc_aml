package com.gs5.kycaml.risk.adapters.web;

import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;

/**
 * Reads a request body only once a resource decides to look at it. A body that cannot be read is
 * absent, which the command's own parsing then refuses as missing input.
 */
final class RequestBodies {

    private RequestBodies() {
    }

    static <T> Optional<T> read(JsonMapper json, @Nullable String body, Class<T> type) {
        if (body == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(json.readValue(body, type));
        } catch (JacksonException unreadable) {
            return Optional.empty();
        }
    }
}
