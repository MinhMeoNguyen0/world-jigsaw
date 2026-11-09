# Two-player local demo

[Watch or download the silent WebM recording](two-player-local.webm) (VP9, 2560 × 720,
23.3 seconds, about 5.5 MB). Open the file in a VP9-capable browser or player such as VLC; if a
repository viewer offers only a download, download it and open it locally. The text in the video
identifies each step. There is no audio. The original recordings are available separately:
[Player A](player-a.webm) (about 2.3 MB) and [Player B](player-b.webm) (about 2.3 MB). They are
silent WebM recordings and start at different times; use the composite to follow the interaction
side by side.

The recording shows two independent browser contexts in one Chromium process on one computer, with
separate sessions and disposable local game and chat databases. It is real interaction with the local
services, driven by an automated browser script; the labels and step captions are overlays. It is not
a recording of separate physical devices. Player A moves a pointer, sends chat, drags a piece while
Player B sees its preview, and confirms it; B reloads and retains the saved position. The script
checked the remote cursor, chat, preview, board version increment and B's post-reload position.

The two original context recordings were composed side by side, delaying B by 1.236 seconds using
their recording start timestamps. This is approximate alignment, not a latency measurement. The
recording was made against merged revision `273418ac2aed52eb19db172a0a6145333e6bb211`; subsequent
changes can alter the UI or behavior. It contains generated fixture artwork and synthetic chat text.
The artwork's [notice](../../apps/web/public/hoi-an-generated.NOTICE.md) calls for a rights review
before public distribution.

## Repeat the exercise and record it

1. Follow the [local setup](../../README.md#local-setup), including separate game and chat services,
   and import the Hoi An fixture for the current UTC day into a **fresh disposable** game database.
   Use a fresh separate chat database too. Choose ports that do not conflict with an existing preview;
   configure the web proxy and game/chat service ports consistently. The checked-in Vite config targets
   game port 8080 and chat port 8081; if those are in use, use a local config copy with alternate
   targets, set `SERVER_PORT` for the game, and set both `CHAT_PORT` and `GAME_PORT` for chat. Preserve
   the browser Host and Origin and WebSocket forwarding. Never use a saved board, existing preview
   database or backup for this exercise.
2. Open the same local web origin in two isolated browser profiles or contexts on one computer, so
   each has its own session cookie. Arrange both windows side by side and start a local screen recorder
   such as OBS. Record only the two application windows, with no browser credentials or personal data.
3. Move A's pointer over the board and observe it on B. Open chat in both, send a synthetic message
   from A, and observe it on B. Drag an available piece in A and observe B's temporary preview; release
   and confirm. Reload B and verify the confirmed position remains. Stop and save the recording.
4. Export a compressed WebM with VP9, play the entire file in a compatible player, and inspect it for
   private information. Keep the original capture until the export has been checked. Remove only the
   disposable services and data you created for the exercise, after stopping your own processes.

The supplied recording used two individually captured contexts and an approximately synchronized
side-by-side composite, rather than a desktop capture. Its captions identify steps, but are not a
separate timed caption track. It demonstrates local functionality, not production readiness or
network performance.
