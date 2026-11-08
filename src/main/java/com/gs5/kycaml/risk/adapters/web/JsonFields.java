package com.gs5.kycaml.risk.adapters.web;

import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Optional;

/**
 * One JSON object of a request, read field by field and by JSON type only (D-12, design addendum A
 * step 5). A field is present only when its node has the expected type: a string for text, a boolean
 * for yes or no, an object, an array, or an integral number that fits a {@code long}. A node of any
 * other type is absent, so the field's own refusal applies, and nothing is ever coerced: {@code "1"}
 * is not a number, {@code 0} is not a boolean, {@code 1.0} is not a whole number.
 */
final class JsonFields {

    private final JsonNode object;

    JsonFields(JsonNode object) {
        this.object = object;
    }

    Optional<String> text(String name) {
        return object.optional(name).filter(JsonNode::isString).map(JsonNode::stringValue);
    }

    Optional<Boolean> yesOrNo(String name) {
        return object.optional(name).filter(JsonNode::isBoolean).map(JsonNode::booleanValue);
    }

    Optional<Long> wholeNumber(String name) {
        return object.optional(name)
                .filter(node -> node.isIntegralNumber() && node.canConvertToLong())
                .map(JsonNode::longValue);
    }

    Optional<JsonFields> object(String name) {
        return object.optional(name).flatMap(JsonFields::asObject);
    }

    /** The elements of an array; an element that is not an object is kept, as an empty position. */
    Optional<List<Optional<JsonFields>>> objects(String name) {
        return object.optional(name)
                .filter(JsonNode::isArray)
                .map(array -> array.values().stream().map(JsonFields::asObject).toList());
    }

    private static Optional<JsonFields> asObject(JsonNode node) {
        return node.isObject() ? Optional.of(new JsonFields(node)) : Optional.empty();
    }
}
