# WORLD JIGSAW — Complete Product and Engineering Blueprint

**Version:** 3.0  
**Status:** Implementation-ready design specification (proposed, not an existing product)  
**Core concept:** One world, one daily destination, one shared jigsaw puzzle, five accepted moves per anonymous contributor, and a global countdown.

---

## 0. Executive summary and non-negotiable decisions

World Jigsaw is a browser-based, collaborative daily jigsaw puzzle. At a server-defined time (initially **00:00 UTC**), the platform reveals exactly one curated photograph of a city or landmark. Every visitor worldwide sees the **same puzzle instance**, the same global progress, and the same deadline. Anonymous players contribute up to **five accepted drag-and-drop moves per daily puzzle**. Other players' meaningful actions become visible through live puzzle movement, an activity ticker below the board, a global activity dashboard, and chat.

**Decisions:**

1. **Exactly one active global puzzle per UTC day.** Historical puzzles remain viewable but read-only. Countries and cities are themes for the daily photograph, not parallel active puzzle rooms.
2. **Five moves per daily puzzle, not rolling 24-hour cooldowns.** A new UTC day's puzzle grants every recognized anonymous identity a new quota. No rollover of unused moves.
3. **A move is one server-accepted drop**, whether bringing a piece out of the bag, repositioning a loose piece/group, joining two groups, or joining several groups in a single drop. Invalid drops, grabbing, panning, zooming, clicking, chat and watching cost zero.
4. **Commit accepted moves immediately**, atomically with quota and event persistence. The **Confirm** screen finalizes participation, but does not save uncommitted moves; nothing is waiting for a dangerous multi-move commit.
5. **No required sign-up.** Users receive a stable, human-friendly name, **Adjective + Fruit + three digits**, e.g. `SleepyMango042`, and a distinct server-side UUID that is never public.
6. **Uniqueness is enforced transactionally.** Display names are unique within the active namespace; the UUID, not the display name, is used for identity and access control.
7. **Anonymous identity cannot conclusively establish a human.** Cookie and browser-key continuity, IP/UA anomaly checks, rate limiting and adaptive challenges discourage casual evasion without covert device fingerprinting or false human-level guarantees.
8. **Java 21 + Spring Boot** runs the server-authoritative game coordinator, APIs and gateways. React + TypeScript + PixiJS renders the front end. PostgreSQL stores durable truth. Redis is for ephemeral signaling and counters.
9. **At scale, fanout is interest-based.** Everyone has the same durable board, but not every participant receives every mouse movement or drag preview globally. Broadcast durable group changes efficiently; sample and cap nearby cursor presence.
10. **The bottom activity ticker shows notable accepted actions only.** A full dashboard shows aggregate activity and a compact recent event feed. Never stream individual mouse clicks into the public activity timeline.
11. **Use images whose rights permit online interactive reuse.** Do not automatically scrape and republish Fine Art America images without permission/license; curated Wikimedia Commons assets or permissioned artist uploads are better initial sources.

**One-sentence pitch:** *Five moves each. One shared puzzle. One world. Every day.*

---

## 1. Daily game and lifecycle

### 1.1 Authoritative UTC schedule

- Example `puzzle_day`: `2026-11-07` (UTC date).
- `starts_at = 2026-11-07T00:00:00Z` and `ends_at = 2026-11-08T00:00:00Z`.
- At any instant, exactly one puzzle is `ACTIVE`; each puzzle has a globally unique `puzzle_id` independent of its day.
- Everyone sees the same server-supplied `ends_at`. The browser updates a smooth local countdown using an estimated server offset; the server never pushes every tick.
- At the boundary, the old puzzle transitions to `COMPLETED` (if solved) or `EXPIRED` (if unsolved), and the next `SCHEDULED` puzzle transitions to `ACTIVE` in an idempotent, serialized rollover operation.
- If a rollover scheduler is late or fails, *any* read or command path should detect the expired active puzzle and fail closed for old moves; a leader/scheduler reconciles missing activation. Never accept an old puzzle move after `ends_at`.
- Default tie-breaker: validity checked using authoritative server/database time at transaction commit. A drop started just before midnight but committed after midnight is rejected with reason `DAY_ENDED`, no move consumed, and rolled back visually.
- A daily puzzle that completes early stays in celebration/read-only mode until the next reset. Users can view the completed image, chat, inspect activity, and browse archives. Do not silently replace it early.

### 1.2 Puzzle sizing and the crowding problem

A single 150-piece puzzle with thousands of eager contributors will finish quickly. Do not promise five meaningful moves to *every* visitor once the board is complete.

- Launch experiments: 300–600 pieces, with a fast renderer and a curated image.
- Build for 1,000–5,000 pieces if daily participation warrants it (performance/UX validation required).
- Calculate the next day's piece count from prior days' unique legitimate contributors, accepted moves, time-to-finish, and actual connections; target a satisfying fraction of the 24-hour window rather than guaranteeing completion.
- Choose piece count *before activation*. Do not change geometry or add pieces in the middle of a day.
- Show both **pieces anchored** and **pieces in connected assemblies**; these are not interchangeable progress metrics.
- Provide a smart **Find an open area** action to help players find movable pieces, especially on crowded boards.
- If the board completes early, accept that the day's puzzle is finished; show celebration, read-only replay, chat, activity, and a countdown to tomorrow.

### 1.3 State machine

```text
SCHEDULED
    |
    v (UTC start)
ACTIVE -- all pieces anchored --> COMPLETED
    |
    v (UTC end if incomplete)
EXPIRED
    |
    v
ARCHIVED (read-only)
```

Rollover and completion must be idempotent. Never let the same puzzle be completed twice or the next day's puzzle be activated twice.

---

## 2. Complete user journey

### 2.1 First visit

1. Visitor loads home page. No account wall, no personal-data form.
2. HTTPS cookie is checked; if absent, create a server-issued anonymous identity and session.
3. Server assigns a unique display name like **CuriousLychee317** and an avatar/color. Optionally establish a browser cryptographic key in Phase 2.
4. Visitor sees today's place, global countdown, live worldwide occupancy and progress.
5. Large **Join today's puzzle** button opens the same global board; the viewer is a spectator by default until they grab a movable piece.
6. First accepted drop begins their daily contribution implicitly; there is no mandatory modal just to start moving.
7. A persistent five-dot move counter shows their remaining quota.
8. After five accepted moves, controls lock; a contribution summary can be confirmed/dismissed. The shared board is already saved.
9. They continue watching, chatting, exploring the dashboard and replaying history until tomorrow.

### 2.2 Returning visit

- Restore existing anonymous identity, name and cursor color if valid.
- Fetch current UTC puzzle and `daily_contribution` keyed by `(identity_id, puzzle_id)`.
- If 2/5 moves used earlier, show 3 left. If 5/5 used, show spectator mode and the common reset timer.
- If previous puzzle expired, grant fresh quota on the *new* puzzle; no independent 24-hour cooldown.

### 2.3 User-facing UX copy

- Header: **One world. One puzzle. Five moves.**
- Quota: **3 of 5 moves left today**.
- Valid drop: **Nice! You connected two groups · 2 moves left**.
- Invalid drop: **That piece can’t go there. No move used.**
- Group busy: **BraveKiwi508 is moving this group. Try another.**
- After five: **You're done for today! Your five moves are part of the world puzzle.**
- Rollover: **Tomorrow's destination is here. Your five moves are ready.**
- Suspicious rate limit: **We need a quick verification before more moves. You can still watch.**
- Completed early: **World solved! Come back for a new country in 05:42:18.**

---

## 3. Information architecture, layout and screens

### 3.1 Main screen: desktop

