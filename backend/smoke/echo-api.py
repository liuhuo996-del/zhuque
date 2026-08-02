#!/usr/bin/env python3
"""Temporary REST backend for the Zhuque → Nacos → Higress MCP smoke test."""

import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

OPENAPI = {
    "openapi": "3.0.3",
    "info": {"title": "Zhuque Smoke Echo API", "version": "1.0.0"},
    "servers": [{"url": "http://host.docker.internal:19090"}],
    "paths": {
        "/echo": {
            "get": {
                "operationId": "echoMessage",
                "summary": "回显一段消息，用于端到端 MCP 注册验证",
                "parameters": [
                    {
                        "name": "message",
                        "in": "query",
                        "required": True,
                        "description": "需要回显的消息",
                        "schema": {"type": "string", "minLength": 1},
                    }
                ],
                "responses": {
                    "200": {
                        "description": "回显成功",
                        "content": {
                            "application/json": {
                                "schema": {
                                    "type": "object",
                                    "required": ["message", "source"],
                                    "properties": {
                                        "message": {"type": "string"},
                                        "source": {"type": "string"},
                                    },
                                },
                                "example": {"message": "hello", "source": "zhuque-smoke-rest"},
                            }
                        },
                    }
                },
            }
        }
    },
}


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path == "/openapi.json":
            self.respond(200, OPENAPI)
            return
        if parsed.path == "/echo":
            message = parse_qs(parsed.query).get("message", [""])[0]
            if not message:
                self.respond(400, {"error": "message is required"})
                return
            self.respond(200, {"message": message, "source": "zhuque-smoke-rest"})
            return
        if parsed.path == "/health":
            self.respond(200, {"ok": True})
            return
        self.respond(404, {"error": "not found"})

    def respond(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, message, *args):
        print("echo-api:", message % args, flush=True)


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", 19090), Handler).serve_forever()
