# Customer risk scoring — functional specification

Feature: **evaluate the terrorist-financing and money-laundering risk of a bank customer, and record
compliance decisions on that risk.**

Stage 1 artefact. Source of business facts: the ARGON analysis supplied by the product owner (the
Gemini conversation "Specyfikacja systemu AML/KYC i CTF", published 2026-11-07), and the product
owner's answers of 2026-11-07 recorded below.

**Releasable to stage 2.** Every former assumption (section 7) was confirmed by the product owner on 2026-11-07 (D-07).

## Confirmed decisions (product owner)

| # | Date | Decision |
|---|---|---|
| D-01 | 2026-11-07 | This first increment is the scoring core: risk evaluation at onboarding and on re-evaluation, compliance decisions, and the compliance and front-office views. Everything under "Out of scope" comes in later increments. |
| D-02 | 2026-11-07 | The jurisdiction multiplier applies to the whole weighted sum: S_total = min(MAX, (w1·R_KYC + w2·R_TM)·M_jur), and a critical fact overrides the result to MAX. ARGON's written formula, which reads as multiplying only the behavioural part, is not followed. |
| D-03 | 2026-11-07 | The user interface is not part of this increment. |
| D-04 | 2026-11-07 | The numbers in ARGON (weights, points, thresholds, multipliers, the 0–100 scale, lengths) are not confirmed. They appear in this document as assumptions. |
| D-05 | 2026-11-05 | Stated by the product owner as a general rule: the technical identity of a record is never exposed outside the service. A record is named by its business identifier. |
| D-06 | 2026-11-07 | The booking centres in scope are in the United States (US), the United Kingdom (GB) and Germany (DE, under EU rules). No other jurisdiction is evaluated in this increment. |
| D-07 | 2026-11-07 | Assumptions A-01 to A-31 are confirmed as written, with A-16 and A-18 applying to the three booking centres of D-06. |
| D-08 | 2026-11-08 | A-27 is amended for decisions: the role is judged before the kind of decision. A kind that is not recognised is judged against the permission to freeze assets, the narrowest one, so a caller who may not freeze learns nothing about whether the kind was valid. Only a caller who passes that check is told the kind is not recognised. Raised by the stage 6 reviews (R-04). |
| D-09 | 2026-11-08 | The input of one request is limited in size: at most 64 KiB for an evaluation and 16 KiB for a decision. Larger input is refused as **request too large** once the caller is identified, without being read further (R-01). |
| D-10 | 2026-11-08 | An evaluation carries at most 10 citizenships. More are refused as **too many citizenships** (R-10). |
| D-11 | 2026-11-08 | The service works with database rights that cannot change its own structure or rewrite the recorded history: the structure is created and changed by a separate owner, and the service may only add to the history and read it (R-09). |
| D-12 | 2026-11-08 | Input is read strictly. Input that is not a single well-formed object, or that names a field twice, is refused as **request not readable**. A field whose value is of the wrong kind (a number or text where yes or no is required, text or a fraction where a version is required) is refused as that field's own refusal, never coerced. A version viewed that is missing, not a whole number or below 1 is refused as **version viewed not valid** (R-06, R-07). |
| D-13 | 2026-11-09 | A caller is identified only by a signed access token issued by the bank's identity provider, Microsoft Entra ID, for this service, presented with the request. The token must be genuine, current, issued by the configured issuer and meant for this service. Anything else, including a missing token, is **caller not identified**. |
| D-14 | 2026-11-09 | The token names the caller's identity, role and booking centre in claims whose names are configured, not fixed in the service, so the identity provider's setup can change without a release. |
| D-15 | 2026-11-09 | A token must carry exactly one of the five roles of A-02. No role of this service, more than one, or a role name that does not match exactly, is **caller not identified**. |
| D-16 | 2026-11-09 | The booking centre in the token must be a well-formed booking centre identifier, and one of the booking centres in scope (D-06); otherwise the caller is not identified (BR-01). |
| D-17 | 2026-11-11 | The onboarding system reaches this service only from the internal network; nothing from the internet can reach it. Its risk classification is therefore lower than that of the customer-facing channels, and the key-fetch risk of an unknown key identifier from outside (F1) is accepted for it. Customer-facing callers go through the backend-for-frontend (ADR, decision 2026-11-11). |
| D-18 | 2026-11-11 | A risk event that the read side cannot apply after ten attempts is set aside (quarantined) and is not retried automatically. While such an event exists for a customer, the customer's front-office status is **verification pending** (fail-closed). Later events for that customer wait behind it, so that no change is applied out of order. Quarantine is visible to operations (log and metric), and it is released only by a person, after the cause has been fixed, with a record of who released it. |
| D-19 | 2026-11-11 | A caller is identified also by the application that presents its token. Human roles (the four analyst and officer roles) are accepted only from a user's token issued for the BFF, the only route for people. The onboarding role is accepted only from an application-only token of an onboarding application that is registered in the service's configuration. Its booking centre comes from that registration, never from a token or a request. An application that is not registered is not identified (BR-01). |
| D-20 | 2026-11-11 | Only the BFF and the onboarding system may open a connection to this service, each with a client certificate (mutual TLS), in addition to the token. TLS is required on every connection to this service. |
| D-21 | 2026-11-11 | When the service cannot fetch a refreshed set of signing keys, it keeps verifying tokens with the last set it has, for as long as its cache allows. A revoked key therefore stays valid at most until that cache expires. **Bound set 2026-11-11: at most 300 seconds after the last successful fetch** (a maximum: a deployment may set less, never more), stated explicitly in configuration, never left to a library default. |

## Scope

**In this increment:**

- Evaluating a customer's risk from the facts gathered at onboarding, and re-evaluating it when those
  facts change: the static component, critical facts, the jurisdiction policy, the total score, the
  risk track, the account restriction, and an explanation of every contribution.
