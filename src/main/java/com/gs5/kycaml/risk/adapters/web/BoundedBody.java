package com.gs5.kycaml.risk.adapters.web;

import com.gs5.kycaml.shared.kernel.Result;
import jakarta.servlet.http.HttpServletRequest;

import java.io.IOException;

/**
 * Reads a request body only after the caller was identified (and, for an evaluation, admitted), and
 * never more than its limit (D-09, design addendum A step 3).
 *
 * <p>A declared {@code Content-Length} above the limit is refused without reading a byte. Otherwise at
 * most limit + 1 bytes are read, so a chunked body with no declared length cannot exceed the limit
 * either: the one byte too many is what proves it is too large. A body that cannot be read at all is
 * not readable. The bytes then go to {@link StrictJson}, never to the application's mapper.
 */
final class BoundedBody {

    /** The largest evaluation request read, in bytes: 64 KiB (D-09). */
    static final int EVALUATION_LIMIT = 65_536;

    /** The largest decision request read, in bytes: 16 KiB (D-09). */
    static final int DECISION_LIMIT = 16_384;

    private BoundedBody() {
    }

    /** The body as one strictly read JSON object, or why it is refused. */
    static Result<JsonFields, BodyRefusal> read(HttpServletRequest request, int limit) {
        return bytes(request, limit).flatMap(StrictJson::object);
    }

    private static Result<byte[], BodyRefusal> bytes(HttpServletRequest request, int limit) {
        if (request.getContentLengthLong() > limit) {
            return Result.failure(new BodyRefusal.TooLarge());
        }
        try {
            var bytes = request.getInputStream().readNBytes(limit + 1);
            return bytes.length > limit
                    ? Result.failure(new BodyRefusal.TooLarge())
                    : Result.success(bytes);
        } catch (IOException unreadable) {
            return Result.failure(new BodyRefusal.NotReadable());
        }
    }
}