```text
┌───────────────────────────────────────────────────────────────────────────────┐
│ WORLD JIGSAW  Today: Hoi An 🇻🇳   Next Puzzle 08:42:17   3,121 online  ☰    │
├────────────────────────────────────────────────────────┬──────────────────────┤
│                                                        │ REFERENCE PHOTO      │
│                 SHARED VIRTUAL TABLE                   │ [full small photo]   │
│   piece       [connected group]                          │ 1,146/2,000 anchored │
│                                                        │                      │
│             ┌────────────────────────┐                 │ WORLD CHAT           │
│  piece      │    FINAL IMAGE FRAME   │       group     │ SleepyMango042: ...  │
│             │  correctly anchored    │                 │ BraveKiwi508: ...    │
│             └────────────────────────┘                 │ [Message...]         │
│                                                        │                      │
├────────────────────────────────────────────────────────┤ TODAY'S STATS        │
│ BAG [piece] [piece] [piece] [piece]  Filter: edges      │ contributors / joins │
├────────────────────────────────────────────────────────┴──────────────────────┤
│ ACTIVITY ▾  🥭 SleepyMango042 joined 3 pieces  ·  🥝 BraveKiwi508 anchored... │
├───────────────────────────────────────────────────────────────────────────────┤
│  Zoom   Pan   Reference  Fit   Find open area       Moves ●●●○○  [Finish]    │
└───────────────────────────────────────────────────────────────────────────────┘
```

Key: activity ticker is **below the board** as requested; it expands into the recent activity feed. The **World Activity** button opens a full dashboard as a panel or route `/activity`.

### 3.2 Mobile

- Header: destination, countdown, live participant number.
- Puzzle board takes most of the screen.
- Reference becomes floating expandable thumbnail; chat becomes collapsible bottom sheet.
- Piece bag becomes bottom drawer; activity ticker appears just under board with swipeable 1–2 notable items.
- Sticky five-move counter and disabled/enabled interaction state.
- One-finger drag piece, background pan, two-finger zoom, accessible keyboard alternatives where possible.
- Option to hide cursor overlays and animations for performance/accessibility.

### 3.3 Other screens

- `/` — today's event lobby, live overview and join CTA.
- `/play` — the one shared active board.
- `/activity` — full public daily dashboard, chart and recent contributions.
- `/archive` — read-only previous puzzle destinations and outcomes.
- `/archive/{date}` — photograph, final state, time lapse and credits.
- `/about` — how five moves, daily clock and privacy protections work.
- `/privacy` — transparent policy, data retention and deletion process.
- `/admin` — secured non-public content, moderation and operations console.

---

## 4. Anonymous naming — adjective + fruit + exactly three digits

**Format:** `AdjectiveFruitNNN` such as `SleepyMango042`, `CleverPeach807`, `GentleLychee003`. Three digits are zero-padded and always present (`000`–`999`). Enforce ASCII-safe curated words with no spaces, punctuation or offensive combinations. User-friendly but non-sensitive.

**Two separate IDs:**

```json
{
  "identityId": "b4a3617c-733f-4a8f-968f-28a8aef52d16",
  "displayName": "SleepyMango042",
  "avatar": "🥭",
  "cursorColor": "#8B5CF6"
}
```

- `identityId`: server-generated UUID v4 or similar random 128-bit opaque identifier; only for internal association. Not shown to users. Never use username as authorization key.
- `displayName`: unique public alias. `UNIQUE(display_name)` in PostgreSQL. All usernames are generated and allocated by server transaction; retry collisions, or allocate from an indexed pool.
- E.g., 150 adjectives × 100 fruits × 1,000 suffixes = 15 million possible names. Expansion requires more word combinations, not a fourth digit.
- Name is stable across days as long as the anonymous identity persists. Reroll permitted once before first contribution, implemented as an atomic unique-name replacement. Later rerolls optional with cooldown, and previous public event attribution should retain the name used at event time for clarity.
- Public chat, cursors and activity show the `displayName`; never expose IP, user-agent, cookie, real location or internal identifiers.
- Multiple tabs using the same identity share the same move allowance even if they display duplicate avatars; participant count de-duplicates identity IDs.

**Transactional pseudocode:**

```text
begin transaction
  identity_id = cryptographicallyRandomUUID()
  repeat:
     proposed = random(adjectives) + random(fruits) + pad3(randomInt(0,999))
     INSERT anonymous_identities(identity_id,display_name,...)
     ON CONFLICT (display_name) DO NOTHING
  until inserted
commit
```

Use bounded retries and a safe fallback allocation strategy. Never spin infinitely when a small namespace fills.

---

## 5. Identity trust and defending the five-move limit

### 5.1 Truth about anonymous identity

You can reliably enforce **five moves per server-recognized anonymous identity per puzzle**. You cannot reliably enforce **five moves per physical human** without stronger identity proof. A visitor can delete browser storage, open a private window, switch devices or create new credentials. IP addresses and browser metadata cannot prove personhood. Avoid promising otherwise.

Optimize for deterring casual abuse, protecting the shared puzzle from bots, and keeping legitimate users friction-free.

### 5.2 Layer 1: secure server-issued session (launch requirement)

- `POST /api/v1/identity/session` issues a CSPRNG-generated opaque session token; set `__Host-wj_session` cookie with `Secure; HttpOnly; Path=/; SameSite=Lax` (or Strict after evaluating navigation flows), host-only, server-enforced TTL.
- Keep identity and quota in PostgreSQL; session token only authenticates an existing server-side identity. Store session token *hash* rather than raw token server-side, with session rotation/revocation support.
- Cookies identify browser sessions, not real-world people. A second cookie should not create a separate quota; multiple sessions for same identity map to one identity.
- Session is validated on HTTP and WSS handshakes; check message-level authorization thereafter.
- If storage is deleted, the server can't automatically trust a new user is the same person.

### 5.3 Layer 2: browser-held proof-of-possession key (Phase 2 enhancement)

On first successful session creation, generate a **non-extractable asymmetric keypair** with Web Crypto; store the private `CryptoKey` in IndexedDB, and register only the public key with the backend.

At WebSocket connection or high-value action:

1. Server sends a fresh, expiring nonce/challenge bound to session, origin and intended action.
2. Browser signs it using the private key.
3. Server verifies signature with the registered public key and consumes nonce.
4. Session continues under identity ID, subject to quota and risk rules.

**Benefits:** hardens continuity and challenges stolen cookies, demonstrates possession of a registered browser key, and lets the server detect unexpected identity/key changes.

**Limits:** non-extractable does not equal tamper-proof device attestation. Browser data can be deleted; someone can mint unlimited new keys; signing can be triggered by compromised same-origin JavaScript; some browsers evict IndexedDB. **It does not solve Sybil identity fraud.** Provide graceful fallback for unsupported or lost keys; do not silently resurrect deleted user identity across browsing resets.

### 5.4 Layer 3: risk-limited metadata, not invasive fingerprinting

Collect only operationally justified, disclosed coarse signals, and set short retention:

| Signal | Useful for | Is it identity proof? |
|---|---|---|
| Server-side session ID | Continuing a recognized anonymous identity | No |
| Browser public key proof | Returning registered browser key | No |
| IP/network prefix and velocity | Unusual new-session bursts and DDoS/automation hints | No |
| Coarse user-agent family/version | Unexpected changes in an existing session | No |
| Session age / creation timestamp | Newly minted identities trying many moves | No |
| CAPTCHA/Turnstile outcome | Reducing automated new-session spam | No |
| Move rate, invalid commands, scripted timing | Detecting probable automation/abuse | No |
| Request rate / connection count | Rate limiting | No |
| Time zone, GPS, canvas/audio fingerprint | Do not use for identity binding | No |

