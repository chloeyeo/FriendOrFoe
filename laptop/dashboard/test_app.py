"""python -m pytest laptop/dashboard  (needs fastapi + httpx; or run this file directly)"""

from fastapi.testclient import TestClient

from app import app, store

client = TestClient(app)


def event(track, cls, status=None, reason=None, t=1):
    return {"t": t, "class": cls, "box": [0, 0, 50, 50], "iffStatus": status, "droneId": None, "reason": reason,
            "text": f"{cls} {status or 'detected'}", "trackId": track}


def setup_function():
    store.reset()


def test_counts_match_the_phone():
    client.post("/api/events", json=[event(1, "person"), event(1, "person"), event(2, "drone")])
    client.post("/api/events", json=[event(2, "drone", "FRIEND"), event(3, "drone", "FOE", "replayed nonce")])
    client.post("/api/events", json=event(3, "drone", "UNKNOWN", "no response"))
    client.post("/api/events", json={"t": 7, "class": None, "box": None, "iffStatus": None, "text": "challenge sent"})
    # The skeleton's schema still counts: no trackId, "verdict" instead of "iffStatus".
    client.post("/api/events", json={"t": 8, "class": "drone", "verdict": "FOE"})
    s = client.get("/api/state").json()
    assert s["counts"] == {"drones": 3, "people": 1, "friend": 1, "foe": 2, "unknown": 1, "events": 8}
    assert [e["id"] for e in s["events"]] == [8, 7, 6, 5, 4, 3, 2, 1]


def test_bad_event_body_is_rejected():
    assert client.post("/api/events", json=[{"t": 1}, 3]).status_code == 422
    assert client.get("/api/state").json()["received"] == 0


def test_frame_thumbnail_and_reset():
    tracks = [{"id": 2, "class": "drone", "box": [576, 238, 806, 432], "tag": "drone · ?", "tone": "amber", "detail": None}]
    assert client.post("/api/frame", json={"t": 1, "width": 1920, "height": 1080, "tracks": tracks}).status_code == 200
    assert client.post("/api/thumbnail", content=b"not a jpeg").status_code == 422
    jpeg = b"\xff\xd8\xff\xe0fake"
    assert client.post("/api/thumbnail", content=jpeg, headers={"Content-Type": "image/jpeg"}).status_code == 200
    s = client.get("/api/state").json()
    assert s["frame"]["tracks"] == tracks and s["frame"]["width"] == 1920
    assert client.get(s["thumbnail"]["url"]).content == jpeg

    client.post("/api/reset")
    s = client.get("/api/state").json()
    assert s["frame"] is None and s["thumbnail"] is None and s["events"] == []
    assert client.get("/api/thumbnail.jpg").status_code == 404


def test_pages():
    r = client.get("/dashboard", follow_redirects=False)
    assert r.status_code == 307 and r.headers["location"].endswith("/dashboard")
    assert "FriendOrFoe" in client.get("/basic").text
    assert client.get("/health").json() == {"ok": True}


if __name__ == "__main__":
    for name, fn in list(globals().items()):
        if name.startswith("test_"):
            setup_function()
            fn()
            print("PASS", name)
