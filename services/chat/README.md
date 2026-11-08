# Local chat service

This independently runnable Java 21 service relays short messages in one live room. It binds
only to `127.0.0.1` and does not contact the game, its database, a gateway, or any infrastructure
service. It does not currently require a database: no durable chat state or retention policy has
been agreed. Any future chat-owned database and credentials must be separate from the game's;
never add message bodies or a transcript to this scaffold.

From `services/chat`, run `./gradlew run`. Set `CHAT_PORT` to change the default port 8081.
Run `./gradlew build` for formatting and tests. No Docker, shared secret, game process or external
service is required. `GET /actuator/health` reports process liveness and
`GET /api/v1/chat/config` reports the local validation version and limit.

This is a **development-only identity adapter**, not authentication. Supply a lowercase canonical
UUID in `X-Local-Subject` on requests. It is caller-selected and can be impersonated; the first
8 characters form a public alias. Use a disposable random UUID, not a secret or real identifier.
Replace the adapter with locally verified, short-lived chat-audience assertions from the identity
owner before integration. Do not expose this service publicly: identity, origin policy, resource
controls, abuse prevention, key management, transport security and moderation need security review.

`GET /api/v1/chat/stream` returns a server-sent event stream (SSE) and requires the header above;
use `fetch` streaming rather than browser `EventSource`, which cannot set it. A `message` data
record is JSON with `schemaVersion`, `type`, `messageId`, `alias`, `text` and `sentAt`. There is
no initial history, replay cursor or offline delivery. Clients must render `text` and `alias` as
text, never as HTML. They should discard previously displayed messages on reconnect if they need a
strictly live display. SSE is the scaffold's proposed transport and must be reconciled with the
shared contract before frontend integration.

`POST /api/v1/chat/messages` accepts UTF-8 JSON `{ "commandId": "<uuid>", "text": "..." }`
with the same identity header. It returns 202 with a `messageId` after placing the message in at
least one current stream's in-memory queue. This does not prove receipt or display. With no current
subscriber or no capacity it returns 503; a disconnected stream may take up to a heartbeat to be
noticed, so even a 202 can be lost. A stream that cannot keep up is disconnected. Clients should
show unavailable on failure, and unknown on a lost response; never claim delivery to all peers.

For 60 seconds in the same process, the same identity and command ID with the same normalized text
returns the original ID without sending again; a different text returns 409. After expiry or restart
there is no deduplication evidence, so an old retry may send again. The bounded in-memory receipt
contains a SHA-256 digest of the normalized text (which may be guessable), identity, ID and expiry,
but no message body; it is not durable and is cleared on restart. Rate limiting allows five accepted
messages per identity per ten seconds, returning 429; it is only a local safeguard because identities
are spoofable. The service bounds body size, subscribers, queues, receipt and rate state. Content is
never logged or persisted by this service.

Text is NFC-normalized using pinned ICU4J 74.2 (Unicode 15.1), trimmed using Unicode `White_Space`,
and limited to 40 extended grapheme clusters. Empty text, `Cc` controls (even at the edges), malformed
surrogates and payloads over 4096 bytes are rejected. Format characters needed for emoji are allowed.
The browser count is advisory; server validation is authoritative. Coordinates and fixtures with the
shared-contract owner are still required before these local interfaces can be considered integrated.