Do **not** collect location permission, IMEI/MAC, exact street location, persistent canvas/WebGL fingerprints, hidden browser resurrection IDs, or cross-site identifiers. Network location should not lock normal legitimate visitors out simply because they share Wi-Fi or cellular carrier NAT.

Use short-lived HMAC/pseudonymized risk keys for coarse network aggregation, with rotating secrets and documented deletion. Be careful: IP-derived data can still be personal data and hashes are not a magic privacy exemption.

### 5.5 Layer 4: adaptive anti-bot challenges

- Use a managed challenge such as Cloudflare Turnstile when a *new identity* appears abusive, or optionally before its first contribution if launch traffic is heavily bot-driven.
- Validate the challenge **server-side** using the provider API; client widgets alone are not proof of success. Token validation is single-use and time-limited.
- Normal visitors should not see repeated puzzles or challenges for every one of five moves.
- Rate-limit session creation, reconnect storms, chat spam, invalid/malformed group commands and excessive group grabs by identity and, secondarily, coarse network origin.
- Maintain risk tiers: low → allow; medium → challenge at new identity/first move; high → limit new contributions and permit spectating/chat according to moderation policy. Provide explanation and dispute/recovery path.

### 5.6 Optional stronger continuity without email

Offer **Save my name with a passkey**. Using WebAuthn, an opted-in visitor can bind a discoverable passkey credential to their anonymous identity. This is credential-backed continuity, not mandatory conventional account registration. Some passkeys can sync across devices, depending on the credential provider, allowing identity recovery. A malicious actor can still create many passkeys; this is *not proof of one human*. A phone-number or identity-verification gate would be a bigger privacy/friction tradeoff and should not be imposed on the default fun experience.

### 5.7 Threat-response matrix

| Attack | Primary mitigation | Known residual risk |
|---|---|---|
| Refresh to regain moves | Quota keyed by identity+puzzle in DB | None if session retained |
| Open second tab | Shared identity, atomic quota row lock | None within recognized identity |
| Duplicate move frame | Unique command ID, idempotent result | None for correct implementation |
| Delete cookies / private mode | New-identity velocity checks, challenge | Determined actor can evade |
| New browser/device | Device registration/optional passkey | Cannot force same human identity |
| Spoof IP/User-Agent | Never treat as authentication | Coarse signals are spoofable |
| Flood grab requests | Token buckets + ownership lease limits | Botnet can distribute requests |
| Replay stolen session token | TLS, HTTP-only cookie, rotation and optional key binding | Browser compromise remains possible |
| Submit altered `movesLeft` | Server derives quota, never trusts client | None if server correctly verifies |
| False positive shared household | No blanket one-IP-one-user policy | Risk models still need tuning |

**MVP line:** secure cookie + durable quota + idempotency + network rate limiting + adaptive challenge. **Phase 2:** browser signing key + optional passkey. Avoid invasive device fingerprinting; it is brittle, unfair, and still bypassable.

---

## 6. Five-move accounting and confirmation UX

### 6.1 Rules and edge cases

| Action | Cost | Durable? | Public activity? |
|---|---:|---|---|
| Grab/mouse-down | 0 | No | No |
| Cursor movement / drag preview | 0 | No | No |
| Pan, zoom, inspect preview | 0 | No | No |
| Invalid/out-of-bounds drop | 0 | No | No |
| Drop same location, no join | 0 | No | No |
| Bag → table, valid position | 1 | Yes | Possibly aggregated |
| Reposition a loose piece/group | 1 | Yes | Not individually |
| Join piece to piece | 1 | Yes | Yes, aggregate |
| Join group to group | 1 | Yes | Yes |
| Join several groups with one release | 1 | Yes | Yes |
| Anchor completed group to image frame | 1 | Yes | Yes |
| Chat | 0 | Message history only | No, chat separate |
| Finish early | Remaining moves forfeited voluntarily | Yes | Optional |

**Drop validity:** movement above small world-coordinate epsilon, or a real join/anchor, no conflicts with ownership/state revision, within permitted workspace, correct geometry. Group size does not multiply move cost.

### 6.2 Contribution state machine

```text
AVAILABLE(5 remaining) --> PLAYING(1..4 used)
       |                       |
       |                       +-- explicitly finish early --> FINISHED
       |                       |
       +-----------------------+-- fifth accepted move -----> FINISHED

FINISHED or PLAYING -- UTC rollover --> NEW DAILY QUOTA for next puzzle
```

Each valid movement commits on release. If user closes tab with 2 moves remaining, those two are still available on return *until that same day's puzzle ends*. Do not impose a 15-minute timeout because that belonged to the older rolling-lock design.

After the fifth move: immediately disable group grabs and display a small celebration/Confirm button. Clicking Confirm **dismisses the summary**, not a transactional commit. If they never click it, they remain at 0 moves and cannot replay the five actions. An optional **Finish for today** button at 1–4 used should warn it permanently forfeits unused moves for that day's puzzle.

### 6.3 Transactional acceptance

At `group.drop`:

1. Resolve authenticated `identity_id` and the server-current `puzzle_id`.
2. Check if `command_id` already committed; return original result if so.
3. Verify room ownership epoch, group lock/lease, expected revisions and puzzle status/deadline.
4. Compute canonical new transforms, joins and anchor status server-side.
5. Begin PostgreSQL transaction; lock affected contribution row (`SELECT ... FOR UPDATE` or equivalent), verify `moves_used < 5` and not finished, check command uniqueness.
6. Atomically update groups, membership, puzzle version, move history, `moves_used += 1`, and outbox event(s).
7. Commit, acknowledge, then asynchronously broadcast; if publish fails, outbox retries without duplicate logical application.
8. If client-reported result differs from authority, send correction/rollback.

A single identity using two tabs cannot execute six accepted moves because quota updates serialize through the database.

### 6.4 Example payload

```json
{
  "type": "group.drop",
  "commandId": "94f3b0f7-2fb5-4a86-852f-bacb260099b3",
  "puzzleId": "puzzle-2026-11-07",
  "groupId": "group-251",
  "groupRevision": 22,
  "leaseToken": "opaque-grab-lease",
  "transform": { "x": 450.25, "y": 280.0, "rotation": 0 }
}
```

Success ack:

```json
{
  "type": "move.accepted",
  "commandId": "94f3b0f7-2fb5-4a86-852f-bacb260099b3",
  "puzzleVersion": 18422,
  "remainingMoves": 2,
  "event": { "kind": "GROUP_JOINED", "piecesJoined": 3 }
}
```

Failure ack has stable machine-readable code such as `GROUP_BUSY`, `STALE_REVISION`, `OUT_OF_BOUNDS`, `DAILY_LIMIT`, `DAY_ENDED`, `NO_EFFECT`, or `RISK_CHECK_REQUIRED`; no quota charged.

---

## 7. Puzzle engine specification

### 7.1 Immutable definition

- Piece ID, row/col, correct solved transform, edge shape definitions, generated mesh/path and texture crop.
- Deterministically derive tabs/sockets from puzzle seed; shared edges are geometric opposites.
- A photo is fitted/cropped to a defined aspect ratio without distortion; make author and license available in preview credits.
- Launch default no-rotation mode; rotation can be a later expert feature.
- Use PixiJS for canvas/WebGL rendering. Use reusable textures and spatial indexes for hit testing.

### 7.2 Mutable group model

Instead of updating every piece's world location, each connected group contains member piece IDs and one rigid transform. Individual local offsets are derived from solved coordinates.

```ts
type Group = {
  id: string;
  pieceIds: string[];
  transform: { x: number; y: number; rotation: number };
  anchored: boolean;
  revision: number;
};
```

All groups begin as individual pieces in a virtual shared bag/staging area, or lazily instantiated from a known initial distribution. Bag state belongs to the *global puzzle*, not to a player's private browser.

