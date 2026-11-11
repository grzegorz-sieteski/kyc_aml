# ADR: Backend-for-frontend boundary for customer-facing callers

- **Status:** Proposed. The architect chooses the trust model (section 2). Three product-owner answers are
  needed before any BFF or identity code (section 8).
- **Date:** 2026-11-11
- **Scope:** the boundary only. No BFF code, no user interface (D-03). It carries out the direction of
  "Decision 2026-11-11" in `docs/adr/customer-risk-scoring-design.md` (F1) and spec D-17.
- **Inputs:** AGENTS.md; spec D-01, D-03, D-05, D-13 to D-17, BR-01 to BR-04, BR-37, BR-39 to BR-41,
  BR-43; design section 10 (web edge), "Addendum 2026-11-09 (3)" (identity source), "(4)" (findings I-01
  to I-10, F1 trade-off) and the two "Decision 2026-11-11" entries.

## 1. Context

Customer-facing callers (analysts, MLRO, front office) reach this service only through a BFF that holds
their session. The BFF calls the service itself. The onboarding system is machine-to-machine on the
internal network and does not go through the BFF (D-17); it appears here only in the network controls
(section 5).

Today the service accepts any Entra token that is genuine, current, for its issuer and for its audience
(design, Addendum 2026-11-09 (3)). Any caller that can reach the service can present one. That is the
F1 problem: the JWK key fetch budget is shared by all callers, and a caller who can reach the service and
sends tokens with random unknown `kid` values keeps the budget spent (design, Addendum 2026-11-11, F1).

Forces:

- A user's Entra token copied from a browser, a log or a support tool must not be enough to call the service.
- Role and booking centre come from claims (D-14 to D-16). What the BFF forwards must carry the user's own
  claims as Entra issued them, not claims the BFF writes.
- The BFF must decide nothing the service decides. The order of refusals is fixed (BR-03, BR-37), and a
  refusal to a front-office caller must reveal no risk (BR-41).

## 2. Decision: trust model

Three options were considered.

- **(a) Forward the user's token.** The BFF passes the user's own Entra token; the service is unchanged.
- **(b) On-behalf-of (OBO).** The BFF is a confidential client. It exchanges the user's token for a token
  issued for the service. The token keeps the user's claims (`oid`, role, booking centre) and names the BFF
  as the calling client in `azp`. The service accepts only that calling client, in addition to today's checks.
- **(c) mTLS plus the user token.** The BFF presents a client certificate to the service, and the user token
  is still sent.

| | (a) forward | (b) OBO + calling-client check | (c) mTLS + token |
|---|---|---|---|
| A copied user token works against the service | Yes, from any host that reaches it | No: a browser token names the front-end client in `azp`, not the BFF, and is refused | Yes from any host holding the BFF certificate; no without it |
| The service can tell the BFF from others | No | Yes, by `azp` | Yes, by certificate |
| Where the user's claims come from | Entra, as today | Entra, copied into the OBO token by Entra (the BFF cannot write them) | Entra, as today |
| New to operate | nothing | BFF client credential (certificate preferred to secret); one Entra setting; one validator in the service | certificate issuance, rotation and truststore on the service |
| Closes F1 | No (section 2.2) | No (section 2.2) | No (section 2.2) |

**Decision: (b).** It is the only option under which the service knows which application is calling and
refuses a user token that was copied out of a browser. Option (a) identifies nobody but the user, so a copied
token is enough. Option (c) adds certificate lifecycle and still needs the user token; it can be added on top
of (b) later (open question 4).

### 2.1 Claims the service trusts after (b)

- `oid` (D-14): the user's object id, as Entra put it in the OBO token.
- The role claim `roles` (D-15): exactly one `ActorRole`, as today.
- The booking-centre claim `booking_centre` (D-16): canonical UUID, in scope, as today.
- `azp` (new): the calling client must equal the BFF's client id. `azp` answers "which application
  called", not "which user" or "which role". It is never a source of role or booking centre.
- The service reads no identity from any header or body. A BFF that sends a different booking centre in a
  header gets nothing from it.

