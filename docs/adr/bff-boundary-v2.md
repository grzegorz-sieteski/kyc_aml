# ADR: Backend-for-frontend boundary, second draft (v2)

> Replaces the trust model of `docs/adr/bff-boundary.md`, audited on 2026-11-11 as `[CHANGES REQUIRED]`. The first draft stays as the audit record. Do not implement from it.

- **Status:** Proposed. Section 2 follows the product owner's answers of 2026-11-11. Section 15 lists what the product owner still decides.
- **Date:** 2026-11-11
- **Scope:** the boundary between browser, BFF, risk service and onboarding system. No BFF code (the BFF is not in this repository), no user interface (D-03), no change to `domain/`.
- **Inputs:** AGENTS.md; spec D-03, D-07, D-09, D-13 to D-21, BR-01 to BR-04, BR-37, BR-39 to BR-46, A-02, A-03, A-04, A-27 (as amended by D-08); design section 10, addenda (3) and (4), both "Decision 2026-11-11" entries; the code in `risk/adapters/web/` and `application.yml` as committed.

## 1. Context and audit findings

What the service does today, from the code:

- It accepts any genuine Entra token for its issuer and audience. It does not know which application presented the token (`ConfiguredIdentityValidator`, `BearerTokenSecurityConfiguration`).
- `CallerClaims.callerOf` requires a `booking_centre` claim on every token, so an app-only onboarding token is refused.
- `application.yml` has no `server.ssl` setting. The service serves plain HTTP.
- One global key-fetch budget (Nimbus `rateLimited`, one fetch per 30 s) is shared by all callers. When a refresh is refused, `noKeyWhenLimited` returns no keys, so a flood stops every legitimate token at once. D-21 requires the opposite.

How each audit finding of the first draft is answered:

| Audit finding | Answer |
|---|---|
| Blocker: an `azp` allowlist locks onboarding out | D3, section 10: a registry per application; onboarding has its own registration and a client certificate |
| Blocker: a service 401 mapped to a BFF 503 | Withdrawn (product owner answer 3). Section 7 |
| Sign-in flow and where tokens live | Section 3: confidential OIDC client; the browser holds a cookie only |
| Key lookup runs before claim checks, so `azp` cannot close F1 | Section 9: mutual TLS first, a key budget per application, last known keys |
| No CSRF or body rule for a cookie session | Section 6: header-only token, checked before any body is read |
| Body bounds at the BFF | Section 6: bounded reads, no parsing, no multipart or form content |
| Redis holds confidential material | Section 5 |
| Cookie attributes and logout | Section 3 |
| TLS on the BFF-to-service hop | D4, sections 10 and 12 |
| Refusal per role through the BFF; role by operation by channel | Sections 7 and 8 |
| What the BFF logs | Section 11 |
| Three deployment controls, each with a test | Section 12 |
| Calling-client rule as a new identification condition | Not new: D-19 states it (section 10) |
| Wrong package and property prefix in the first draft | Section 10: the identity classes are in `risk/adapters/web` |

## 2. Decisions