### 7.3 Drag lifecycle

```text
pointerdown -> try_grab -> lease granted -> live drag preview
    -> pointerup -> validate drop -> commit and snap OR rollback
```

- Optimistic local motion feels immediate, but server holds truth.
- Other users see leased group's transient motion only when relevant to their viewport.
- Group is exclusive during drag; second grab is denied with subtle outline.
- Lease example: five seconds renewed during drag, with maximum hold policy and inactivity timeout.
- On lease expiration, disconnect, or rejected movement, return the *entire group*, however large, to last committed valid transform.
- If groups merge, calculate canonical result from server geometry and publish removed IDs + new/updated group.
- If a target group is concurrently leased, reject/defer merge instead of racing another drag.
- When anchored to completed-image frame, group becomes immovable; other groups can join anchored geometry.

### 7.4 Snapping and progress

A snap succeeds only when correctly neighboring pieces/groups are within configured relative-translation/rotation thresholds. Solve using canonical transforms, not visual approximations supplied by client. A single drop may produce multiple joins (one quota move); each merge persists all membership changes atomically.

Track distinct metrics:
- `anchored_piece_count` = pieces fixed in correct final image frame / total pieces (primary progress bar).
- `largest_group_size` = size of biggest assembly (secondary fun metric).
- `connected_piece_count` = pieces belonging to a group of size >= 2 (do not misleadingly call this anchored progress).
- `successful_connections` = number of pair/group merges, deduplicated.
- `total_valid_moves` = accepted drops.
- `unique_contributors` = distinct recognized identity IDs with accepted moves.

### 7.5 Controls and polish

Desktop: left-drag pieces, left-drag background pan, Space-drag pan, wheel zoom around cursor, Escape cancel preview, F fit, H reference overlay, E edge filter, click to inspect. Mobile: one-finger piece drag or empty-space pan, pinch zoom, bottom bag drawer, chat drawer, collapse live overlays. All gesture paths must support pointer capture; cancel on page visibility change.

Visual polish: piece raises under cursor; good matching edge glows; snap eases ~100–150 ms; subtle optional sound; server failure animates spring-back; no quota decrement before ack. Add reduced-motion setting, keyboard equivalents and high-contrast accessible focus states.

---

## 8. Activity system — bottom ticker and real-time dashboard

### 8.1 Product behavior

- **Bottom ticker** (under board): one to three meaningful recent actions, e.g. “🥭 SleepyMango042 joined 4 pieces” and “🥝 BraveKiwi508 anchored a corner group”. Click/tap expands recent activity.
- **Dashboard** (`/activity`): live online count, distinct contributors, total accepted moves, successful joins, anchored pieces/percentage, groups left, time until reset, activity per minute, top *non-competitive* contributors, and recent notable events.
- **Chat** remains a separate panel; chat messages never intermingle with event ticker.
- **Do not show every piece move or mouse click** in the public feed. Server captures underlying accepted moves for correctness; a projector creates feed entries only when meaningful, and coalesces repetitive actions into summaries.

### 8.2 Canonical event → projection

```text
Committed MoveEvent (PostgreSQL + outbox)
        |
        v
Activity Projector
  - classify event
  - deduplicate by event ID
  - group by identity + event category + 5s window
  - aggregate important counts
        |
        +-- recent activity read model (Redis or PostgreSQL)
        +-- compact activity.changed event to WS gateways (<= 1 Hz)
        +-- minute rollups for chart
```

Public types: `PIECES_JOINED`, `GROUPS_MERGED`, `ANCHOR_COMPLETED`, `CONTRIBUTION_FINISHED`, `PUZZLE_COMPLETED`, `DAILY_REVEAL`. Ordinary `GROUP_MOVED` should not become an individual ticker event unless especially meaningful; count it in total stats. A grab, click, drag preview, or invalid move does not enter the public event log.

### 8.3 Events and aggregation examples

Raw persistent events:

```text
12:00:01 SleepyMango042 drop piece 21 from bag
12:00:04 SleepyMango042 drop piece 22 from bag
12:00:06 SleepyMango042 connect pieces 21 & 22
12:00:09 BraveKiwi508 attach group of 3 to group of 4
```

Projected feed:

```text
🥭 SleepyMango042 placed 2 pieces and made 1 connection
🥝 BraveKiwi508 connected 2 groups (7 pieces total)
```

Avoid action spam by coalescing per identity/category over a rolling window (start with five seconds, tunable). Maximum 10–20 visible entries, more from paged history. Feed flush interval ~1 s. Never generate a *second* gameplay commit because a feed entry was created.

### 8.4 Data model and display samples

```json
{
  "type": "activity.batch",
  "puzzleId": "puzzle-2026-11-07",
  "latestVersion": 18422,
  "items": [
    {
      "activityId": "act-9002",
      "at": "2026-11-07T12:00:12Z",
      "actorName": "SleepyMango042",
      "actorAvatar": "🥭",
      "kind": "PIECES_JOINED",
      "summary": "connected 3 pieces",
      "groupSize": 3
    }
  ]
}
```

Never insert raw IP, UUID, session token, browser metadata, or public geolocation into activity. Add opt-out from detailed named leaderboard if needed, while showing aggregated room progress.

### 8.5 Dashboard data projections

- `activity_minute_rollups(puzzle_id, minute_bucket, accepted_moves, merges, anchored_delta, contributors_active)`.
- `daily_stats(puzzle_id, online_count_estimate, contributors, accepted_moves, merged_groups, anchored_pieces, completion_pct, last_event_at)`.
- `recent_activity(puzzle_id, activity_id, public_summary, created_at)` with capped retention.
- On reconnect, fetch last 20 projected activities via HTTP; subscribe for new batches; deduplicate by `activityId`.

Metrics are updated asynchronously for dashboard only. **Game integrity never depends on dashboard freshness**. Show `updated seconds ago` if feed falls behind.

---

## 9. Live chat and presence

- One global room chat accompanying the one daily puzzle. Lobby and board can display the same global channel, with separate UI presentation rather than duplicate logical rooms.
- One anonymous name per participant; fruit avatar and cursor color.
- Prevent spam: message length cap, per-identity token bucket, repeat-message suppression, report/mute functions, moderation queue, automatic cooldown for abusive chat.
- Durable recent chat messages in PostgreSQL with an explicit retention period; optional ephemeral typing indicator in Redis.
- Counting online means approximate distinct active recognized identities, not socket count (same user with two tabs counts once). Heartbeats/TTL, debounce count updates.
- UI allows hide chat and hide remote cursor overlays without leaving shared puzzle.

---

## 10. Network and real-time scaling

### 10.1 Traffic classes

| Class | Examples | Persistence | Delivery rule | Rate |
|---|---|---|---|---|
| Authoritative mutations | group drops, joins, anchors, quota | PostgreSQL | Ordered, acked, idempotent, resyncable | On valid action |
| Critical lifecycle | day reset, completion, quota lock | PostgreSQL | Durable events + snapshot/reconnect | On event |
| Semi-live projections | activity ticker, stats, presence | cache/read model | Batches, periodic repair | ~1 Hz or slower |
| Ephemeral interaction | cursors, drag previews, clicks | None | Best effort, droppable | Capped/sampled |
| Chat | messages | PostgreSQL recent history | Ack, moderated, replay recent | Low user rate |

### 10.2 The N-squared cursor problem

At 10,000 connected users, if each sends ten pointer updates/second, this is 100,000 incoming updates/second. Naively forwarding every update to every viewer approaches a billion outgoing messages/second. **Do not do this.**

