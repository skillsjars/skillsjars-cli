#!/usr/bin/env python3
"""Serves a directory as a GitHub release, for testing install.sh / install.ps1 before anything is published.

Usage: fake_release.py <dir> <version> <port>

  GET /releases/latest              -> 302 to /releases/tag/v<version>
  GET /releases/download/v<version>/<file> -> <dir>/<file>
"""

import http.server
import os
import sys

directory, version, port = sys.argv[1], sys.argv[2], int(sys.argv[3])
prefix = f"/releases/download/v{version}/"


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/releases/latest":
            self.send_response(302)
            self.send_header("Location", f"http://127.0.0.1:{port}/releases/tag/v{version}")
            self.end_headers()
            return
        name = self.path[len(prefix):] if self.path.startswith(prefix) else ""
        path = os.path.join(directory, name)
        if not name or "/" in name or not os.path.isfile(path):
            self.send_response(404)
            self.end_headers()
            return
        with open(path, "rb") as f:
            body = f.read()
        self.send_response(200)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


http.server.ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()
