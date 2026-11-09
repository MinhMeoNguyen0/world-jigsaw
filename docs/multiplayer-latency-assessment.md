# Multiplayer latency assessment

This is a loopback laboratory measurement of the private-local prototype, not a worldwide latency
claim. The earlier [preview assessment](multiplayer-preview-assessment.md) identified a reconnect
on the first Begin and removed a reconnect on a known collision; it did not measure frame times.
The local ghost masks server delay during a drag. Peers see only server-accepted previews, so they
can lag even when the mover's ghost follows the pointer. Confirm still saves the exact acknowledged
actions; a preview is not a commit.

## Finding and change

On the 60-piece fixture, a Java Flight Recorder profile of a third drag found
`hasForbiddenOverlap` in 45 of 60 sampled execution stacks, including 20 at the first call and
25 at the second call in preview processing. This is a small statistical sample, not a percentage
of wall time. A trace of the preceding drag showed many rejected intermediate placements and then
13 accepted submissions; accepted submission-to-broadcast timing was substantially longer than
rejected submission-to-rejection timing. This suggests validation work, rather than local ghost
rendering or loopback transport alone, is a useful target.

An unjoined move previously checked the same candidate and same current snapshot twice, with no
attachment between the checks. The coordinator is synchronized during this operation. It now
keeps the first validation and skips the second only when there were no attachments. A join can
change transforms and membership, so the final validation remains. Reservation, quota, revision,
rejection, acknowledgement, and Confirm behavior are unchanged. The new regression exercises a
valid unjoined preview, a forbidden overlapping update, preservation of the accepted preview,
and retry. Deliberately disabling the first check made it fail. Existing geometry tests cover
unsafe snapped joins and joined undo; disabling only the final check did not fail the snapped-join
test because the join search also rejects that case. That mutation therefore does **not** prove
the final check unnecessary, and it remains in place.

## Measurements

Two separate headless Chromium processes, each with its own browser session, used the production
Vite build and a real Spring Boot service and PostgreSQL 18 cluster on loopback. The cluster and
service used separate ports and a disposable database, seeded with `hoi-an-balanced-60.json` for
the UTC day. No existing preview or database was used. Java used a 320 MiB heap; the same machine,
fixture, browser binary, page size and unthrottled network were used before and after. Before each
measured run the first fixture group was dragged to `(500000, 200000)` and confirmed, yielding the
same saved transform. The measured drag moved the second fixture group toward `(800000, 200000)`
in 40 synthetic pointer moves spaced by a 50 ms timer, followed by release and Confirm. The same
end transform was confirmed in both runs. Browser instrumentation and performance tracing were
active in both; the timer and rendering compete with other host work.

| Observation                                                                                  |           Before |          After |               Samples per run |
| -------------------------------------------------------------------------------------------- | ---------------: | -------------: | ----------------------------: |
| Input to matching local ghost at a `requestAnimationFrame` callback, median / p95            |   13.4 / 23.8 ms | 17.2 / 33.5 ms |          40 matched positions |
| Input to matching peer preview DOM mutation, median / p95                                    | 110.5 / 118.4 ms | 41.7 / 56.7 ms | 12 matched accepted positions |
| Submit socket send to matching-sized accepted preview broadcast in mover trace, median / p95 |   51.2 / 59.0 ms | 31.1 / 46.6 ms |             13 accepted sends |
| Animation callback interval, median / p95                                                    |   16.7 / 30.0 ms | 16.7 / 30.3 ms |           330 / 316 intervals |
| Intervals over 33.4 ms                                                                       |                7 |             10 |                same intervals |
| Confirm click to peer returning to `ready` after resubscription                              |         152.8 ms |       145.4 ms |                        1 each |

The frame interval sample includes the drag and the subsequent settling and confirmation waits,
not just active motion. A callback observing a changed DOM transform is a proxy for the next local
frame, not a hardware presentation timestamp. The peer metric is a DOM mutation, not a remote
screen presentation. The peer `ready` measurement includes the Confirm-triggered reconnect and
snapshot handoff; it is not a separate network-only commit latency. In an initial production
experiment the peer also observed the confirmed transform, but both sessions shared one browser
and its background tab throttled animation frames. That experiment is excluded from the table.
The 13 trace timings identify preview broadcasts by message size in this controlled run; they are
not protocol instrumentation. Intermediate collisions and the serialized receipt path mean the
12 peer samples are selected accepted positions, not every input. One drag per version and one
Confirm per version cannot establish a general distribution. The local frame result does not show
an improvement and the Confirm sample is too small to claim one.

An earlier exploratory development-build run used two browser processes, 45 synthetic pointer
moves, and an invalid release. It yielded 43 matching local callbacks (median 27 ms, p95 70 ms),
eight matching peer mutations (median 212 ms from the corresponding local callback), and 32 of
196 callback intervals above 33.4 ms. It also showed a first-Begin reconnect. Development mode,
invalid placements and rising host swap limit interpretation; it is not comparable to the table.
The run was stopped when swap rose. On the renewed run a two-second memory monitor recorded a
minimum 3,139,556 KiB available and a maximum 104,900 KiB swap, unchanged from that run's start.
The isolated processes were stopped and the heavy-work slot released.

To repeat the table, use the README's isolated local setup with a fresh disposable database and
current-day fixture, bind the game and production frontend to unused loopback ports, and configure
the frontend proxy to that game port. Open two independent browser profiles and wait for `ready`.
On the mover, use the fixture's group and piece reference point with SVG `getScreenCTM()` to map
the two fixture-space targets to screen coordinates. Record `performance.timeOrigin +
performance.now()` for each synthetic pointer event and the ghost transform at animation
callbacks. Observe the peer's preview transform and connection state with a `MutationObserver`;
match transforms to the inputs and calculate the percentiles from matched samples. Record the
Confirm click and peer's next `ready` state. Use Chrome's performance trace for socket send and
receive timing, and a bounded JFR profile for server call stacks. Repeat across multiple runs,
real pointer input, devices, and network conditions before drawing broader conclusions.

## Alternatives and boundaries

Caching stable SVG paths or reducing React rendering may help local frames, but these samples do
not establish that as the dominant cost; React recommends measuring production builds and using
memoization for demonstrated expensive recalculations ([React `useMemo`](https://react.dev/reference/react/useMemo)).
`requestAnimationFrame` batching can reduce repeated work, but Chrome already aligns continuous
pointer events to frames in common cases ([Chrome input events](https://developer.chrome.com/blog/aligning-input-events/));
adding a frame of scheduling without measuring it could increase latency. Optimistic remote motion
or unreliable datagrams could show a peer an unvalidated location. WebTransport datagrams have
different reliability and ordering properties and are not a direct replacement for WebSockets
([Chrome WebTransport](https://developer.chrome.com/docs/capabilities/web-apis/webtransport)).
No network change was justified by this loopback test. Separate profiling of input processing,
rendering, server work and real networks remains necessary ([Chrome performance guidance](https://developer.chrome.com/docs/modern-web-guidance/guides/performance/identify-inp-causes)).

The focused Java geometry and draft tests and formatting check passed locally. The Docker-backed
`GameCoordinatorTest` could not initialize because Docker was unavailable; it was not treated as a
pass. The two-browser disposable-server exercise did confirm a durable move on the mover and peer
resubscription, but it does not replace that suite or CI.