- Only publish cursor updates when pointer is moving, and throttle client to about 5–10 Hz; cap server rates per identity.
- Partition the virtual canvas into viewport interest regions / spatial cells. Clients subscribe to their visible region and maybe adjacent cells.
- Gateway sends a sample of nearby active cursors; cap e.g. 20–30 displayed remote cursors per client; prefer users actually dragging relevant groups. Use deterministic sampling so names don't flicker randomly.
- Send cursor state as delta/coalesced messages, not per-frame updates. Drop older queued cursor packets under backpressure.
- For viewers outside region, send only global activity and progress totals, never global pointer traffic.
- Add a `Hide others' cursors` toggle and lower animation rate for low-end devices.

### 10.3 Board update fanout

Everyone must converge on one durable board, but not necessarily receive every transient visual gesture:

1. HTTP serves immutable puzzle geometry and image assets through CDN.
2. Initial current snapshot through HTTP or WS; versioned state.
3. After valid movement, server emits compact `room.patch(version, changedGroups, removedGroups, statsDelta)`.
4. Gateways batch/coalesce nondependent patches for their subscribers under load, without skipping logical versions without a recoverable checkpoint.
5. Client detects version gaps and resyncs via snapshot + bounded event replay.
6. Optional view-based snapshots for very large boards plus authoritative whole-board version. Panning loads missing regions and revisions; server always maintains one global truth.

### 10.4 Room owner and actor model

One active puzzle initially maps to **one authoritative Java coordinator actor** with a single command processing queue, plus horizontally scaled WebSocket gateway pods. This serializes conflicting game mutations and makes correctness tractable. Identity quotas still transact in PostgreSQL.

The gateway layer can scale horizontally for connection volume; the puzzle actor remains a throughput bottleneck by design. Measure it. A single daily board is an intentional hot key; simply deploying many game processors does *not* magically parallelize the same state.

If measurement demands higher mutation throughput:

- Partition work by **puzzle region / solution-coordinate zones** for routine independent moves.
- Keep a global authoritative merge/anchor coordinator for cross-region joins, or design explicit ordered multi-zone commands with safe ownership handoff and durable transaction semantics.
- Never allow independently authoritative processors to accept conflicting edits to the same group.
- Use epoch/fencing IDs for actor ownership transitions and database-backed conditional commits.
- Treat spatially partitioned game mutation as a later, separately load-tested optimization; start with a single actor per global puzzle.

### 10.5 Redis vs durable stream

Redis Pub/Sub is **at-most-once**: excellent for cursors and disposable presence, insufficient as sole source for committed moves. Use PostgreSQL transactional outbox + a retrying publisher for committed game events; durable event catch-up via DB or persisted stream. Introduce Kafka or Redis Streams for higher-throughput durable projections after real need appears. Public dashboard and fanout can be rebuilt from persisted gameplay events.

---

## 11. Distributed deployment topology

```text
                         CDN / edge / WAF
                          /      |       \
                     Static    HTTP API   WebSocket LB
                       |         |        /    |    \
                   React app   API pods   WS Gateway pods (N)
                                   \       |       /
                                  Command routing
                                         |
                              DAILY GAME COORDINATOR
                              authoritative actor + lease
                                 /         |          \
                           Postgres     Redis        Image worker
                         state/outbox  transient      + S3 CDN
                                 |
                          Activity projector
                            /         \
                      minute stats    ticker batches
```

Components (start modular in one Spring Boot binary, split when ready):

1. **Identity module:** anonymous sessions, naming, browser key registration and risk signals.
2. **Catalog module:** licensed image assets and scheduled daily puzzles.
3. **Game coordinator:** group leases, geometry validation, merges, authoritative placement, quota mutations.
4. **Gateway:** WebSocket lifecycle, subscription, routing, rate limiting, fanout and backpressure.
5. **Activity projector:** aggregated activity and per-minute statistics.
6. **Chat module:** moderated global messages.
7. **Rollover scheduler:** idempotent UTC activation/archival/completion reconciliation.
8. **Media worker:** puzzle generation and optimized textures.
9. **Operations console:** content queue, health, bans, moderation, repair.

### Recovery

- Coordinator holds owner lease with monotonically increasing `owner_epoch`.
- On crash, new leader acquires epoch, loads latest saved snapshot, replays durable changes, cancels transient drag leases, resumes at next version.
- Old leader's delayed writes fail fencing checks.
- Gateways require resync after leadership change. No accepted committed group mutation can be lost; clients may briefly see drag rollback.
- Identity quota and replayed command ID are durable across failover.

---

## 12. Data model (PostgreSQL)

Core tables:

```text
anonymous_identities(
  id UUID PK, display_name TEXT UNIQUE, avatar TEXT, cursor_color TEXT,
  created_at, last_seen_at, status, optional_public_key_id
)

identity_sessions(
  session_id UUID PK, identity_id UUID FK, secret_hash BYTEA,
  created_at, expires_at, revoked_at
)

identity_browser_keys(
  key_id UUID PK, identity_id UUID FK, public_key BYTEA,
  created_at, last_verified_at, revoked_at
)

identity_risk_events(
  id UUID PK, identity_id nullable, kind, coarse_network_key,
  created_at, expires_at, decision
)

daily_puzzles(
  id UUID PK, puzzle_day DATE UNIQUE, image_id UUID FK,
  status, starts_at, ends_at, completed_at, seed, piece_count,
  owner_epoch, state_version, anchored_count, accepted_moves
)

piece_definitions(
  puzzle_id UUID FK, piece_id INT, row, col, solved_x, solved_y,
  edge_geometry JSONB, PRIMARY KEY(puzzle_id,piece_id)
)

puzzle_groups(
  id UUID PK, puzzle_id UUID FK, position_x, position_y,
  rotation, anchored, revision, updated_at
)

puzzle_group_members(
  puzzle_id UUID FK, piece_id INT, group_id UUID FK,
  PRIMARY KEY(puzzle_id,piece_id)
)

daily_contributions(
  identity_id UUID FK, puzzle_id UUID FK, moves_used INT CHECK (moves_used BETWEEN 0 AND 5),
  finished_at nullable, version INT, PRIMARY KEY(identity_id,puzzle_id)
)

move_commands(
  command_id UUID PRIMARY KEY, identity_id UUID FK, puzzle_id UUID FK,
  result_code, resulting_puzzle_version, move_cost, created_at
)

puzzle_events(
  puzzle_id UUID FK, event_version BIGINT, command_id UUID UNIQUE,
  identity_id UUID, kind, payload JSONB, created_at,
  PRIMARY KEY(puzzle_id,event_version)
)

puzzle_snapshots(
  puzzle_id UUID, version BIGINT, state_blob BYTEA, created_at,
  PRIMARY KEY(puzzle_id,version)
)

outbox_events(
  id UUID PK, topic, aggregate_id, event_version, payload JSONB,
  created_at, published_at nullable, attempts INT
)

activity_events(
  id UUID PK, puzzle_id UUID, public_name TEXT, kind,
  summary TEXT, created_at, source_event_version BIGINT
)

activity_minute_rollups(
  puzzle_id UUID, minute_bucket TIMESTAMPTZ, accepted_moves,
  merges, anchors, unique_contributor_estimate,
  PRIMARY KEY(puzzle_id,minute_bucket)
)

chat_messages(
  id UUID PK, puzzle_id UUID, identity_id UUID, public_name TEXT,
  message TEXT, created_at, moderated_at nullable
)

image_assets(
  id UUID PK, city_name, country_code, storage_key, thumbnail_key,
  author_name, source_url, license_name, license_url,
  license_verification, approved_at, status
)
```

Indexes: `puzzle_events(puzzle_id,event_version)`, `daily_contributions(identity_id,puzzle_id)` unique, `puzzle_groups(puzzle_id)`, `chat_messages(puzzle_id,created_at DESC)`, `activity_events(puzzle_id,created_at DESC)`, `identity_sessions(identity_id,expires_at)`, outbox by `published_at`. Prefer retention/partitioning for high-volume event, chat and risk tables.