- **D1.** Only the BFF holds tokens. The browser holds an opaque session cookie and never sees a token.
- **D2.** The BFF is a confidential OpenID Connect client. It runs the authorization code flow with PKCE and asks Entra for an access token for the service's scope, so the token is issued to the BFF's own registration. There is no on-behalf-of exchange; this replaces option (b) of the first draft. (To confirm in the tenant: `azp` is the BFF's client id.)
- **D3.** The service identifies the application as well as the user (D-19). A user token is accepted only when its `azp` is the BFF's registration. An app-only token is accepted only from a registered onboarding application, whose booking centre comes from its registration in configuration, never from a token.
- **D4.** TLS is required on every connection to the service (D-20). The service requires mutual TLS from the BFF and the onboarding system. A connection without a registered client certificate ends at the TLS handshake.
- **D5.** A user whose claims do not identify them gets the service's constant 401, passed through unchanged. The first draft's 401-to-503 mapping is removed.
- **D6.** Key fetches are budgeted per application, behind mutual TLS, and last known keys are kept when a refresh is refused (D-21). Together these close the key-fetch flood from the BFF's segment (section 9).
- **D7.** Redis holds only encrypted confidential material (section 5).
- **D8.** On every state-changing route the BFF checks the session, then the CSRF header, then reads the body within a bound. The BFF parses no body.

## 3. Sign-in, tokens and the cookie

**Sign-in.**

1. `GET /auth/login`: the BFF creates `state`, `nonce` and a PKCE verifier, stores them under a random key with a five-minute TTL, and redirects (302) to Entra's authorize endpoint. Tenant and scope (the service's) come from configuration. Challenge method S256.
2. `GET /auth/callback`: the pre-login record is deleted (single use) and `state` is checked. The code is redeemed at the token endpoint with a certificate credential (to confirm in the tenant that the registration accepts it). The ID token's `nonce` is checked, and the access token's signature is checked against the BFF's own key source. Any failure creates no session.
3. On success the BFF issues a new session id (no fixation), writes the session record (section 5), and answers 302 with `Set-Cookie`.

**Refresh.** The access token is refreshed before it expires, inside the server-side record. A refused refresh deletes the session; the next call gets the constant 401.

**Cookie.** Name with the `__Host-` prefix. `Secure`, `HttpOnly`, `SameSite=Strict`, `Path=/`, no `Domain`, no `Max-Age` (a session cookie). The lifetime is kept on the server. If the BFF uses Boot's servlet session settings, the attributes are `server.servlet.session.cookie.name`, `.secure`, `.http-only`, `.same-site`, `.path` and `.domain` (checked, section 14). `SameSite=Strict` means a link from another site opens logged out. Accepted.

**Logout.** `POST /auth/logout` with the CSRF header of section 6. There is no `GET` logout, so another page cannot log a user out. The BFF deletes the session record and the user's index entry, sends the cookie with the same attributes and `Max-Age=0`, then ends the Entra session by RP-initiated logout (to confirm in the tenant: the end-session endpoint).

**Lifetimes.** An idle and an absolute timeout are needed. The spec sets no values (section 15). Each Redis key's TTL is the shortest of the idle timeout, the absolute timeout and the refresh token's expiry.

## 4. What the BFF never does

1. No token in a URL, log, response or browser. The browser's `Authorization` header and cookies are not forwarded.
2. No response under `/api/**` is cached by the BFF, a CDN or a proxy. Every answer carries `Cache-Control: no-store`.
3. No change to a service refusal: status, `type`, `title`, `detail` and `instance` pass through byte for byte.
4. No decision the service makes. The BFF's two checks, session and size, follow the spec's own order (BR-37: identified, then request not too large and readable, then role). Role, input and existence are the service's.
5. The path is forwarded byte for byte. The routes are the three human routes of section 8; there is no list, search or enrichment route.
6. No automatic retry of `POST`. No identity read from a client header (`X-Forwarded-*` included).
7. No `Evaluate` route. Evaluate is the onboarding system's alone (spec, Command 1).

## 5. Redis as a store of confidential material

| Control | Decision |
|---|---|
| Content | Per session: encrypted access and refresh tokens, their expiry times, and the user index. Nothing else: no customer number, no risk data, no response. The ID token is not kept |
| Encryption at rest | AES-256-GCM per record in the application. The key comes from the secret store; its id is stored with the record, so keys rotate. Redis runs over TLS (client and property not checked, section 14). Persistence is off for this database, or the volume is encrypted |
| Expiry | Every key has a TTL: the shortest of idle timeout, absolute timeout and refresh expiry. Pre-login records: five minutes, single use |
| Access | A dedicated Redis ACL user for the BFF, limited to the `bff:` namespace. No `KEYS`, no `SCAN`, no administrative command from the application |
| Eviction | `maxmemory-policy noeviction`. A full store fails sign-in and refresh (fail closed). A live session is never evicted silently |
| One live session per user | Sign-in writes `bff:user:<HMAC of the actor id>` holding the session id, and deletes the session the index named before. The HMAC key is secret |
| Booking centre | A session is bound to the one booking centre in its token. No key contains a customer number or a booking centre. No key is shared between sessions (BR-04) |

