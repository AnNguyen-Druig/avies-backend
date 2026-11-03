# Feature 3: progress events

`GET /student-exams/{id}/oral-examination/events` uses the same bearer JWT as REST. Only the owning Student can subscribe to an existing oral attempt. Do not put tokens in query strings.

The `ready` and `progress` SSE events contain only `{"revision": number}`. After `ready`, reconnect, or a newer revision, the frontend reads the existing authenticated progress endpoint. Events are sent after transaction commit, on a separate bounded executor. They never change exam state, timers, lease, answers or AI decisions.

When SSE is healthy, REST reconciliation runs every 15 seconds. If SSE fails, the previous polling intervals resume (800 ms during processing, otherwise 3 seconds). The frontend also asks the server at the answer/slot deadline. Disconnection cannot mark an attempt complete or grant more time.

Connections expire after at most 60 seconds (earlier at JWT expiry), reconnect with backoff, and close when the tab is hidden, the route is left, or the attempt ends. Heartbeats run every 15 seconds without querying the database. A reconnect always gets the current state; there is no event-history replay.

## Compare with polling

SSE is enabled by default. To temporarily restore polling, add `VITE_ORAL_SSE_ENABLED=false` to the frontend's local `.env`, then restart Vite (or rebuild a deployed frontend). Remove it or set `true` to enable SSE again. No database migration or API key is needed.

In browser Network, an authenticated `events` request should have `text/event-stream`, and receive `ready` / `progress`. Measure from clicking “Trả lời xong” to hearing the first audio of the next question. Compare several main-question and follow-up transitions, with the same provider settings, distinguishing cached audio from fresh synthesis. SSE removes polling delay; STT, decision and TTS processing times still apply.

The event registry is local to one backend instance. Multiple backend instances require shared pub/sub for immediate cross-instance notifications; the periodic REST reconciliation remains a fallback. Reverse proxies must permit streaming without buffering (`X-Accel-Buffering: no`) and allow idle connections longer than the heartbeat interval.