### Consistency invariants

1. Piece belongs to exactly one current group.
2. Every group's set of pieces forms a valid connected component of the solution adjacency graph.
3. Anchored groups cannot move; completed puzzle has exactly one correct anchored component with all pieces.
4. Each puzzle version is unique and monotonically increasing.
5. For any `(identity_id,puzzle_id)`, `0 <= moves_used <= 5`.
6. Each accepted command ID incurs at most one move, even across retries/failover.
7. No command for an expired puzzle may commit.
8. Last committed position can always be reconstructed.
9. Activity/feed projection never changes canonical puzzle state.

---

## 13. API and WebSocket contracts

### HTTP

```text
POST /api/v1/identity/session       create/restore recognized anonymous visitor
GET  /api/v1/identity/me            public profile + today's quota
POST /api/v1/identity/rename        one-time allowed alias reroll
POST /api/v1/identity/key           register browser public key (later)
POST /api/v1/identity/challenge     request challenge to prove key possession
GET  /api/v1/daily                 active puzzle meta + serverTime + starts/ends
GET  /api/v1/daily/snapshot        authoritative board snapshot, version
GET  /api/v1/daily/events?after=  durable move event catch-up
GET  /api/v1/daily/activity        recent aggregated public activity
GET  /api/v1/daily/stats           dashboard totals and chart buckets
GET  /api/v1/daily/chat           recent moderated global chat
GET  /api/v1/archive             paginated past puzzles
GET  /api/v1/archive/{date}      read-only solved/uncompleted state + credits
```

### WebSocket

```text
wss://api.example.com/ws
```

Client → server:

```text
session.hello          bind cookie-authenticated session and optional key proof
puzzle.subscribe       subscribe to daily state/selected viewport cells
viewport.update        change interest subscription for nearby cursors
cursor.move            throttled and best effort
cursor.click           optional visual pulse, best effort
piece.grab             request exclusive group lease
piece.drag             transient drag preview (throttled)
piece.drop             accepted mutation request; requires commandId
piece.cancel           release without move
contribution.finish    voluntarily finalize daily moves / dismiss summary
chat.send              moderated and rate limited
presence.heartbeat     renew presence
```

Server → client:

```text
session.ready          public identity + serverTime + daily quota
puzzle.snapshot        full immutable/mutable state + version
puzzle.patch           authoritative changed group(s) + version
piece.granted          lease token and expiration
piece.denied           busy or invalid state
piece.dragging         sampled remote movement
move.accepted          commandId + remaining moves + version
move.rejected          reason + rollback transform
cursor.batch           nearby cursor samples
chat.message           accepted chat message
activity.batch         aggregated recent actions
stats.updated          chart/counter projection
presence.updated       online count estimate
puzzle.completed       live celebration
puzzle.rolled_over     new puzzle ID, serverTime and reset deadline
resync.required        client must catch up
```

**WS security:** WSS, strict Origin allowlist, cookie auth + CSRF considerations, session validation and periodic revalidation, per-message authorization, strict schema and message-size limits, rate limiting and backpressure. Never put session tokens in URL query strings. Validate all client geometry commands and reject stale revisions.

---

## 14. Time synchronization and reset edge cases

The daily countdown should use `serverTime` and `endsAt` supplied together. Calculate `offset = serverTime - clientLocalTime`, then render from a monotonic performance clock; periodically resynchronize offset on HTTP or WS pings. Timer is visual only; Java decides whether a move is valid.

Test cases:

- Browser clock intentionally set wrong: timer still near correct, backend authoritative.
- Player drags before midnight and releases after: reject no quota, rollback old piece, transition to new puzzle.
- Network delay causes an ack after midnight for a move committed before midnight: it remains accepted in old puzzle history, but old board is now archive; don't apply it as a new puzzle command.
- User disconnects at reset: next HTTP load or reconnect fetches new puzzle and quota.
- Two scheduler instances try same rollover: unique day/active fencing ensures only one wins.
- Next image is not prepared: fail safe to explicitly announced maintenance/fallback approved asset, not a broken board.
- Server restarts at midnight: DB-driven active-puzzle reconciliation restores correct day.
- Completed puzzle at 13:00 UTC: preserve finished event and celebrate; don't falsely give new moves until next day's reset.

---

## 15. Artwork ingestion, licensing and scheduling

Fine Art America can be a discovery/inspiration source but artwork cannot safely be republished or cropped automatically simply because it can be viewed online. Acquire a proper usage license/permission including interactive redistribution, or use photographs with verified rights for web puzzle adaptation. Wikimedia Commons has reusable assets, but each file's attribution/share-alike terms need individual review.

Admin flow:

1. Search/select candidate photograph of a city/country.
2. Save source URL, artist name, license, allowed derivative/use conditions and evidence.
3. Approve/deny for commercial web interactive use.
4. Download authorized high-resolution image and verify quality/crop.
5. Generate thumbnail, preview, texture atlas, puzzle seed, edge geometry and metadata.
6. Preview solved/unsolved puzzle and rendering performance.
7. Schedule for a date; enforce unique puzzle per UTC day.
8. Audit that following days have at least seven approved images queued.
9. Show photographer attribution and license links on preview and archive.

Avoid real-time scraping when users visit. Every daily puzzle asset should be ready before reveal and cached through CDN.

---

## 16. Real-world concurrency scenarios

### Scenario A: Two visitors grab the same group

The authoritative game actor grants one short lease. Winner moves; loser sees small owner outline and can grab something else. Grab itself costs zero moves. Lease expires on drop, cancel, disconnect or inactivity; old stale leaseToken rejected.

### Scenario B: Two tabs sharing same identity submit their fifth move simultaneously

Both requests carry distinct IDs. DB serializes `daily_contributions` row. First valid drop increments 4→5; second sees no remaining moves and is rejected (no extra move). Both tabs receive `quota.locked`/updated counts.

### Scenario C: Player's group connects to a third party's moving group

Reject/defer the join because target group is leased. Allow a legal staging drop as a reposition (1 move) only if accepted position is valid and player chooses that placement; otherwise rollback without consuming a move. Do not move another player's group as a side effect.

### Scenario D: Reconnect after accepted drop but lost acknowledgement

Client retries same `commandId`; backend detects prior outcome, replays same accepted ack with original room version and quota; no second move charged.

### Scenario E: Heavy cursor load

Interest region and server-side max-cursor cap; sample at low Hz. Aggressively drop ephemeral frames before delaying board commits or chat.

### Scenario F: Coordinator crashes after DB commit before fanout

Outbox republish and client revision-gap detection ensure eventual state convergence; never reverse a committed move just because no WebSocket ack was received.

---

## 17. Java backend design and packages

Use Java 21, Spring Boot, Gradle or Maven, PostgreSQL JDBC/JPA for catalogs and identity, explicit transactional SQL for hot move path, Redis client, Micrometer/OpenTelemetry.

```text
services/backend/src/main/java/com/worldjigsaw/
  api/             HTTP controllers and DTOs
  identity/        AnonymousIdentityService, NameAllocator, SessionService
  risk/            AbuseRiskService, ChallengeVerifier, RateLimitService
  catalog/         ImageAsset, DailyPuzzleScheduler
  daily/           DayRolloverService, PuzzleLifecycleService
  puzzle/          PieceDefinition, GroupState, SnapEngine
  coordinator/     RoomActor, CommandRouter, LeaseManager, OwnerEpoch
  contribution/    MoveQuotaService, ContributionRepository
  persistence/     MoveTransactionService, EventStore, SnapshotStore
  outbox/          OutboxPublisher
  activity/        ActivityProjector, ActivityQueryService
  chat/            ChatService, ModerationService
  realtime/        WebSocketHandler, SubscriptionManager, FanoutService
  metrics/         PuzzleMetrics
```

