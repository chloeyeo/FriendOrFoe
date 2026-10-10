#!/usr/bin/env python3
"""Mac/Windows용 reader GUI: python reader_gui.py [--port 8765] [--no-browser]."""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import threading
import webbrowser

from reader_backend import BenchController
from serial_connection import list_ports

WEB = Path(__file__).resolve().parent / "reader_web"


def make_handler(controller):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_args):
            pass

        def reply(self, value, status=200):
            data = json.dumps(value, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(data)

        def local_request(self):
            # localhost에만 바인딩합니다. 외부 웹페이지의 브라우저 요청도 거절합니다.
            expected = f"127.0.0.1:{self.server.server_port}"
            return (self.headers.get("Host") == expected and
                    self.headers.get("Origin", "http://" + expected) == "http://" + expected)

        def do_GET(self):
            if not self.local_request():
                return self.reply({"error": "Local origin required"}, 403)
            if self.path == "/api/state":
                return self.reply(controller.snapshot())
            if self.path == "/api/ports":
                try:
                    return self.reply({"ports": list_ports()})
                except Exception as error:
                    return self.reply({"error": str(error)}, 500)
            routes = {"/": ("index.html", "text/html"), "/app.js": ("app.js", "text/javascript"),
                      "/style.css": ("style.css", "text/css")}
            if self.path not in routes:
                return self.reply({"error": "Not found"}, 404)
            name, content_type = routes[self.path]
            data = (WEB / name).read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", content_type + "; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Content-Security-Policy", "default-src 'self'; style-src 'self'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'")
            self.end_headers()
            self.wfile.write(data)

        def do_POST(self):
            if not self.local_request():
                return self.reply({"error": "Local origin required"}, 403)
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if length < 0 or length > 1024:
                    raise ValueError("Request too large")
                body = json.loads(self.rfile.read(length) or b"{}")
                if not isinstance(body, dict):
                    raise ValueError("JSON object required")
                if self.path == "/api/connect":
                    if isinstance(controller, BenchController):
                        controller.connect(body.get("port"), body.get("role", "reader"))
                    else:
                        controller.connect(body.get("port"))
                elif self.path == "/api/disconnect":
                    if isinstance(controller, BenchController):
                        controller.disconnect(body.get("role", "reader"))
                    else:
                        controller.disconnect()
                elif self.path == "/api/status":
                    if isinstance(controller, BenchController):
                        controller.request_status(body.get("role", "reader"))
                    else:
                        controller.request_status()
                elif self.path == "/api/query":
                    return self.reply({"request_id": controller.query()}, 202)
                else:
                    return self.reply({"error": "Not found"}, 404)
                self.reply({"ok": True}, 202)
            except (ValueError, TypeError) as error:
                self.reply({"error": str(error)}, 409)
    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8765, help="로컬 HTTP 포트 (시리얼 포트는 GUI에서 선택)")
    parser.add_argument("--no-browser", action="store_true")
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error("--port must be between 1 and 65535")
    try:
        import serial
    except ImportError:
        parser.exit(1, "pySerial을 설치해 주세요: python -m pip install -r firmware/tools/requirements.txt\n")
    controller = BenchController()
    try:
        server = ThreadingHTTPServer(("127.0.0.1", args.port), make_handler(controller))
    except OSError as error:
        controller.close()
        parser.exit(1, f"서버 시작 실패: {error}\n")
    server.daemon_threads = True
    url = f"http://127.0.0.1:{server.server_port}"
    print(f"Reader GUI: {url}\n종료: Ctrl+C", flush=True)
    if not args.no_browser:
        threading.Timer(0.3, webbrowser.open, args=(url,)).start()
    try:
        server.serve_forever(poll_interval=0.25)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        controller.close()


if __name__ == "__main__":
    main()