The BFF is trusted to present the session holder's token and nothing else. It is not trusted to say who
the user is or what the user may do: Entra decides both, and the service checks Entra's claims.

### 2.2 What (b) closes and what it does not

Closes: a user token used directly against the service, from any host that can reach it, including a host on
the onboarding segment or a compromised internal host. The OBO token is also short-lived and never leaves the
BFF's server side.

Does **not** close:

- **F1.** The key lookup happens during signature verification, before the claim checks (Spring's
  `NimbusJwtDecoder` behaviour as designed; not checked in source here). A token with an unknown `kid` makes a
  key fetch before `azp` is looked at. Only the network controls of section 5 close F1. The `azp` check is
  not a rate control.
- **A compromised BFF.** It holds sessions and can obtain OBO tokens for any user it has a session for. That is
  impersonation within the user's own rights, for as long as the compromise lasts.
- **An OBO token stolen from the BFF's memory** and used from inside the network during its lifetime.
- **Internal flooding** of the key-fetch budget by a caller who already reaches the internal network. Accepted
  for onboarding (D-17); for the BFF, see section 5.3.

### 2.3 Configuration

| Property | Status | Source |
|---|---|---|
| `spring.security.oauth2.resourceserver.jwt.audiences` (`TOKEN_AUDIENCE`) | existing; the OBO token's `aud` is this service | Verified: field `audiences` of `OAuth2ResourceServerProperties$Jwt`, Boot 4.1.2 |
| `spring.security.oauth2.resourceserver.jwt.issuer-uri` (`ISSUER_URI`) | existing | Verified: field `issuerUri` (same class) |
| `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` (`JWK_SET_URI`) | existing | Verified: field `jwkSetUri` (same class) |
| `app.identity.calling-client-claim`, default `azp` | proposed; the name follows D-14 | new |
| `app.identity.allowed-calling-client` (`BFF_CLIENT_ID`) | proposed; required, startup fails when missing or blank (I-01) | new |

The `azp` check is a `JwtClaimValidator` (class present in `spring-security-oauth2-jose` 7.1.2, verified),
added to the same delegating validator as the I-01 checks. No Boot property exists for the calling client,
so the validator is code.

## 3. What the BFF must never do

1. **Tokens.** Never read a bearer token from a query string or a body. The browser holds an opaque session
   cookie; the BFF holds the OBO token server side. No token in a URL, a log, or a response.
2. **Cache confidential answers.** No response under `/api/**` is cached by the BFF, a CDN or a reverse
   proxy. The service's `Cache-Control: no-store` is passed through, and the BFF sets it on its own answers.
   OBO tokens may live in the BFF's memory per session, never in a shared store.
3. **Change refusal texts.** Status, `type`, `title`, `detail` and `instance` of a service problem pass
   through byte-identical. No translation, no appended explanation, no status remapped (the exceptions are
   in section 4 and are the BFF's own answers, not the service's).
4. **Decide what the service decides.** No role check beyond routing, no validation of the customer number
   or the body, no look-up that tells whether a customer exists. BR-03 and BR-37 fix the order; a BFF
   pre-check would answer first, and in its own words.
5. **Expose customer numbers beyond what the user sees.** Only the four routes of section 10 of the design,
   at the same paths. No list, search or enrichment endpoint. The front-office caller receives what the
   front-office status route returns and nothing else.
6. **Normalise the request.** The path is forwarded byte for byte: no decoding, re-encoding or
   normalisation. The service's firewall decisions depend on the exact bytes (Addendum 2026-11-09 (4),
   I-03; `RiskApiHardeningIT.Firewall`). The body is forwarded unchanged. Only `Content-Type`, `Accept` and
   the correlation id are copied; the browser's `Authorization` header and cookies are dropped.
7. **Log bodies or identities.** No request or response body, no OBO or session token, no claim, no path
   with a customer number. The log holds the route template, method, status, duration and correlation id.
   BR-43 names the risk content and the caller's identity; the customer number is personal data (D-05) and
   the BFF treats it the same way. The product owner should confirm this reading (open question 6).
8. **Retry writes.** No automatic retry of `POST` evaluations or decisions. Their idempotency is not
   established in the design; a retry after a timeout could repeat a decision.
