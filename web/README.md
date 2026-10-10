# FriendOrFoe dashboard page

The judges' second screen: current observer tracks and recent identification results. A neutral,
square-edged layout gives the camera view priority; colour identifies FRIEND, FOE, UNKNOWN and
unchallenged objects. Svelte 5 + SvelteKit, served by the Vite dev server on the demo laptop; the data
comes from the FastAPI side in `laptop/dashboard/app.py`.

The observation header counts objects in the latest frame, while the results panel shows past
identifications newest first. Select a result to expand signal strength, response time and other
details. **All activity** also shows detections and challenge messages. Stale positions are dashed
and labelled; a missing camera image is explicitly marked as positions only.

```bash
npm install        # once, while online
npm run dev        # http://localhost:5180/dashboard  (proxies /api to 127.0.0.1:8081)
npm run check      # svelte-check
```

Start the API first (`python laptop/dashboard/app.py`); without a phone, `python laptop/dashboard/feed_demo.py`
plays the scripted demo run into it. Full setup, ports and the scrcpy mirror: [`laptop/README.md`](../laptop/README.md).

| File | Role |
|---|---|
| `src/lib/Dashboard.svelte` | responsive observation/results layout and feed status |
| `src/lib/poller.svelte.ts` | polls `GET /api/state` once a second while the page is open (`createSubscriber`) |
| `src/lib/components/FrameView.svelte` | square track boxes with concise tags, fresh thumbnails and stale-feed handling |
| `src/lib/components/EventList.svelte` | verdict, target, reason and age; expandable measurements and an all-activity view |
| `src/app.css` | charcoal/grey palette with semantic verdict colours; system fonts, no CDN |
