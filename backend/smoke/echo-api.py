#!/usr/bin/env python3
"""Temporary REST backend for the GateForge → Nacos → Higress MCP smoke test."""

import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

BASE_URL = os.getenv("GATEFORGE_SMOKE_BASE_URL", "http://127.0.0.1:19090").rstrip("/")

OPENAPI = {
    "openapi": "3.0.3",
    "info": {"title": "GateForge Smoke Echo API", "version": "1.0.0"},
    "servers": [{"url": BASE_URL}],
    "paths": {
        "/smoke": {
            "get": {
                "operationId": "getSmokeStatus",
                "summary": "返回 GateForge 冒烟服务状态，用于端到端 MCP 注册验证",
                "responses": {
                    "200": {
                        "description": "冒烟服务正常",
                        "content": {
                            "application/json": {
                                "schema": {
                                    "type": "object",
                                    "required": ["ok", "service", "source"],
                                    "properties": {
                                        "ok": {"type": "boolean"},
                                        "service": {"type": "string"},
                                        "source": {"type": "string"},
                                    },
                                    "additionalProperties": False,
                                },
                                "example": {
                                    "ok": True,
                                    "service": "gateforge-smoke",
                                    "source": "real-rest-upstream",
                                },
                            }
                        },
                    }
                },
            }
        },
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
                                "example": {"message": "hello", "source": "gateforge-smoke-rest"},
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
        if parsed.path == "/smoke":
            self.respond(
                200,
                {
                    "ok": True,
                    "service": "gateforge-smoke",
                    "source": "real-rest-upstream",
                },
            )
            return
        if parsed.path == "/echo":
            message = parse_qs(parsed.query).get("message", [""])[0]
            if not message:
                self.respond(400, {"error": "message is required"})
                return
            self.respond(200, {"message": message, "source": "gateforge-smoke-rest"})
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
