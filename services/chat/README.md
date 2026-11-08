# Local chat service

This independently runnable Java 21 service relays short messages in one live room. It binds
only to `127.0.0.1` and does not contact the game, its database, a gateway, or any infrastructure
service. It does not currently require a database: no durable chat state or retention policy has
been agreed. A separate chat-owned database and credentials must be provisioned for integration;
never add message bodies or a transcript to this scaffold.

From `services/chat`, run `./gradlew run`. Set `CHAT_PORT` to change the default port 8081.
Run `./gradlew build` for formatting and tests. No Docker, game process or external service is
required. `GET /actuator/health` reports process liveness and `GET /api/v1/chat/config` reports
the local validation version and limit.

## Identity and private use

The default `CHAT_IDENTITY_MODE=local` is a **development-only identity adapter**, not
authentication. Supply a lowercase canonical UUID in `X-Local-Subject` on requests. It is
caller-selected and can be impersonated; the first 8 characters form a public display name. Use
a disposable random UUID, not a secret or real identifier.

For the proposed game assertion integration, explicitly set `CHAT_IDENTITY_MODE=assertion`,
`CHAT_ASSERTION_ISSUER`, `CHAT_ASSERTION_KEY_ID`, and `CHAT_ASSERTION_PUBLIC_KEY` (standard Base64
encoded Ed25519 X.509 SubjectPublicKeyInfo). Missing or invalid settings prevent startup; there is
no automatic fallback. Use `Authorization: Bearer <assertion>` on both endpoints. The service
checks Ed25519 JWT signature and key ID, exact issuer and `aud=world-jigsaw-chat`, canonical UUID
subject and token ID, signed `schemaVersion:1` and `displayName`, and integer `iat`/`exp` with a
maximum 300-second lifetime and no clock skew. Names must be NFC and trimmed, 1–40 Unicode 15.1
graphemes, at most 128 UTF-16 units, and contain no controls. Each stream closes on expiry. Only
one public key is configured; changing it requires a controlled restart. There is no revocation,
replay prevention or seamless key rotation. The game issuer is not implemented in this service;
these claims require integration with that owner and a trusted key provisioning channel.

Do not expose this service publicly: the identity boundary, origin policy, resource controls, abuse
prevention, key management, transport security and moderation require security review. The service
accepts only loopback Host and Origin values and does not add CORS headers. A future private proxy
must be deliberately configured and reviewed; container networking is not configured here.

## Live protocol

`GET /api/v1/chat/stream` returns server-sent events. A browser should use same-origin streaming
`fetch` with the Bearer header, not native `EventSource`, which cannot set that header. The proxy
must not buffer SSE. Refresh the assertion and reconnect when the stream closes; reconnect starts
empty and `Last-Event-ID` is ignored. Each default `message` event has JSON fields `schemaVersion`,
`messageId`, `displayName`, `text` and `sentAt`. Clients must render text and names as text, never
as HTML, and discard prior displayed messages on reconnect when a strictly live display is needed.

`POST /api/v1/chat/messages` accepts UTF-8 JSON
`{ "schemaVersion": 1, "messageId": "<lowercase uuid>", "text": "..." }`. It returns HTTP 202
with `{ "schemaVersion": 1, "messageId": "..." }` after placing the message in at least one
current stream's in-memory queue. This does not prove receipt or display. With no current subscriber
or capacity it returns 503; a disconnected stream may take up to a heartbeat to be noticed, so even
a 202 can be lost. A stream that cannot keep up is disconnected. Show unavailable on failure, and
unknown on a lost response; never claim delivery to all peers.

For 60 seconds in the same process, the same identity and message ID with the same original decoded
text returns the original 202 without sending again; different text returns 409. IDs are scoped by
identity, so two different identities may use the same ID; consumers must not globally deduplicate
by message ID alone. After expiry or restart an old retry may send again. The bounded in-memory
receipt contains a SHA-256 digest of original text (which may be guessable), identity, ID and expiry,
but no message body; it is not durable and is cleared on restart. Rate limiting allows five accepted
messages per identity per ten seconds, returning 429. The local adapter's identities are spoofable.
The service bounds body size, subscribers, queues, receipt and rate state. Content is never logged or
persisted by this service.

Original decoded text is limited to 1,024 UTF-8 bytes; the entire JSON body is independently limited
to 4,096 bytes. Reject Unicode `Cc` controls before trimming, even at the edges, and reject malformed
input. Text is NFC-normalized using pinned ICU4J 74.2 (Unicode 15.1), trimmed using Unicode
`White_Space`, and limited to 1–40 extended grapheme clusters. Format characters needed for emoji are
allowed. Browser counts are advisory; server validation is authoritative. The S3 draft wire fixtures
and Unicode 15.1 grapheme-break data are exercised in tests; the actual shared contract and game
issuer must still be integrated before an interoperability claim.