- Recording a compliance decision on a customer's risk, guarded by the version of the risk profile the
  deciding person was looking at.
- Two reads: the compliance view of a customer's risk, with its full explanation, and the front-office
  view, which shows a neutral operational status only.
- A recorded history: every evaluation and every decision is a new recorded fact. Nothing recorded is
  ever changed or removed.

**Out of scope (later increments):**

- transaction monitoring and the behavioural component (behavioural red flags, time windows, velocity,
  funnel accounts, conflict-zone proximity, merchant categories, network analysis)
- payment screening before execution
- sanctions, watch-list, politically-exposed-person and adverse-media screening, and name matching
  (fuzzy, phonetic, transliteration). In this increment the outcomes of screening arrive as facts with
  the evaluation (assumption A-10, A-11)
- case management, escalation workflow, regulatory reporting of suspicious activity, and the
  execution of an asset freeze in the bank's account systems
- management of jurisdiction policies (editing weights and thresholds, four-eyes approval of a policy
  change, simulation on historical data)
- scheduling of periodic KYC reviews, and retention periods
- country risk lists held by this service, and re-evaluation triggered by a change of such a list
- the user interface (D-03)

## 1. Ubiquitous language

| Term | Definition |
|---|---|
| **Customer** | A natural person or an entity (a company, a non-profit organisation, a structure such as a trust) whose relationship with the bank is assessed. |
| **Customer Number** | The identifier the bank's core banking system gives the customer. It is the only identifier of a customer that leaves this service (D-05). |
| **Booking Centre** | The unit of the bank, in one country, where the customer's relationship is booked. A risk profile belongs to exactly one booking centre: the booking centre of the caller that first evaluated it. |
| **Caller** | The person or system performing an operation, always known by three things together: their booking centre, their own identity, and their role. |
| **Role** | What a caller is permitted to do. The roles are listed in assumption A-02. |
| **Risk Evaluation** | The calculation of a customer's risk from a complete set of facts. The first evaluation of a customer creates the customer's risk profile; every later one is a re-evaluation. |
| **Risk Factor** | One fact about the customer that adds points to a factor category, for example the customer's residence country being on the FATF grey list. |
| **Factor Category** | One of four groups of risk factors: geographic risk, customer profile, adverse media, and products and channels. Each category scores between 0 and MAX points and carries a category weight. |
| **Geographic Risk Class** | The risk class of a country: low risk, FATF grey list, or FATF black list or armed-conflict zone. |
| **Customer Profile Class** | The kind of customer: an individual in employment; an individual who is a student or has no income; a politically exposed person or a member of such a person's family; a complex offshore structure or a trust; a non-profit organisation operating across borders. |
| **Adverse Media Class** | What adverse-media screening found: nothing; unconfirmed reports of financial crime; or articles indicating radicalisation, extremism or links to paramilitary groups. |
| **Onboarding Channel** | How the customer was onboarded: in a branch, or fully remotely without face-to-face contact. |
| **High-Risk Product Request** | Whether the customer asked for products that allow fast international transfers, for prepaid cards, or for links to crypto-asset service providers. |
| **Login Country** | The country the customer was located in, by the network address or the device position, while being onboarded. |
| **Critical Fact** | One of three facts that decide the risk alone, whatever else is true: a confirmed match on a sanctions or terrorist list; a documented link to a terrorist organisation in a law-enforcement database or on the bank group's internal watch list; a forged identity document, or an attempt to open an account for a substituted person linked to a previously blocked device. |
| **Static Risk Score (R_KYC)** | The weighted sum of the four category scores. Between 0 and MAX. |
| **Behavioural Risk Score (R_TM)** | The score transaction monitoring will produce. It does not exist in this increment and is zero in every evaluation (BR-20). |
| **Component Weights (w1, w2)** | The weights of the static and behavioural scores in the total. |
| **Jurisdiction Multiplier (M_jur)** | A factor that makes the total stricter where the policy of the booking centre's jurisdiction requires it. |
| **Total Risk Score (S_total)** | The customer's overall risk, between 0 and MAX, with two decimal places. MAX means the highest risk. |
| **Evaluated Track** | The risk track the latest evaluation produced from the total score: Green, Yellow, Orange or Red. |
| **Decided Track** | A risk track set by a compliance decision. It exists only between the decision that sets it and the decision that clears it. |
| **Effective Track** | The risk track that governs the customer's account: the decided track while one exists and it is not lower than the evaluated track; otherwise the evaluated track (BR-31). |
| **Account Restriction** | What the effective track means for the account: active, soft limits, enhanced-due-diligence hold, or assets frozen. |
| **Explanation** | The list of every contribution to the total score: each factor with its points, its category weight and its contribution, and the multiplier; or the critical fact in force. Every score is explainable from its explanation alone. |
| **Risk Profile** | The recorded risk state of one customer in one booking centre: the latest evaluation with its explanation, the decided track if any, the effective track and restriction, and the version. |
| **Risk Profile Version** | A counter that starts at 1 when the risk profile is created and grows by exactly one with every recorded change. |
| **Compliance Decision** | A decision taken by compliance staff on a customer's risk: freeze assets, escalate to enhanced due diligence, or clear as a false positive. |
| **Justification** | The text in which the deciding person explains a compliance decision, for audit. |
| **Compliance View** | What compliance staff see of a customer's risk: everything in the risk profile. |
| **Front-Office View** | What branch and call-centre staff see: a neutral operational status and nothing about risk. |
| **Tipping-off** | Letting a customer, or staff who deal with the customer, learn that the customer is suspected or under review. It is forbidden (FATF Recommendation 21). |
| **Control Character** | A character in Unicode general category **Cc**: any character in U+0000–U+001F or U+007F–U+009F. SPACE (U+0020) is not one. |
| **Line Break** | LINE FEED (U+000A), alone or preceded by CARRIAGE RETURN (U+000D). Both are control characters; a justification may contain them only as a line break (BR-34). |