Service boundaries are logical first. In deployment stage one they can share a single JVM so end-to-end tests are straightforward. Later split WS gateway, coordinator and projector using stable protocols.

**Hot-path Java-style pseudocode:**

```java
MoveResult handleDrop(Identity id, DropCommand c) {
    return transaction.execute(() -> {
        MoveResult prior = moveRepo.findResultByCommandId(c.commandId());
        if (prior != null) return prior;

        DailyPuzzle puzzle = puzzleRepo.lockActivePuzzleForWrite(c.puzzleId());
        validateCurrentDayAndOwnerEpoch(puzzle, c);
        Contribution quota = contributionRepo.lockOrCreate(id.id(), puzzle.id());
        if (quota.movesUsed() >= 5 || quota.isFinished())
            return MoveResult.rejected("DAILY_LIMIT");

        GroupMutation mutation = snapEngine.validateAndCompute(c, puzzle);
        if (!mutation.hasMeaningfulChange())
            return MoveResult.rejected("NO_EFFECT");

        groupRepo.apply(mutation);
        quota.incrementMove();
        long version = puzzleRepo.incrementVersionAndProgress(puzzle, mutation);
        eventRepo.insertUnique(c.commandId(), id.id(), puzzle.id(), version, mutation);
        outboxRepo.insertFromCommittedMove(puzzle.id(), version, mutation);
        return MoveResult.accepted(c.commandId(), version, 5 - quota.movesUsed());
    });
}
```

Real implementation must handle database uniqueness races and failed transactions, isolate geometry computation from held locks where possible, verify ephemeral lease and epoch with the authoritative actor, and revalidate mutable revisions under commit. Pseudocode illustrates atomicity, not a copy-paste complete concurrency algorithm.

---

## 18. Web frontend packages

React + TypeScript + Vite + PixiJS, Zustand for small client state, TanStack Query for HTTP cache, a single dedicated WebSocket client module.

```text
apps/web/src/
  app/             routing and app bootstrap
  pages/           TodayPage, PlayPage, ActivityPage, ArchivePage
  components/
    header/        GlobalCountdown, IdentityChip, OnlineCount
    puzzle/        PuzzleCanvas, PieceBag, Preview, CameraToolbar
    contribution/  FiveMoveBar, ConfirmSummary, SpectatorBanner
    activity/      BottomTicker, ActivityDrawer, DashboardCharts
    chat/          ChatPanel, MessageRow, ReportDialog
  engine/
    generation/    deterministic tabs, sockets, masks
    rendering/     atlas, display objects, hit tests
    group/         rigid group transform and caching
    input/         pointer capture, drag, pan/zoom, touch
    snapping/      client-only predictive preview
  realtime/        socket, schema validation, patch sequencing, resync
  stores/          puzzle, transient cursors, identity, quota, view state
  api/             http client
  styles/          tokens and responsive layout
```

Visual game state should separate authoritative `committedGroups` from `localDragPreview` and `remoteDragPreviews`. Rendered drag motion does not mutate the canonical store until a server ack/patch arrives.

---

## 19. Metrics, dashboards and performance budgets

**Proposed targets to validate with measured load (not performance claims):**

- A 60/150-piece offline puzzle can be completed flawlessly on desktop and mobile first.
- 300–600-piece daily puzzles on launch with deterministic generation; larger boards after profiling.
- Client pointer tracking/drag locally targets 60 FPS on representative desktop, graceful 30+ FPS on older mobile hardware.
- Healthy-region accepted drop acknowledgement p95 target < 250 ms, measured from client release to ack.
- Relative real-time group update target p95 < 300 ms in same region at tested load, with degradations visible in telemetry.
- Cursor updates emitted <= 10 Hz; delivered only to selected nearby viewers; cap displayed remote cursors ~20–30.
- Activity ticker <= 1 broadcast/s to each gateway subscription; chat independently rate limited.
- Reliable replay from snapshot + durable events, no double charges, no lost accepted moves.
- Load test 100, 1,000, 10,000 viewers and separately 10, 50, 200 simultaneous *active dragging* visitors, since viewer and editor loads differ.

Metrics:

```text
worldjigsaw_ws_connections
worldjigsaw_presence_distinct_identities
worldjigsaw_active_drags
worldjigsaw_grab_denials_total
worldjigsaw_moves_accepted_total
worldjigsaw_moves_rejected_total{reason}
worldjigsaw_move_commit_duration_seconds
worldjigsaw_puzzle_version_lag
worldjigsaw_gateway_outbound_bytes
worldjigsaw_cursor_dropped_packets_total
worldjigsaw_resync_total
worldjigsaw_activity_projector_lag_seconds
worldjigsaw_rollover_failures_total
worldjigsaw_new_identity_velocity
worldjigsaw_risk_challenge_rate
worldjigsaw_coordinator_owner_changes_total
```

SLO-focused alerts should watch move success/latency, stuck active puzzle or rollover, outbox lag, coordinator fencing failures, serious client version drift and abuse surges. Do not treat high chat volume as system success if gameplay is broken.

---

## 20. Testing plan and acceptance criteria

### Unit: puzzle geometry

- Shared Bézier edges match exactly; non-neighbors never connect.
- Group transforms preserve relative offsets after merges and drags.
- Large group invalid drop restores entire last committed position.
- Deterministic seed generates identical geometry across clients and Java validation.
- Multiple adjoining groups can merge atomically with a single move charge.
- Anchored groups cannot be moved.
- Piece counts, group memberships and anchored count are consistent.

### Identity/quota

- `SleepyMango042` name has exact 3 digits and unique allocation under concurrent creation.
- Public names never become auth keys.
- Both tabs share quota; 4→5 and attempted 6th move results in exactly 5 accepted.
- Refresh/reconnect same session does not replenish quota.
- New UTC puzzle grants fresh quota; prior day remains immutable.
- Expired cookie/new browser receives new identity unless optional recovery succeeds; do not claim same-human detection.
- Challenge tokens verified server-side; replay rejected.
- Browser key challenge rejects stale nonce or wrong public key.

### Distributed failures

- Node crash before transaction commit: no move charged and no change committed.
- Crash after commit before ack: same `commandId` returns original outcome.
- Gateway misses a patch: resync and exact state convergence.
- Old owner tries writing after lease transfer: fenced out.
- Multiple rollover attempts: exactly one active puzzle.
- Drop crosses UTC boundary: no accepted old-day mutation after deadline.
- Activity projector restarts/replays: no duplicated public feed items.
- Redis outage: durable game data not lost; degrade ephemeral features gracefully.

### UX automation (Playwright)

1. Two browser contexts open one global puzzle and see same version.
2. A grabs piece 12; B sees remote drag if in same viewport.
3. B tries to grab same group; denied; quota unchanged.
4. A joins pieces; both see exact same merged group.
5. A makes five accepted moves; bar reaches zero and group grab disables.
6. A refreshes and remains at zero; B still has its own moves.
7. A can chat and see bottom activity ticker while locked.
8. Browser clock is wrong; countdown and server validation still correct.
9. Force rollover; everyone sees new destination and fresh quota.
10. View 24-hour archive; old puzzle is read-only.

### Product acceptance definition

A fresh visitor joins with `AdjectiveFruitNNN`, sees one global daily destination and countdown, drags and snaps pieces in real time with another browser, has exactly five accepted moves enforced across tabs, views a low-noise activity ticker and live chat, sees all accepted moves survive refresh/failure, and receives fresh quota on next UTC daily puzzle.

---

