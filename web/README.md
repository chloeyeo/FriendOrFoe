# FriendOrFoe dashboard page

The judges' second screen: counts, the live list of detections and verdicts, and the tracks phone A is
drawing (same ring colours and tag text as the phone). Svelte 5 + SvelteKit, served by the Vite dev
server on the demo laptop; the data comes from the FastAPI side in `laptop/dashboard/app.py`.

```bash
npm install        # once, while online
npm run dev        # http://localhost:5180/dashboard  (proxies /api to 127.0.0.1:8081)
npm run check      # svelte-check
```

Start the API first (`python laptop/dashboard/app.py`); without a phone, `python laptop/dashboard/feed_demo.py`
plays the scripted demo run into it. Full setup, ports and the scrcpy mirror: [`laptop/README.md`](../laptop/README.md).

| File | Role |
|---|---|
| `src/lib/Dashboard.svelte` | page layout: header with API status, counts, frame, event list |
| `src/lib/poller.svelte.ts` | polls `GET /api/state` once a second while the page is open (`createSubscriber`) |
| `src/lib/components/FrameView.svelte` | rings and tags over the latest thumbnail (shown only while fresh) |
| `src/lib/components/EventList.svelte` | newest-first rows: time, verdict or class, reason, RSSI · round trip, age |
| `src/lib/components/Counts.svelte` | drones / people / friend / foe / unknown |
| `src/app.css` | palette shared with the phone's `ui/theme/Color.kt`; system fonts, no CDN |