## 2. Business rules (decision table)

Markers in the form `(A-nn)` point to the assumption in section 7 that a rule depends on. A rule
with a marker is not confirmed until that assumption is.

### Access and isolation

| ID | Rule | Condition | Expected outcome / refusal |
|---|---|---|---|
| `BR-01` | A caller must be identified before any operation | No caller is known: no valid token for this service (D-13), or the presenting application is not an approved one for the role it asserts (D-19), or the connection is not mutually authenticated (D-20), or the token does not name exactly one identity, one role and one booking centre (D-14, D-15, D-16); or the caller's booking centre is not one of the booking centres in scope (D-06) | Refused: **caller not identified**. Nothing is recorded and nothing is returned |
| `BR-02` | Each operation, and each kind of compliance decision, is open only to the roles listed for it (A-02) | The caller's role is not listed for the operation or decision | Refused: **role may not perform this operation** |
| `BR-03` | The role is decided before any input is examined (A-03) | Any operation is attempted | A caller whose role is not permitted learns nothing about whether the input was valid or whether the customer exists |
| `BR-04` | An operation reaches only risk profiles of the caller's own booking centre (A-04) | The customer number names a profile of another booking centre, or no profile at all | The two cases are indistinguishable to the caller: both are **customer not found** |
| `BR-05` | A customer number is unique within one booking centre (A-05) | Two booking centres evaluate the same customer number | Two separate risk profiles exist, one per booking centre. Neither sees the other |

### Evaluation input

| ID | Rule | Condition | Expected outcome / refusal |
|---|---|---|---|
| `BR-06` | A customer number must be supplied, is at most 64 characters long, contains no control character, and is compared exactly as supplied (A-06) | Missing or empty; longer than 64 characters; contains a control character | Refused: **customer number missing**, **customer number too long**, or **customer number contains a control character**. Two numbers differing in any character, including letter case or spaces, are different numbers |
| `BR-07` | The customer profile class must be one of the five listed classes (A-07) | Missing, or any other value | Refused: **customer profile class not recognised** |
| `BR-08` | The residence country, the birth country and the login country are each exactly one country; citizenship is one to 10 countries (D-10). Each is a valid ISO 3166-1 alpha-2 code (A-08) | A country is missing; a code is not a valid ISO 3166-1 alpha-2 code; no citizenship is given; more than 10 citizenships are given | Refused: **country missing or not valid**, naming which country (residence, citizenship, birth or login); **no citizenship given**; or **too many citizenships** |
| `BR-09` | The residence country, every citizenship country and the birth country each carry their geographic risk class, supplied with the evaluation (A-09) | A class is missing for one of those countries, or is not one of the three classes | Refused: **geographic risk class missing or not recognised**, naming the country |
| `BR-10` | The onboarding channel is branch or remote; the high-risk product request is yes or no | The channel is missing or any other value; the product request is missing or is not yes or no (D-12) | Refused: **onboarding channel not recognised**, or **product request missing** |
| `BR-11` | The adverse media class is one of the three listed classes, supplied with the evaluation (A-10) | Missing, or any other value | Refused: **adverse media class not recognised** |
| `BR-12` | Each of the three critical facts is supplied as yes or no with every evaluation (A-11) | Any of the three is missing, or is not yes or no (D-12) | Refused: **critical fact missing**, naming which |
| `BR-13` | An evaluation is complete. A re-evaluation replaces every input of the previous one; nothing is carried over (A-12) | A re-evaluation is attempted | It is judged on its own inputs alone, by exactly the same rules as a first evaluation |

### Scoring

