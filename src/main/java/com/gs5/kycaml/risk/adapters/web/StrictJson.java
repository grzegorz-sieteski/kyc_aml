package com.gs5.kycaml.risk.adapters.web;

import com.gs5.kycaml.shared.kernel.Result;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * The strict reader of request bodies (D-12, design addendum A step 4). It is neither the
 * application's {@code JsonMapper} nor the mapper that reads stored documents, so loosening either of
 * those can never loosen what a client may send.
 *
 * <p>The bytes are decoded as UTF-8, refusing malformed input rather than replacing it, and parsed
 * into a tree with duplicate keys refused and trailing content refused. Anything that is not exactly
 * one JSON object is not readable: an empty body, an array, {@code null}, a scalar, malformed JSON, a
 * key named twice, content after the object, or nesting deeper than Jackson's own limit.
 */
final class StrictJson {

    private static final JsonMapper READER = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private StrictJson() {
    }

    static Result<JsonFields, BodyRefusal> object(byte[] bytes) {
        try {
            var text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
            return Result.fromOptional(Optional.ofNullable(READER.readTree(text))
                            .filter(JsonNode::isObject)
                            .map(JsonFields::new),
                    BodyRefusal.NotReadable::new);
        } catch (CharacterCodingException | JacksonException unreadable) {
            return Result.failure(new BodyRefusal.NotReadable());
        }
    }
}