9. **Trust client-set identity headers.** No `X-Forwarded-*` or similar header is read for identity or for
   the booking centre.

## 4. Failure answers

The BFF's own answers use a fixed catalogue, reviewed like the service's refusal texts. None names a risk,
a score, a track or a decision. None differs by customer number or by role.

| Situation | Browser receives | Why |
|---|---|---|
| No BFF session, or session expired | `401`, with the same bytes the service gives for caller not identified (instance `/api/risk-profiles`, `WWW-Authenticate: Bearer`, `no-store`) | I-04: a caller must not tell which component refused |
| Entra refuses the OBO exchange (user disabled, not assigned) | The same `401` | BR-01: the caller is not identified |
| Service answers `401` to a request with a valid BFF session (wrong audience, wrong `azp`, BFF credential expired) | `503` with the BFF's constant "service temporarily unavailable" text; WARN log with the exception type only | A platform fault is not the user's fault. Forwarding `401` would send the user into a login loop and wrongly say their identity is not accepted |
| Service answers `400`, `403`, `404`, `409` or `422` | Unchanged | Service texts; BR-02, BR-03, BR-04, BR-41 |
| Service answers `5xx`, or an unexpected status | `503`, constant text, no service detail | Nothing of the service's internals leaves the BFF |
| Service unreachable (refused, DNS, TLS) or `GET` timeout | `503`, same constant text | Identical for every customer number, so it is no existence test |
| `POST` timeout | `504`, constant text saying the outcome is unknown and the view must be reloaded before repeating | The BFF does not retry (section 3.8) |
| BFF down or the load balancer cannot reach it | `502` or `503` from the infrastructure, with no body from the BFF or the service | Outside the BFF code; the deployment must check that its body holds nothing from the service |

**Front-office callers (BR-41).** Each of these answers contains no risk word and is the same for every
customer number and every role. The service's own refusals to a front-office caller already meet BR-41 and
pass unchanged. Timing differences between a found and a missing customer are not covered by this ADR;
the BFF adds no delay of its own.

## 5. Network controls the deployment must enforce

These are deployment controls, like the D-11 controls in AGENTS.md. Code cannot check them.

1. **Service.** No route from the internet. Ingress allowed only from the BFF's workload or subnet and from
   the onboarding segment (D-17). Default deny. Checked at deployment: a connection from outside is refused,
   and so is one from an internal host not on the list.
2. **BFF.** The only component reachable from the internet. It terminates TLS and enforces rate limits per
   session. Its own key fetches are its own budget: the browser sends a session cookie, not a JWT, so no
   attacker-chosen `kid` reaches the BFF's validation.
3. **Onboarding.** Internal network only (D-17). It calls the service directly, not through the BFF. Its
   identity (app-only token, possibly without a booking centre) is open (open question 2).
4. **Service egress.** Only the Entra key-set URL and the database. The key fetch uses `HttpURLConnection`,
   so it follows the JVM proxy properties; the proxy allows only the Entra host.
5. **Management endpoints.** `pom.xml` has no actuator dependency and `application.yml` has no management
   settings (checked). The deployment must keep any management port off the BFF's network and off the
   internet.
6. **Verification at each deployment.** One test from outside the network and one from an unlisted internal
   host: both must get no connection. The 404 for `/.well-known/oauth-protected-resource/**` (I-07) must
   still hold.
7. **Later, optional:** `server.ssl.client-auth=need` on the service with a truststore for the BFF's
   certificate, as defence in depth on top of (b). The property name is in `spring-boot-web-server-4.1.2.jar`
   (string found; binding not tested).

## 6. What changes in this service

- **Domain:** nothing. No change to `domain/`, aggregates, commands, queries, errors or events.
- **Adapters and configuration:** in `risk/adapters/config`, the identity settings gain the two proposed
  properties of section 2.3 and the `azp` validator. `ActorContextResolver` keeps reading `oid`, the role and
  the booking centre as now. `IdentitySettings` fails startup on a blank `BFF_CLIENT_ID` (I-01).