| ID | Rule | Condition | Expected outcome |
|---|---|---|---|
| `BR-14` | Geographic category points are the highest of the class points of the residence country, every citizenship country and the birth country: low 0, FATF grey list 50, FATF black list or armed-conflict zone 100. If the login country differs from the residence country, 30 points are added. The category never exceeds MAX (A-13, A-14) | Any evaluation | Example: residence low, one citizenship on the grey list, login country different: min(MAX, 50 + 30) = 80 |
| `BR-15` | Customer profile points: individual in employment 10; student or no income 30; politically exposed person or family member 70; complex offshore structure or trust 80; non-profit organisation operating across borders 85 (A-13) | Any evaluation | The points of the one class supplied |
| `BR-16` | Adverse media points: nothing found 0; unconfirmed reports of financial crime 50; radicalisation, extremism or paramilitary links 95 (A-13) | Any evaluation | The points of the one class supplied |
| `BR-17` | Products and channels points: branch onboarding 10; remote onboarding 40; a high-risk product request 60. The category scores the highest that applies (A-13, A-15) | Any evaluation | Example: remote onboarding with a high-risk product request: max(40, 60) = 60 |
| `BR-18` | The static risk score is the weighted sum of the four category scores, with category weights: geographic 30%, customer profile 25%, adverse media 25%, products and channels 20% (A-13) | Any evaluation | R_KYC = 0.30·geographic + 0.25·profile + 0.25·adverse media + 0.20·products and channels |
| `BR-19` | The jurisdiction policy that applies is the policy of the caller's booking centre (A-16) | Any evaluation | The weights, the multiplier and the track thresholds of that policy are used, and the policy is recorded with the evaluation (BR-27) |
| `BR-20` | In this increment the behavioural score is zero, and the onboarding weights apply to every evaluation: w1 = 1.00, w2 = 0.00. The weights for customers with transactional activity, w1 = 0.35 and w2 = 0.65, apply only once transaction monitoring exists (A-13, A-17) | Any evaluation in this increment | The total does not depend on the behavioural score |
| `BR-21` | The jurisdiction multiplier is 1.00, or 1.25 for a non-resident customer where the policy of the booking centre's jurisdiction requires a stricter approach for non-residents. A customer is a non-resident when the residence country differs from the booking centre's country (A-13, A-18) | Any evaluation | The multiplier used is recorded in the explanation |
| `BR-22` | The total score is min(MAX, (w1·R_KYC + w2·R_TM)·M_jur), rounded half-up to two decimal places. Rounding happens once, on the total; no intermediate value is rounded (D-02, A-13, A-19) | No critical fact is in force | Example: R_KYC 73.25, multiplier 1.25: 91.5625 → 91.56 |
| `BR-23` | A critical fact sets the total score to MAX, whatever every other input says (A-13) | At least one critical fact is yes | S_total = MAX, the evaluated track is Red, and the explanation names every critical fact in force |
| `BR-24` | The evaluated track follows from the total score: Green below 31.00; Yellow from 31.00 to below 61.00; Orange from 61.00 to below 85.00; Red from 85.00 (A-13, A-20) | Any evaluation | Example: 30.99 Green; 31.00 Yellow; 60.99 Yellow; 61.00 Orange; 84.99 Orange; 85.00 Red |
| `BR-25` | A non-profit organisation operating across borders is placed on the Orange track at least, whatever its score: ARGON requires such organisations to be escalated to enhanced due diligence automatically (FATF Recommendation 8) (A-21) | The customer profile class is non-profit organisation operating across borders, and the score alone gives Green or Yellow | The evaluated track is Orange; the explanation names the rule |
| `BR-26` | Every evaluation records its explanation: each factor with its points, its category weight and its contribution, the static score, the multiplier, and the total; or every critical fact in force | Any evaluation | Recomputing the total from the explanation alone gives exactly the recorded total |
| `BR-27` | Every evaluation records the jurisdiction policy it applied: the jurisdiction and every weight, multiplier and threshold used | Any evaluation | A later change of a policy never changes how an earlier evaluation reads |

### Account restriction

| ID | Rule | Condition | Expected outcome |
|---|---|---|---|
| `BR-28` | The account restriction follows from the effective track: Green active; Yellow soft limits; Orange enhanced-due-diligence hold; Red assets frozen (A-22) | Any change of the effective track | The restriction is recorded with the new state |

### Recorded history and concurrency

| ID | Rule | Condition | Expected outcome |
|---|---|---|---|
| `BR-29` | The first evaluation of a customer number in a booking centre creates the risk profile at version 1. Every later evaluation and every compliance decision records a new state, one version higher. Earlier states stay on record and are never changed or removed | Any successful operation | The views show the most current state only; earlier states are never shown as current |
| `BR-30` | If another change of the same risk profile is recorded after an operation read the profile and before it records its own result, the later operation is refused | Two operations on one risk profile overlap | Refused: **risk profile changed concurrently**. The change recorded first stands; the refused operation records nothing |
| `BR-31` | An evaluation never lowers a decided track. A decided track stays in force until a compliance decision clears it, and an evaluation may raise the effective track above it (A-23) | An evaluation produces an evaluated track lower than the decided track | The effective track stays the decided track. The new evaluation, its score and its explanation are still recorded |

### Compliance decisions

| ID | Rule | Condition | Expected outcome / refusal |
|---|---|---|---|
| `BR-32` | **Freeze assets** sets the decided track to Red. **Escalate to enhanced due diligence** sets the decided track to Orange. **Clear as false positive** removes the decided track, so the effective track becomes the evaluated track of the latest evaluation (A-24) | A decision is recorded | The effective track and the restriction (BR-28) change accordingly |
| `BR-33` | A decision carries the version of the risk profile the deciding person was looking at. It is recorded only if that is still the current version | The version carried is missing, not a whole number or below 1 (D-12); the current version differs from the version carried | Refused: **version viewed not valid**; or **risk profile changed since viewed**. Nothing is recorded; the deciding person must look again |
| `BR-34` | A justification must be supplied: at least 20 characters, at most 2 000 characters. It may contain line breaks; it contains no other control character (A-25) | Missing; shorter than 20 characters; longer than 2 000 characters; contains a control character other than a line break | Refused: **justification missing**, **justification too short**, **justification too long**, or **justification contains a control character** |
| `BR-35` | A decision that changes nothing, or that would lower a decided track other than by clearing, is refused. The test is the decided track, not the effective one: freezing a customer who is Red by evaluation alone is allowed, because it makes the freeze survive later evaluations (BR-31) (A-26) | Freeze when the decided track is already Red; escalate when the decided track is already Orange or Red; clear when no decided track exists | Refused: **already frozen**, **already escalated**, or **nothing to clear** |
| `BR-36` | A decided track cannot be cleared while a critical fact is in force | Clear as false positive, and the latest evaluation has a critical fact in force | Refused: **critical fact in force**. The customer stays on Red |
| `BR-37` | A decision is judged in a fixed order, and the first failure found is the one refusal the caller receives (A-27) | Any decision | Caller identified (BR-01); request not too large and readable (D-09, D-12); role permits this kind of decision, an unrecognised kind being judged as freezing assets (BR-02, D-08); the kind of decision is recognised (BR-32); customer found in the caller's booking centre (BR-04); justification valid (BR-34); version viewed valid and current (BR-33); critical fact (BR-36); the decision changes something (BR-35); no concurrent change (BR-30) |
| `BR-38` | A decision records who decided, when, which decision, the justification, and the version it was taken on. It changes neither the score nor the explanation | A decision is recorded | The compliance view shows the latest decision with these details |