The reading of "one session per user and per booking centre" used here is: one live session per user, and each session belongs to one booking centre. The product owner confirms it (section 15).

## 6. CSRF, the cookie session and body bounds

Order of checks on `POST` and any other state-changing route:

1. **Session.** Cookie to Redis record. No session gives the constant 401 (section 7). No body is read.
2. **Origin.** `Sec-Fetch-Site` must be `same-origin`; `Origin`, if present, must equal the BFF's origin. Failure is the BFF's constant 403. No body is read.
3. **CSRF token.** A synchronizer token is kept in the session record and returned as JSON by `GET /api/session`. The client sends it in header `X-CSRF-TOKEN`, Spring Security's default header name (checked, section 14). A mismatch gives the BFF's constant 403. No body is read. Spring's default `CsrfTokenRequestHandler` falls back to `request.getParameter` when the header is absent (checked in bytecode), which reads and parses a form body. The BFF therefore installs a handler that reads the header only.
4. **Body.** The limit is the service's limit for the route: 16 KiB for the decision route, the only human `POST` (D-09). A declared `Content-Length` above the limit is refused without reading. Otherwise `readNBytes(limit + 1)`, as `BoundedBody` does. Over the limit, the BFF answers the service's constant `request-too-large` problem, byte for byte. Within it, the bytes are forwarded with `Content-Type` and the correlation id. The BFF does not check `Content-Type`; the service's `StrictJson` decides readability (BR-37).
5. **Forward.** The service decides role, then input.

No multipart and no form content in the BFF (`spring.servlet.multipart.enabled: false`, as in the service). The Tomcat settings `server.tomcat.max-swallow-size` and `server.tomcat.max-http-form-post-size` are not bounds for JSON bodies, and the BFF does not rely on them.

## 7. Refusals through the BFF

| Situation | Browser receives | Basis |
|---|---|---|
| No session, expired, logged out | Constant 401, the same bytes as the service's caller-not-identified answer (instance `/api/risk-profiles`, `WWW-Authenticate: Bearer`, `no-store`) | BR-01, I-04 |
| Service answers 401 to the session's token (claims do not identify the user, token revoked) | The same 401, passed through unchanged. The BFF deletes the session | BR-01; product owner answer 3 |
| Entra refuses the refresh | The same 401; session deleted | D-13 |
| Service 403 (role may not perform the operation) | Unchanged | BR-02, BR-03 |
| Service 400, 404, 409, 413, 422 | Unchanged | Service catalogue |
| BFF origin or CSRF refusal | Constant 403 of the BFF; no reason, no risk word | Section 6 |
| Body over the BFF limit | The service's constant `request-too-large` | D-09 |
| Path not in the BFF's catalogue, including Evaluate | The service's constant 404 (`request-not-handled`, I-07) | Section 4 (7) |
| Service 5xx, unexpected status, unreachable, TLS failure | Constant 503, no service detail | Operational |
| `POST` timeout | Constant 504: outcome unknown, reload the view before repeating | No retry (section 4) |

The BFF's own refusals are copies of constant texts and name no reason that refers to risk (BR-41).

## 8. Role by operation by channel

Channels: **BFF**, a human caller with a user token issued to the BFF's registration (D-19). **Onboarding**, the onboarding application with an app-only token on a mutual TLS connection (D-17, D-19, D-20).

"Yes" is accepted by the service under A-02. "403" is the service's `role may not perform this operation`. "No route" means the BFF exposes no such route.

| Role | Channel | Evaluate | Decide: escalate | Decide: freeze | Decide: clear | Compliance view | Front-office status |
|---|---|---|---|---|---|---|---|
| ONBOARDING_SYSTEM | Onboarding | yes | 403 | 403 | 403 | 403 | yes (A-02 "every role"; section 15) |
| AML_ANALYST_L1 | BFF | no route | yes | 403 | 403 | yes | yes |
| AML_ANALYST_L2 | BFF | no route | yes | yes | yes | yes | yes |
| MLRO | BFF | no route | yes | yes | yes | yes | yes |
| FRONT_OFFICE | BFF | no route | 403 | 403 | 403 | 403 | yes (neutral message only, BR-40) |

