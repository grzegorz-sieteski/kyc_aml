package com.gs5.kycaml.risk.adapters.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;

/**
 * The customer number exactly as the client wrote it in the path (R-03, design addendum C).
 *
 * <p>Spring's path matching strips {@code ;...} from a segment, so {@code C-1;x} would bind as
 * {@code C-1} and land on another customer. The number is therefore taken from the raw request URI:
 * the segment after {@code /api/risk-profiles/}, percent-decoded as UTF-8, with a literal {@code ;}
 * kept as part of it (BR-06 compares numbers exactly). Segments Tomcat refuses ({@code %00},
 * {@code %2F}, {@code %5C}, invalid UTF-8) never get here. A URI that cannot be read this way gives an
 * empty number, which the command or query refuses as missing or not found: never a guess.
 */
final class RawCustomerNumber {

    private static final String PREFIX = "/api/risk-profiles/";
    private static final String NONE = "";

    private RawCustomerNumber() {
    }

    static String of(HttpServletRequest request) {
        var uri = request.getRequestURI();
        var path = uri.startsWith(request.getContextPath()) ? uri.substring(request.getContextPath().length()) : uri;
        if (!path.startsWith(PREFIX)) {
            return NONE;
        }
        var rest = path.substring(PREFIX.length());
        var end = rest.indexOf('/');
        var segment = end < 0 ? rest : rest.substring(0, end);
        try {
            return UriUtils.decode(segment, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformedEscape) {
            return NONE;
        }
    }
}