### Views and tipping-off

| ID | Rule | Condition | Expected outcome |
|---|---|---|---|
| `BR-39` | The compliance view is available to compliance roles only, and only for customers of the caller's booking centre (A-02, A-04) | Any read | See query 1 |
| `BR-40` | The front-office view shows only an operational status and a neutral message. Effective track Green: status **active**, message "Account active – no operational restrictions." Any other effective track: status **verification pending**, message "Standard administrative verification of documentation is in progress." (A-28) | Any read | Yellow, Orange and Red produce the same status and message, so they cannot be told apart. Nothing about the score, the track, the restriction, the factors, critical facts, decisions or the justification is shown |
| `BR-41` | A refusal shown to a front-office caller reveals no risk information | Any refusal to a front-office caller | The refusal names its reason only, and no reason refers to risk |
| `BR-42` | A refused operation records nothing and announces nothing | Any refusal | — |

### Logging

| ID | Rule | Condition | Expected outcome |
|---|---|---|---|
| `BR-43` | No risk information, no personal input and no caller identity is ever written to a log (A-29) | Any operation is logged | Scores, tracks, restrictions, explanations, critical facts, decisions, justifications, profile classes, adverse media classes, countries and the caller's identity are absent from every log entry |

## 3. Command catalogue

### Command 1 — Evaluate Customer Risk

| | |
|---|---|
| **Who may do it** | The onboarding system (A-02) |
| **Organisational context** | The caller's booking centre. The risk profile belongs to it (BR-04, BR-05) |

Inputs and their hard constraints:

| Input | Required | Constraint |
|---|---|---|
| Customer number | Yes | BR-06 |
| Customer profile class | Yes | One of the five classes (BR-07) |
| Residence country, with its geographic risk class | Yes | BR-08, BR-09 |
| Citizenship countries, each with its geographic risk class | Yes, one or more | BR-08, BR-09 |
| Birth country, with its geographic risk class | Yes | BR-08, BR-09 |
| Login country | Yes | BR-08 (A-08) |
| Onboarding channel | Yes | Branch or remote (BR-10) |
| High-risk product request | Yes | Yes or no (BR-10) |
| Adverse media class | Yes | One of the three classes (BR-11) |
| The three critical facts | Yes, each | Yes or no (BR-12) |

Nothing else is accepted. The booking centre comes from the caller; the moment of evaluation comes
from the clock.

**Every reason this command can be refused** (exhaustive), in the order they are decided (A-27):

| # | Refusal reason | Rule |
|---|---|---|
| 1 | Caller not identified | BR-01 |
| 2 | Role may not perform this operation | BR-02, BR-03 |
| 3 | Request too large | D-09 |
| 4 | Request not readable | D-12 |
| 5 | Customer number missing | BR-06 |
| 6 | Customer number too long | BR-06 |
| 7 | Customer number contains a control character | BR-06 |
| 8 | Customer profile class not recognised | BR-07 |
| 9 | Country missing or not valid (naming which) | BR-08 |
| 10 | No citizenship given | BR-08 |
| 11 | Too many citizenships | BR-08, D-10 |
| 12 | Geographic risk class missing or not recognised (naming the country) | BR-09 |
| 13 | Onboarding channel not recognised | BR-10 |
| 14 | Product request missing | BR-10 |
| 15 | Adverse media class not recognised | BR-11 |
| 16 | Critical fact missing (naming which) | BR-12 |
| 17 | Risk profile changed concurrently | BR-30 |

No other refusal exists for this command. A refusal records nothing (BR-42).

**Business fact that follows success:** a new state of the customer's risk profile is recorded, at
version 1 for a new profile or one version higher, carrying the inputs, the jurisdiction policy, the
explanation, the total score, the evaluated track, the effective track and the restriction. The fact
`CustomerRiskEvaluated` is announced (section 5).

**Worked examples.** The booking centre is in Germany (DE) and its policy requires the stricter
approach for non-residents (A-18). No critical fact is in force unless stated.

| # | Inputs | Calculation | Outcome |
|---|---|---|---|
| E1 | Individual in employment; residence, citizenship and birth DE, all low; login DE; branch; no product request; nothing in adverse media | Geographic 0, profile 10, media 0, products 10. R_KYC = 0 + 2.50 + 0 + 2.00 = 4.50. Resident: ×1.00 | S_total 4.50, Green, active |
| E2 | As E1, but residence and login country France (FR), low | Geographic 0 (login matches residence). R_KYC 4.50. Non-resident: 4.50 × 1.25 = 5.625 | S_total 5.63 (half-up), Green, active |
| E3 | Politically exposed person; residence DE low; citizenship AE low; birth DE low; login AE; remote; product request yes; unconfirmed financial-crime reports | Geographic min(MAX, 0 + 30) = 30, profile 70, media 50, products max(40, 60) = 60. R_KYC = 9.00 + 17.50 + 12.50 + 12.00 = 51.00. Resident | S_total 51.00, Yellow, soft limits |
| E4 | As E3, but residence FR, low | 51.00 × 1.25 = 63.75 | S_total 63.75, Orange, enhanced-due-diligence hold |
| E5 | Non-profit organisation operating across borders; all countries DE low; login DE; branch; no product request; nothing in adverse media | Geographic 0, profile 85, media 0, products 10. R_KYC = 21.25 + 2.00 = 23.25 | S_total 23.25, but Orange by BR-25, enhanced-due-diligence hold |
| E6 | Student; residence on the FATF black list; login elsewhere; remote; product request yes; radicalisation reported; booking centre DE | Geographic min(MAX, 100 + 30) = 100, profile 30, media 95, products 60. R_KYC = 30.00 + 7.50 + 23.75 + 12.00 = 73.25. Non-resident: 91.5625 | S_total 91.56, Red, assets frozen |
| E7 | As E1, with a confirmed sanctions-list match | Critical fact | S_total MAX, Red, assets frozen; the explanation names the match |