Rules that follow: a human role in an app-only token is refused (401), and `ONBOARDING_SYSTEM` in a user token is refused (401). A user token whose `azp` is an onboarding application is refused (401), and an app-only token whose `azp` is the BFF is refused (401). A user token presented on the BFF's connection for Evaluate gets the service's 403.

## 9. The key-fetch flood from the BFF's segment

The first draft's answer was the network alone. The product owner does not accept a flood from the BFF's segment, so the code closes it. The chain, in order:

1. **Handshake first.** Mutual TLS refuses a host without a BFF or onboarding certificate before any HTTP request is read, and so before any token and any key fetch. Being on the BFF's segment is not enough.
2. **Budget per application.** The key source is chosen by the application whose certificate the connection presented (its subject, from the registry of section 10). Nimbus `rateLimited` is applied per source, so a flood by one application cannot spend the other's budget. The single global source is replaced.
3. **Last known keys.** D-21: the service keeps verifying with its last set while its cache allows. The cache TTL is 300 s (`JWKSourceBuilder.DEFAULT_CACHE_TIME_TO_LIVE`, checked). Nimbus `JWKSourceBuilder.outageTolerant` (checked in nimbus-jose-jwt 10.9.1) serves the last set during an outage. It must be placed in the builder chain so that a `RateLimitReachedException` counts as an outage. This is untested (section 14). The `noKeyWhenLimited` path is removed.
4. **The BFF forwards only tokens it obtained from Entra itself** (D2). Their `kid` is one Entra published for that moment, which a user cannot choose.

**Residual.** A compromised BFF (its host and its certificate) can still send tokens with unknown `kid`s. That spends only the BFF's own budget. Onboarding's budget and the keys already held keep working (D-21). This is not a flood from the segment. It is recorded in section 13.

## 10. Identity in the service: the changes

Package: `risk/adapters/web`, where `CallerClaims`, `ConfiguredIdentityValidator`, `IdentitySettings` and `BearerTokenSecurityConfiguration` already are. `domain/` and the resources do not change.

New configuration (names are proposals; all required, and startup fails when one is missing or blank, as I-01):

- `app.identity.bff-client-id` (`BFF_CLIENT_ID`): the BFF's registration.
- `app.identity.onboarding-applications.<client-id>`: per onboarding application, `booking-centre` (in scope, D-06), `certificate-subject`, and the fixed role `ONBOARDING_SYSTEM`.

Rules, in the order of the token check:

1. Signature, `exp`, `iss`, `aud` as today (I-01, I-02).
2. The application: `azp` (to confirm in the tenant) must be the BFF's client id or a key of the onboarding map. Otherwise 401.
3. The certificate: the subject of the connection's certificate (request attribute `jakarta.servlet.request.X509Certificate`, checked as Tomcat's `SSLSupport.CERTIFICATE_KEY`) must be the one registered for that application. This is a second check after the handshake (D4). Otherwise 401.
4. The token's kind. A user token carries `scp` (to confirm in the tenant); an app-only token does not.
   - BFF and user token: the role comes from `roles`, exactly one human role (D-15); the booking centre from the `booking_centre` claim (D-16); the actor from `oid`.
   - Onboarding and app-only token: the role is `ONBOARDING_SYSTEM` and must be in `roles` (to confirm in the tenant); the booking centre is the registration's, and no booking-centre claim is read; the actor from `oid` (to confirm: the service principal's id).
   - Any other combination: 401.

`CallerClaims` gains the onboarding branch. `ActorContextResolver.resolve()` and `identify()` keep their signatures. The per-application key sources of section 9 are built in `BearerTokenSecurityConfiguration`.