- **Tests:** an IT with a token whose `azp` is another client (constant 401, identical bytes); an IT with the
  BFF's `azp` (accepted); `ProductionIdentitySettingsTest` fails if the new property leaves `application.yml`.
  The test configuration in `src/test/resources/config/application.yml` gets a test value.
- **Architecture rules:** none expected; no new dependency out of `domain/`. Not run here.
- **Unchanged:** refusal texts, firewall allowances, the constant problems, logging, rate limit (section 7).

## 7. Consequences and residual risks

- A compromised BFF impersonates its users to the service, within their rights. Mitigations: certificate
  credential preferred to a secret, stored in a vault; the BFF's Entra registration limited to this service's
  scope and to assigned users; short OBO lifetime; no bodies in the BFF's logs; alerting on OBO volume per user.
- F1 stays closed only by the network (section 2.2). Internal flooding of the key budget is accepted for
  onboarding (D-17). For the BFF, its own key budget is its own and receives no attacker input.
- The OBO exchange adds a round trip per user session (cache the token in memory until shortly before expiry).
- Rotating the BFF's credential and changing the `azp` allowlist must happen together. The client id itself
  does not change on credential rotation.
- The service's key-fetch rate limit (I-06, F1 trade-off) keeps its present form until open question 5 is
  answered.

## 8. Open questions for the product owner (not answered here)

1. **Entra exchange flow.** Is the app registration set up for on-behalf-of, with the BFF as a confidential
   client and the service as its API? Which client-credential type (certificate or secret)?
2. **Onboarding identity.** Its app-only token has no user, so may carry no `booking_centre` (design
   Addendum 2026-11-09 (4)). Does onboarding get its own entry in the `azp` allowlist, and with which booking
   centre? Not decided here.
3. **Owner of the BFF.** Who builds, runs and is accountable for the BFF and its Entra registration? The
   BFF's controls in section 3 and section 5.2 belong to that owner.
4. **Option (c).** Is mTLS wanted between the BFF and the service, and for onboarding too (the "not doing now"
   item in Addendum 2026-11-09 (3))?
5. **Key-fetch rate limit.** Is the service's rate limit (one fetch per 30 s, global) still wanted once only the
   BFF and onboarding call it? Keeping the last known key set on a refused refresh is still undecided.
6. **Customer numbers in logs.** BR-43 does not list the customer number. Does the product owner confirm that
   the BFF also keeps it out of its logs, as section 3.7 proposes?

Also to confirm in the Entra tenant before implementation (not checked here, no tenant access): that an OBO
token carries `azp`, the `roles` claim with the user's app roles, and the `booking_centre` extension claim;
and that `accessTokenAcceptedVersion` is 2 (design, I-10).

## 9. Verification status

- **Checked:** the three OAuth2 resource-server property names and their Java fields in
  `spring-boot-security-oauth2-resource-server-4.1.2.jar` (`OAuth2ResourceServerProperties$Jwt`: `audiences`,
  `issuerUri`, `jwkSetUri`); `server.ssl.client-auth` as a string in `spring-boot-web-server-4.1.2.jar`; the
  classes `JwtClaimValidator`, `JwtAudienceValidator` and `JwtIssuerValidator` in
  `spring-security-oauth2-jose` 7.1.2; `pom.xml` has no actuator dependency; `application.yml` has no
  management or SSL settings. All taken from `~/.m2`.
- **Not checked:** Entra's behaviour for OBO tokens (section 8, last paragraph); the order of key lookup and
  claim checks inside Spring's decoder (stated from the design of the decoder, not read in source); the
  `server.ssl.client-auth` binding; any Maven run or test run. No build result is claimed.

## 10. Decision requested

- Approve option (b): BFF as an OBO confidential client, with the service accepting only the BFF's calling
  client (`azp`) in addition to today's checks.
- Confirm that F1 is closed by the network controls of section 5, not by the `azp` check or the rate limit.
- Approve the BFF rules of section 3 and the failure catalogue of section 4 as the BFF's requirements.
- Answer open questions 1 to 6 (section 8), in particular who owns the BFF and whether the rate limit stays.
- Authorise the code change of section 6 only after questions 1 and 2 are answered.