### Command 2 — Record Compliance Decision

| | |
|---|---|
| **Who may do it** | Depends on the kind of decision (A-02) |
| **Organisational context** | The customer's risk profile must belong to the caller's booking centre (BR-04) |

Inputs and their hard constraints:

| Input | Required | Constraint |
|---|---|---|
| Customer number | Yes | Names one risk profile of the caller's booking centre. A number that cannot be a customer number (BR-06) names no profile and is refused as **customer not found** |
| Kind of decision | Yes | Freeze assets, escalate to enhanced due diligence, or clear as false positive |
| Justification | Yes | BR-34 |
| Version viewed | Yes | The risk profile version the deciding person was looking at (BR-33) |

Nothing else is accepted. The deciding caller and the moment come from the caller and the clock.

**Every reason this command can be refused** (exhaustive), in the order of BR-37:

| # | Refusal reason | Rule |
|---|---|---|
| 1 | Caller not identified | BR-01 |
| 2 | Request too large | D-09 |
| 3 | Request not readable | D-12 |
| 4 | Role may not perform this operation | BR-02, BR-03, D-08 |
| 5 | Kind of decision not recognised | BR-32 |
| 6 | Customer not found | BR-04 |
| 7 | Justification missing | BR-34 |
| 8 | Justification too short | BR-34 |
| 9 | Justification too long | BR-34 |
| 10 | Justification contains a control character | BR-34 |
| 11 | Version viewed not valid | BR-33, D-12 |
| 12 | Risk profile changed since viewed | BR-33 |
| 13 | Critical fact in force | BR-36 |
| 14 | Already frozen | BR-35 |
| 15 | Already escalated | BR-35 |
| 16 | Nothing to clear | BR-35 |
| 17 | Risk profile changed concurrently | BR-30 |

The role is decided on the kind of decision, and is judged before the kind (D-08, amending A-27): an
unrecognised kind is judged against the permission to freeze assets, so a caller who may not freeze is
refused for the role, and only a caller who may freeze is told that the kind is not recognised.

**Business fact that follows success:** a new state of the risk profile is recorded, one version
higher, with the decided track (or its removal), the effective track and the restriction, and the
decision with who took it, when, the justification and the version it was taken on (BR-38). The fact
`ComplianceDecisionRecorded` is announced (section 5).

**Worked examples.** Each starts from a risk profile at version 3 whose latest evaluation is Yellow.

| # | Situation | Outcome |
|---|---|---|
| F1 | An L2 analyst freezes assets, viewing version 3 | Recorded at version 4: decided Red, effective Red, assets frozen |
| F2 | As F1, but an evaluation recorded version 4 first | Refused: risk profile changed since viewed |
| F3 | After F1, an evaluation gives Green | Recorded at version 5: evaluated Green, effective stays Red (BR-31) |
| F4 | After F3, an L2 analyst clears as false positive, viewing version 5 | Recorded at version 6: no decided track, effective Green, active |
| F5 | An L2 analyst clears as false positive on a profile with no decided track | Refused: nothing to clear |
| F6 | The latest evaluation has a confirmed sanctions match; an MLRO clears as false positive | Refused: critical fact in force |
| F7 | After F1, an L1 analyst escalates to enhanced due diligence | Refused: already escalated (the decided track is Red) |
| F7a | An L2 analyst freezes assets on a profile that is Red by evaluation alone, with no decided track | Recorded: decided Red, effective Red, assets frozen; a later Green evaluation leaves it Red (BR-31) |
| F8 | An L1 analyst freezes assets | Refused: role may not perform this operation (A-02) |

## 4. Query catalogue

### Query 1 — Compliance Risk View

| | |
|---|---|
| **Who may ask** | Compliance roles (A-02) |
| **Selection** | One customer number, within the caller's own booking centre (BR-39) |

The view shows: the customer number; the booking centre's jurisdiction; the jurisdiction policy
applied; the static score, the behavioural score, the weights and the multiplier; the total score; the
evaluated track, the decided track if any, the effective track and the restriction; whether a critical
fact is in force, and which; the explanation; the latest compliance decision with who took it, when,
the justification and the version it was taken on; the moment of the latest evaluation; and the
current version. The version lets the deciding person tell whether the view is current (BR-33).

It does not show the internal identity of the profile (D-05), and it shows the most current state only
(BR-29).

Refusals: caller not identified (BR-01); role may not perform this operation (BR-02); customer not
found (BR-04).

### Query 2 — Front-Office Status

| | |
|---|---|
| **Who may ask** | Any identified caller of the booking centre (A-02) |
| **Selection** | One customer number, within the caller's own booking centre |

The view shows the customer number, the operational status and the neutral message of BR-40, and
nothing else.

Refusals: caller not identified (BR-01); customer not found (BR-04). Neither refusal refers to risk
(BR-41).

### Failure handling (D-18)

| ID | Rule | Condition | Expected outcome |
|---|---|---|---|
| `BR-44` | An event that fails to apply to the read side ten times is quarantined and is not retried automatically (D-18) | An event has reached ten failed attempts | The event is kept as quarantined; no further automatic attempt is made |
| `BR-45` | While a quarantined event exists for a customer, that customer's front-office status is **verification pending**, and the later events of that customer wait (D-18) | Any read of the front-office status for such a customer | Status verification pending, with the neutral message of BR-40; no risk information (BR-41) |
| `BR-46` | Each quarantined event produces one operational log line and counts in a metric; each release is recorded with who released it and when (D-18, BR-43) | An event is quarantined, or released | One ERROR line with the event type and the count only, never a customer number or a score; one audit record per release |

