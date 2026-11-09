# Private-local live chat adapter

`LiveChat` is the web client's transport for the local chat relay. It uses the existing
HTTP-only game session cookie for both the stream and sends. Start it after the game
session is ready. Supply `window.fetch.bind(window)` and `crypto.randomUUID` through
the constructor arguments.

Subscribe before calling `connect()`. Render message text and display names as text,
never HTML. State contains only the latest 100 live events, and it is cleared on stream
close, disconnect or reconnect. The consumer decides when to reconnect; disconnect
when the view is no longer in use. There is no transcript or replay. The browser's
character count is advisory: the server applies the authoritative Unicode 15.1
40-grapheme limit and normalization. The adapter rejects serialized request bodies
larger than 64 KiB.

`send(text)` resolves to `accepted` when the relay acknowledges queueing to at least one
stream, `invalid` for a rejected request, `unavailable` when the relay is unavailable,
or `unknown` when the response is lost or malformed. Accepted does not mean every player
received the event. An unknown result may have been delivered; do not automatically
retry it. Messages appear in state only when received from the stream, not from the
send response.

The Vite development server proxies `/api/v1/chat` to the local relay and other
`/api/v1` requests to the game service, preserving the browser Host and Origin. It binds
only to loopback and is not a production gateway. See the relay README for local setup,
identity limitations, and the prohibition on public exposure.