Listener: `server.ssl.enabled: true` and `server.ssl.client-auth: need` (checked: `Ssl` field `enabled`, enum `Ssl$ClientAuth.NEED`, metadata name). The truststore holds the BFF's and the onboarding system's certificate authority, set through the `Ssl` field `trustStore` (checked). The kebab-case property name follows Boot's binding; the test of section 12 confirms it.

Tests (stage 3, red first):

- A token with another `azp`: the constant 401, byte-identical to the other 401s (I-04).
- An onboarding app-only token without `booking_centre`, on its own certificate: accepted with the configured centre.
- An onboarding token on the BFF's certificate; a user token with an onboarding `azp`; an app-only token with the BFF's `azp`; a human role in an app-only token; `ONBOARDING_SYSTEM` in a user token. Each is a 401.
- Two applications: unknown-`kid` floods from one leave the other's key source unaffected.
- Startup fails when `BFF_CLIENT_ID` or an onboarding entry is blank. `ProductionIdentitySettingsTest` fails if the new settings leave `application.yml`.

## 11. What the BFF logs (BR-43)

One line per request: timestamp, method, route template (for example `/api/risk-profiles/{customerNumber}/decisions`, never the filled-in path), status, duration, a generated correlation id, and the constant refusal code where there is one.

Never logged: the customer number, any path that carries one, any token, cookie, CSRF value or `Authorization` header, any body, any claim, the actor id (`oid`), the booking centre, or any risk word. The Tomcat access log is off, or its pattern has no request path (`server.tomcat.accesslog.enabled` and `.pattern`, checked). No `DEBUG` on `org.springframework.security` or `org.springframework.web` (I-08); `spring.mvc.log-request-details` stays off (checked). Whether the customer number may appear in the BFF's logs is an open decision (section 15).

## 12. Deployment controls, each with a test

These are deployment controls, like the D-11 controls in AGENTS.md. Code cannot check them. Each runs as a test at every deployment.

1. **No route from the internet to the service.** Test: a connection to the service's port from an external test host is refused.
2. **Ingress only from the BFF and onboarding segments.** Test: a connection from an unlisted internal host is refused; one from a listed segment without a registered certificate is refused at the handshake.
3. **Mutual TLS on the service.** Test: a handshake without a client certificate fails; one with an unregistered certificate fails; the BFF's and the onboarding system's certificates succeed.

Also: the BFF refuses a service certificate that is not in its truststore (test: a certificate from an unknown authority is refused). The 404 for `/.well-known/oauth-protected-resource/**` (I-07) still holds.

## 13. Consequences and residual risks

- F1 from the BFF's segment is closed (section 9). What remains is a compromised BFF host or certificate, which can send unknown `kid`s to its own budget. Mitigations: the certificate key in a vault, short token lifetimes, alerting on the BFF's refusal rate.
- A compromised BFF can act for its users within their rights and read their views. This is the largest residual risk, and the reason the BFF's registration is limited to the service's scope and to assigned users.
- Redis loss or a full store ends every session. Accepted: failure is closed.
- The on-behalf-of exchange of the first draft is gone: one token flow fewer, one round trip fewer per session. The cost is an Entra registration for the BFF with a certificate credential.
- Certificates need issuance, rotation and revocation. Revocation means removing the certificate from the truststore or the registry. No certificate revocation list is part of this design.
- A change of onboarding booking centre is a configuration change and a restart, not a token change. Accepted.

## 14. Verification status

**Checked against the jars in `~/.m2` (Boot 4.1.2, Spring Security 7.1.2, Nimbus 10.9.1, Tomcat 11.0.27) or in the repository:**