The role that may release a quarantined event is a product-owner decision still open (see the design, 2026-11-11 addendum).

## 5. Business events

Facts announced to other parts of the bank. They carry risk information, so they are delivered only
to compliance consumers and never to a front-office channel (A-30).

| Event | Meaning | Announced when | Carries |
|---|---|---|---|
| `CustomerRiskEvaluated` | A customer's risk was evaluated | An evaluation succeeds | The booking centre, the customer number, the version, the jurisdiction policy, the static and behavioural scores, the weights, the multiplier, the total, the evaluated, decided and effective tracks, the restriction, the critical facts in force, the explanation, the moment |
| `ComplianceDecisionRecorded` | A compliance decision was recorded | A decision succeeds | The booking centre, the customer number, the version, the kind of decision, the decided and effective tracks, the restriction, the deciding caller, the moment. Not the justification (A-31) |

Neither is announced for a refused operation (BR-42).

## 6. Data classification

| Data | Classification | May appear in a log? |
|---|---|---|
| Customer number | Business identifier | Yes, but never together with risk information (A-29) |
| Booking centre | Organisational | Yes |
| Scores, tracks, restriction, explanation, critical facts, decisions, justification | Confidential: revealing them is tipping-off | **Never** (BR-43) |
| Customer profile class, adverse media class, countries | Personal data; politically exposed status and adverse media are sensitive | **Never** (BR-43) |
| Caller identity | Attributable to an individual | **Never** (A-29) |

## 7. Former assumptions, confirmed by the product owner

Each was an open assumption until 2026-11-07, when the product owner confirmed it (D-07). The source of each is kept for audit.

**A-01.**
> `CONFIRMED 2026-11-07 (D-07): the score scale is 0 to MAX with MAX = 100, and every score carries two decimal places. Source: ARGON (0–100 scale, two decimals).`

**A-02.**
> `CONFIRMED 2026-11-07 (D-07): the roles are Onboarding System, AML Analyst L1, AML Analyst L2, MLRO and Front-Office Employee. Evaluate: Onboarding System. Escalate to enhanced due diligence: L1, L2, MLRO. Freeze assets: L2, MLRO. Clear as false positive: L2, MLRO. Compliance view: L1, L2, MLRO. Front-office status: every role. Source: analyst reading of ARGON (L1 handles alerts, L2 verifies enhanced due diligence, MLRO receives critical cases); ARGON names no permission table.`

**A-03.**
> `CONFIRMED 2026-11-07 (D-07): the role is decided before any input is examined. Source: analyst default, taken from the confirmed rule of the template project; ARGON is silent.`

**A-04.**
> `CONFIRMED 2026-11-07 (D-07): compliance and front-office staff reach only the customers of their own booking centre, and another booking centre's customer is indistinguishable from an unknown one. Source: analyst reading of ARGON's booking-centre overlays and data-residency requirement; ARGON does not state who may see which customer.`

**A-05.**
> `CONFIRMED 2026-11-07 (D-07): a customer number is unique within a booking centre, not across the bank. Source: analyst default; ARGON makes the core-banking identifier unique without saying in what scope.`

**A-06.**
> `CONFIRMED 2026-11-07 (D-07): a customer number is at most 64 characters, contains no control character, and is compared exactly as supplied. Source: ARGON (64-character identifier); the control-character and exact-comparison rules are analyst defaults.`

**A-07.**
> `CONFIRMED 2026-11-07 (D-07): the five profile classes cover every customer this increment evaluates. A domestic company, a domestic non-profit organisation or a self-employed individual fits none of them and is refused. Source: ARGON lists these five classes only.`

**A-08.**
> `CONFIRMED 2026-11-07 (D-07): the login country is required, and countries are ISO 3166-1 alpha-2 codes. Source: ARGON (alpha-2 codes; login location checked at onboarding); that the login country is required is an analyst default.`

**A-09.**
> `CONFIRMED 2026-11-07 (D-07): the geographic risk class of each country is supplied with the evaluation, from the bank's country risk register; this service holds no country lists in this increment. Source: analyst default; ARGON does not say where the classification comes from.`

**A-10.**
> `CONFIRMED 2026-11-07 (D-07): the adverse media class is supplied with the evaluation as the outcome of screening done elsewhere. Source: analyst default; screening is out of scope for this increment (D-01).`

**A-11.**
> `CONFIRMED 2026-11-07 (D-07): the three critical facts are supplied with the evaluation as yes or no, decided elsewhere (screening, law-enforcement checks, document and device checks). Source: analyst default; those checks are out of scope for this increment (D-01).`

**A-12.**
> `CONFIRMED 2026-11-07 (D-07): every evaluation carries the complete set of inputs, and a re-evaluation replaces all earlier inputs. Source: analyst default; ARGON does not say whether a re-evaluation may change one fact alone.`

**A-13.**
> `CONFIRMED 2026-11-07 (D-07): every number in BR-14 to BR-24: geographic points 0, 50 and 100 and the login mismatch 30; profile points 10, 30, 70, 80 and 85; adverse media points 0, 50 and 95; products and channels points 10, 40 and 60; category weights 30%, 25%, 25% and 20%; component weights 1.00 and 0.00 at onboarding, 0.35 and 0.65 with transactional activity; multipliers 1.00 and 1.25; track thresholds 31.00, 61.00 and 85.00. Source: ARGON; the product owner has not confirmed them (D-04).`

**A-14.**
> `CONFIRMED 2026-11-07 (D-07): the geographic category takes the highest class among the residence, citizenship and birth countries, adds the login mismatch, and is capped at MAX. Source: analyst reading of ARGON, which lists the points without saying how several countries combine.`

