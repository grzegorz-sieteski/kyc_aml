package com.gs5.kycaml.risk.adapters.web;

import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Signs RS256 tokens with plain JDK crypto and serves the matching JWK set over local HTTP, the way Entra
 * ID would (design addendum 2026-11-09 (3)). No token library: the test must not share code with the
 * implementation it checks.
 */
final class TestTokens {

    static final String ISSUER = "https://login.microsoftonline.com/00000000-0000-0000-0000-0000000000aa/v2.0";
    static final String AUDIENCE = "api://kyc-aml-test";
    static final String DE_CENTRE = "0de00000-0000-0000-0000-00000000000d";
    static final String OUT_OF_SCOPE_CENTRE = "05000000-0000-0000-0000-000000000005";

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    final KeyPair keys;
    final String kid;
    /** How many times the served JWK set was fetched. */
    final java.util.concurrent.atomic.AtomicInteger fetches = new java.util.concurrent.atomic.AtomicInteger();

    private TestTokens(KeyPair keys, String kid) {
        this.keys = keys;
        this.kid = kid;
    }

    static TestTokens generate(String kid) {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return new TestTokens(generator.generateKeyPair(), kid);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Valid claims for one role in the German booking centre; tests change what they attack. */
    static Map<String, Object> claims(String role) {
        var now = Instant.now().getEpochSecond();
        var claims = new LinkedHashMap<String, Object>();
        claims.put("iss", ISSUER);
        claims.put("aud", AUDIENCE);
        claims.put("iat", now - 10);
        claims.put("nbf", now - 10);
        claims.put("exp", now + 600);
        claims.put("oid", "11111111-2222-3333-4444-555555555555");
        claims.put("roles", List.of(role));
        claims.put("booking_centre", DE_CENTRE);
        return claims;
    }

    String sign(Map<String, Object> claims) {
        return sign(Map.of("alg", "RS256", "typ", "JWT", "kid", kid), claims);
    }

    String sign(Map<String, Object> header, Map<String, Object> claims) {
        var signingInput = URL.encodeToString(JSON.writeValueAsBytes(header)) + "."
                + URL.encodeToString(JSON.writeValueAsBytes(claims));
        try {
            var signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(keys.getPrivate());
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + URL.encodeToString(signature.sign());
        } catch (java.security.GeneralSecurityException broken) {
            throw new IllegalStateException(broken);
        }
    }

    /** A token with alg "none" and no signature. */
    static String unsigned(Map<String, Object> claims) {
        return URL.encodeToString(JSON.writeValueAsBytes(Map.of("alg", "none", "typ", "JWT"))) + "."
                + URL.encodeToString(JSON.writeValueAsBytes(claims)) + ".";
    }

    String jwks() {
        var key = (RSAPublicKey) keys.getPublic();
        return JSON.writeValueAsString(Map.of("keys", List.of(Map.of(
                "kty", "RSA", "use", "sig", "alg", "RS256", "kid", kid,
                "n", unsigned(key.getModulus()), "e", unsigned(key.getPublicExponent())))));
    }

    private static String unsigned(BigInteger value) {
        var bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return URL.encodeToString(bytes);
    }

    /** Serves {@link #jwks()} at {@code /keys} on 127.0.0.1; returns the URI. */
    String serveJwks() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            var body = jwks().getBytes(StandardCharsets.UTF_8);
            server.createContext("/keys", exchange -> {
                fetches.incrementAndGet();
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            server.setExecutor(null);
            server.start();
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/keys";
        } catch (java.io.IOException unavailable) {
            throw new IllegalStateException(unavailable);
        }
    }
}