- `spring.security.oauth2.resourceserver.jwt.issuer-uri`, `.audiences`, `.jwk-set-uri`, `.jws-algorithms`: fields of `OAuth2ResourceServerProperties$Jwt` (`spring-boot-security-oauth2-resource-server-4.1.2.jar`).
- `server.ssl.enabled`, `server.ssl.client-auth`: the `Ssl` fields `enabled`, `clientAuth`, `trustStore`, `keyStore`, `bundle`; enum `Ssl$ClientAuth` with `NONE`, `WANT`, `NEED` (`spring-boot-web-server-4.1.2.jar`); property names in the Boot metadata.
- `server.servlet.session.cookie.name`, `.secure`, `.http-only`, `.same-site`, `.path`, `.domain`, `.max-age`, and `server.servlet.session.timeout`: Boot metadata (`spring-boot-web-server-4.1.2.jar`).
- `server.tomcat.max-swallow-size`, `server.tomcat.max-http-form-post-size`, `server.tomcat.accesslog.enabled`, `server.tomcat.accesslog.pattern`: Boot metadata (`spring-boot-tomcat-4.1.2.jar`).
- `spring.servlet.multipart.enabled`, `spring.mvc.log-request-details`: Boot metadata.
- `HttpSessionCsrfTokenRepository`: default header `X-CSRF-TOKEN`, parameter `_csrf` (`spring-security-web-7.1.2.jar`). `CsrfTokenRequestHandler.resolveCsrfTokenValue`: header first, then `getParameter` when the header is null (bytecode). `CookieCsrfTokenRepository` and `XorCsrfTokenRequestAttributeHandler` exist in the same jar.
- `JWKSourceBuilder`: `outageTolerant(boolean)`, `rateLimited(long)`, `cache(boolean)`, `DEFAULT_CACHE_TIME_TO_LIVE` = 300000, `DEFAULT_RATE_LIMIT_MIN_INTERVAL` = 30000 (`nimbus-jose-jwt-10.9.1.jar`).
- `SSLSupport.CERTIFICATE_KEY` = `jakarta.servlet.request.X509Certificate` (`tomcat-embed-core-11.0.27.jar`).
- In the repository: `application.yml` has no `server.ssl` and no session setting; `CallerClaims` requires `booking_centre`; `noKeyWhenLimited` returns no keys; `BoundedBody` reads `limit + 1` bytes. The repository references `RateLimitReachedException`; the code was not compiled here.

**Not checked:**

- Spring Session, the Redis client, Redis configuration (ACL, `maxmemory-policy`, persistence), and the Spring Security OAuth2 client. Their jars are not in `~/.m2`, so the sign-in classes and their properties are not named here.
- Whether `OutageTolerantJWKSetSource` serves the last set when its wrapped source throws `RateLimitReachedException`. Stage 3 must test this before D-21 is claimed.
- Whether the JWT decoder calls the key selector before the claim validators (stated in the first draft; unverified in source).

**To confirm in the Entra tenant (not facts):**

- `azp` in an authorization-code access token for the service's scope (expected: the BFF's client id).
- `azp` and `azpacr` for a certificate credential, and which credential type `azpacr` reports.
- `scp` present in user tokens and absent from app-only tokens; whether `idtyp` is emitted.
- `roles` in a user token carries the user's app roles; in an app-only token it carries `ONBOARDING_SYSTEM`; whether an app-only token carries any booking-centre claim.
- Access and refresh token lifetimes; the RP-initiated logout endpoint.
- `accessTokenAcceptedVersion` is 2 for the service's registration (design I-10, unchanged).

## 15. Decision requested

The product owner must still decide:

1. **Entra model.** One confidential BFF registration with a certificate credential, and one registration per onboarding application with its booking centre in configuration (the first draft's proposal for orchestrator question 2).
2. **Human roles.** D-19 says "the four analyst and officer roles". This draft reads them as the four non-onboarding roles: L1, L2, MLRO and Front-Office Employee.
3. **Front-office status for the onboarding system.** A-02 says "every role". Confirm, or restrict it to the BFF.
4. **One session per user and per booking centre** (section 5). Confirm: one live session per user, each bound to one booking centre.
5. **Session lifetimes.** The idle and absolute timeouts. The spec sets no values.
6. **Owner of the BFF:** its code, its Entra registration, its certificates and its Redis (the first draft's question 3).
7. **Customer number in the BFF's logs** (section 11). BR-43 does not list it. Confirm it stays out.
8. **Handshake refusal.** A connection without a registered certificate ends at the TLS handshake with no HTTP answer. Confirm that BR-01's "nothing is returned" covers it (section 2, D4).