**A-15.**
> `CONFIRMED 2026-11-07 (D-07): the products and channels category takes the highest of the channel points and the product request points, rather than adding them. Source: analyst reading of ARGON, which lists the points without saying how they combine.`

**A-16.**
> `CONFIRMED 2026-11-07 (D-07): the jurisdiction policy is the policy of the caller's booking centre. Source: analyst choice; ARGON says overlays are activated by residence, citizenship and booking centre, and its examples select the policy by residence country, so ARGON is not consistent.`

**A-17.**
> `CONFIRMED 2026-11-07 (D-07): in this increment every evaluation uses the onboarding weights, because no behavioural score exists yet. Source: analyst reading of ARGON (onboarding weights 1.0 and 0.0) and of D-01.`

**A-18.**
> `CONFIRMED 2026-11-07 (D-07): the policy of every booking centre in this increment requires the stricter approach for non-residents, so every non-resident gets the 1.25 multiplier; a customer is a non-resident when the residence country differs from the booking centre's country. Source: analyst default; ARGON gives 1.25 as an example and does not name the jurisdictions that require it.`

**A-19.**
> `CONFIRMED 2026-11-07 (D-07): the total is rounded half-up to two decimal places, once, at the end. Source: ARGON's first calculation rounds half-up to two places; its later examples round differently.`

**A-20.**
> `CONFIRMED 2026-11-07 (D-07): a threshold belongs to the higher track (31.00 is Yellow, 61.00 is Orange, 85.00 is Red). Source: ARGON's policy thresholds compared with "at least"; ARGON's track table (0–30, 31–60, 61–84, 85–100) does not say where a score such as 30.50 falls.`

**A-21.**
> `CONFIRMED 2026-11-07 (D-07): the automatic escalation of non-profit organisations means the evaluated track is at least Orange. Source: analyst reading of ARGON ("non-profit organisations are escalated to enhanced due diligence automatically") without a stated mechanism.`

**A-22.**
> `CONFIRMED 2026-11-07 (D-07): Green means active, Yellow soft limits, Orange enhanced-due-diligence hold, Red assets frozen. Source: ARGON's final model; an earlier ARGON model maps every track except Red to active, so ARGON contradicts itself.`

**A-23.**
> `CONFIRMED 2026-11-07 (D-07): an evaluation never lowers a track set by a compliance decision; only a later decision clears it. Source: analyst default for safety. ARGON is silent, and without this rule an automated re-evaluation could lift an asset freeze that compliance staff imposed.`

**A-24.**
> `CONFIRMED 2026-11-07 (D-07): freeze assets sets Red, escalate sets Orange, and clear as false positive returns the customer to the track of the latest evaluation. Source: analyst reading of ARGON's three decisions; ARGON does not say which track clearing leads to.`

**A-25.**
> `CONFIRMED 2026-11-07 (D-07): a justification is at least 20 and at most 2 000 characters, and may contain line breaks but no other control character. Source: ARGON (at least 20 characters); the upper limit and the line-break rule are analyst defaults, because ARGON sets no upper limit.`

**A-26.**
> `CONFIRMED 2026-11-07 (D-07): a decision that changes nothing is refused, judged on the decided track: freeze is refused when a decided Red exists, escalation when a decided Orange or Red exists, clearing when no decided track exists; freezing a customer who is Red by evaluation alone is allowed. Source: analyst default; ARGON is silent.`

**A-27.**
> `CONFIRMED 2026-11-07 (D-07): the order of refusals in both command catalogues, including that an unrecognised kind of decision is refused before the role is judged. Source: analyst default, following the template project's confirmed ordering principle; ARGON is silent.`
>
> `AMENDED 2026-11-08 (D-08): for decisions the role is judged before the kind; an unrecognised kind is judged against the permission to freeze assets. The size and readability refusals of D-09 and D-12 come right after the caller is identified (command 2) or after the role (command 1).`

**A-28.**
> `CONFIRMED 2026-11-07 (D-07): the two front-office statuses and their messages. Source: ARGON gives the messages in Polish ("Konto aktywne – brak ograniczeń operacyjnych." and "Trwa standardowa weryfikacja administracyjna dokumentacji."); the English wording and the two status names are analyst translations.`

**A-29.**
> `CONFIRMED 2026-11-07 (D-07): the customer number may be logged, but never with risk information; the caller's identity is never logged. Source: analyst default, from the tipping-off ban in ARGON and the template project's confirmed rule on caller identity.`

**A-30.**
> `CONFIRMED 2026-11-07 (D-07): the two events are delivered to compliance consumers only. Source: analyst reading of ARGON's tipping-off ban; ARGON does not name the consumers.`

**A-31.**
> `CONFIRMED 2026-11-07 (D-07): the decision event does not carry the justification; the justification is recorded and shown in the compliance view only. Source: analyst default, to keep free text out of messages; ARGON's decision event carries it.`

## Hand-over checklist

- [x] Every rule has an ID and is testable as written
- [x] Every command lists all its refusal reasons, in order
- [x] Every number traces to the product owner, or carries an `ASSUMPTION` marker (A-01, A-06, A-13, A-25)
- [x] No class name, table name, SQL or framework term in the document
- [x] Every assumption resolved: A-01 to A-31 confirmed on 2026-11-07 (D-07); A-27 amended on 2026-11-08 (D-08)
- [x] The identity decisions D-19 to D-21 (2026-11-11) are reflected in BR-01
- [x] The failure decision D-18 (2026-11-11) is reflected in BR-44 to BR-46
- [x] The identity decisions D-13 to D-16 (2026-11-09) are reflected in BR-01
- [x] The stage 6 decisions D-08 to D-12 (2026-11-08) are reflected in BR-01, BR-08, BR-10, BR-12, BR-33, BR-37 and both catalogues