## 21. Step-by-step implementation roadmap and deliverables

### Phase 0 — Definition and prerequisites

- Finalize daily reset policy, quota semantics, UI maps, database invariants, licensing requirements and identity privacy policy.
- Decide a representative first licensed photo and fixtures with 60/150 pieces.
- Write event schemas and fixture tests; define metrics before architecture splitting.

**Exit:** everyone can describe what counts as a move, when quota resets, and what Confirm does.

### Phase 1 — Standalone puzzle engine (no network)

- Deterministic jigsaw piece geometry and image clipping.
- PixiJS renderer, pan/zoom, bag, drag/select, snap, connected groups, anchored frame and rollback.
- Test full completion and mobile touch interactions with 60/150 pieces.

**Exit:** satisfying single-browser puzzle with deterministic placement and no rendering bugs.

### Phase 2 — Authoritative Java + PostgreSQL

- Define immutable puzzle, mutable groups, accepted command events and snapshots.
- Implement game actor and geometric validation in Java.
- Persist accepted moves transactionally; test duplicate command handling and revision conflicts.

**Exit:** refresh/load reproduces exact state; server rejects impossible joins.

### Phase 3 — WebSockets and two-browser collaboration

- Spring WS handler, source-of-truth event patches, leases, remote drag previews, resync and reconnect.
- Add live cursors limited to room viewport.

**Exit:** two clients independently see same authoritative moves and conflicting grabs resolve deterministically.

### Phase 4 — Anonymous identities and five daily moves

- Unique names with three digits, session cookie, per-puzzle contribution quota, confirm summary and two-tab transaction tests.
- Add rate limiting, first abuse challenge checks and privacy controls.

**Exit:** refresh, retries and multi-tab concurrency cannot exceed five moves for a recognized identity.

### Phase 5 — Daily rollout, lobby and archives

- Curated photo catalog, active-puzzle scheduler, UTC clock, rollover, expiry/completion and historical gallery.
- World lobby, large board screen and small-screen layouts.

**Exit:** date boundary cleanly changes destination and quota while preserving old boards.

### Phase 6 — Chat, ticker and full dashboard

- Moderated chat, presence count, activity projector and 1-second batches, recent timeline and minute metrics.
- Bottom activity ticker that filters low-value movement.

**Exit:** names, meaningful activity and counters are useful without drowning players in events.

### Phase 7 — Hardening and distribution

- Outbox replay, Redis gateway signaling, multi-node WS scale, fencing/leader handoff, backpressure, observability.
- Introduce browser signing key, optional passkey, nuanced abuse rules.
- Large-puzzle profiling, many-viewer load tests, coordinated crash tests.

**Exit:** measured capacity at explicit loads, reliable failover, clear degradation modes, no data loss and no double move charge.

### Phase 8 — Public release and iteration

- Curate at least a week of approved artwork.
- Release a small beta, measure puzzle completion time and abandonment.
- Tune piece count, snap threshold, activity batching, cursor cap and challenge thresholds.
- Consider time-lapse replays, contributor history, optional profiles and worldwide daily statistics.

---

## 22. Work tracking — epic breakdown

**Epic A — Core engine:** shape generator, deterministic seed, group transform, snap validator, bag, anchors, camera, UI input, tests.

**Epic B — Persistence:** DB schema/migrations, command IDs, event store, snapshots, transactional quota, outbox.

**Epic C — Real time:** WS auth/protocol, remote cursors, leases, stale revision rejection, reconnect/resync.

**Epic D — Anonymous identity:** session management, unique adjective/fruit/three-digit allocator, nickname/avatar, risk signals, key binding later.

**Epic E — Daily:** scheduling, countdown offset, completion/expiry, reset fanout, archives, daily contribution reset.

**Epic F — Social:** global chat, moderation, occupancy, bottom activity ticker, dashboard queries and rollups.

**Epic G — Media:** licensed image review, processing, generation, publication queue and attribution.

**Epic H — Scale:** gateways, message batching/interest regions, actor ownership, fenced failover, load tests, metrics and dashboards.

---

## 23. Product compromises and decisions to validate in beta

1. **One board, many visitors:** expect early completion or contention if popular. Adaptive *next-day* difficulty, view-based editing, and good spectating matter. Keep one global board, not secretly separate copies.
2. **Five moves vs fairness:** a single group drag counts one regardless of group size; this is simple and fun, but can be gamed with low-value repositioning. Provide a visible contribution count vs meaningful connection count, rather than silently charging extra for group size.
3. **Identity trust:** user-requested 'no login' fundamentally conflicts with strict one-human-five-moves enforcement. Communicate best-effort protections; consider optional passkey for retention/recovery rather than turning the game into surveillance.
4. **Confirm:** do not let a multi-move client-side batch overwrite collaborators. Commit individually and use Confirm as completion UX only.
5. **Daily clock:** choose 00:00 UTC first, revisit after community feedback. Set globally, not by visitor locale.
6. **No meaningless public noise:** store valid moves for integrity, aggregate feed, and never show mouse clicks as ticker entries.
7. **Image rights:** verify every photo. 'Available online' is not a license to republish an interactive crop.

---

## 24. Public README / interview overview

### README elevator pitch

> World Jigsaw is a distributed, real-time collaborative daily puzzle where anonymous visitors worldwide share a single canvas, receive five authoritative moves per day, and race a common UTC countdown. Java/Spring Boot coordinates concurrent group movement and transactional contribution limits; React/PixiJS renders jigsaw geometry and WebSocket interactions; PostgreSQL events make accepted moves replayable; Redis and interest-based fanout keep cursor, presence and chat responsive; an aggregated activity projector powers a live ticker and dashboard.

### The three most interesting engineering deep dives

1. **The hot-room coordinator:** ensuring exactly one authoritative sequence of accepted group merges while supporting thousands of connected spectators and concurrent active editors. Explore ownership leases, revision checks, spatial interest regions, leader fencing and failover.
2. **Five moves exactly once per identity and daily puzzle:** two tabs racing to make a fifth move, duplicated WebSocket frames, network timeouts, idempotent command records and PostgreSQL transactional updates. Distinguish identity continuity from proving personhood.
3. **Separate ephemeral from durable streams:** a cursor event can be dropped, but an accepted jigsaw connection cannot. Contrast Redis Pub/Sub with transactional outbox, durable event log, snapshot/replay and dashboard projections.

Use real load numbers in your résumé only **after measuring them**. The blueprint is a design, not a claim that the application already handles a specific number of users.

---

## 25. Engineering references

- OWASP Session Management Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/Session_Management_Cheat_Sheet.html
- OWASP WebSocket Security Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/WebSocket_Security_Cheat_Sheet.html
- Cloudflare Turnstile server-side validation: https://developers.cloudflare.com/turnstile/get-started/server-side-validation/
- MDN Web Crypto non-extractable keys: https://developer.mozilla.org/en-US/docs/Web/API/CryptoKey/extractable
- MDN Passkeys: https://developer.mozilla.org/en-US/docs/Web/Security/Authentication/Passkeys
- Redis Pub/Sub delivery semantics: https://redis.io/docs/latest/develop/pubsub/
- Wikimedia Commons reuse guidance: https://commons.wikimedia.org/wiki/Commons:Reusing_content_outside_Wikimedia
- Fine Art America digital image license offerings: https://fineartamerica.com/tour/sell-digital-downloads

**Final definition of done:** Two independent anonymous browsers, each with unique public `AdjectiveFruitNNN` aliases, can enter the same current-day global board, observe each other's relevant interactions, make correct authoritative jigsaw movements with at most five accepted moves per recognized identity per puzzle, read a quiet bottom activity ticker and chat, survive refresh and failover without losing committed state, and see the next global puzzle/reset together at the UTC day boundary.
