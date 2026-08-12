from __future__ import annotations

import json
import sqlite3
from pathlib import Path
from threading import RLock
from typing import Any, Iterable

from gateforge.errors import NotFoundError
from gateforge.util import canonical_json


SCHEMA = """
pragma foreign_keys = on;

create table if not exists api_source (
  id text primary key,
  name text not null,
  slug text not null unique,
  spec_url text,
  base_url text not null,
  environment text not null,
  owner text not null,
  spec_hash text not null,
  spec_text text not null,
  imported_at text not null
);

create table if not exists tool (
  id text primary key,
  source_id text not null references api_source(id) on delete cascade,
  source_spec_hash text not null,
  accepted integer not null,
  rejection_reasons text not null,
  method text not null,
  path text not null,
  operation_id text not null,
  standard text not null,
  backend_mapping text not null,
  endpoint text not null,
  governance text not null,
  cluster_key text not null,
  fingerprint text not null,
  unique(source_id, method, path)
);

create index if not exists idx_tool_cluster on tool(cluster_key);
create index if not exists idx_tool_fingerprint on tool(fingerprint);

create table if not exists pack (
  id text primary key,
  slug text not null,
  name text not null,
  status text not null,
  artifact_hash text not null,
  artifact text not null,
  created_at text not null
);

create table if not exists nacos_registration (
  pack_id text primary key references pack(id) on delete cascade,
  nacos_server_id text not null,
  nacos_version text not null,
  status text not null,
  mcp_name text not null,
  registered_at text not null,
  raw text not null
);
"""


class Store:
    def __init__(self, database_path: Path) -> None:
        database_path.parent.mkdir(parents=True, exist_ok=True)
        self._connection = sqlite3.connect(database_path, check_same_thread=False)
        self._connection.row_factory = sqlite3.Row
        self._lock = RLock()
        with self._connection:
            self._connection.executescript(SCHEMA)
            columns = {
                row["name"] for row in self._connection.execute("pragma table_info(tool)").fetchall()
            }
            if "source_spec_hash" not in columns:
                self._connection.execute(
                    "alter table tool add column source_spec_hash text not null default ''"
                )

    def save_source(self, source: dict[str, Any], tools: Iterable[dict[str, Any]]) -> None:
        with self._lock, self._connection:
            self._connection.execute(
                """insert into api_source(id,name,slug,spec_url,base_url,environment,owner,
                                            spec_hash,spec_text,imported_at)
                   values(:id,:name,:slug,:spec_url,:base_url,:environment,:owner,
                          :spec_hash,:spec_text,:imported_at)""",
                source,
            )
            for tool in tools:
                encoded = dict(tool)
                for field in (
                    "rejection_reasons", "standard", "backend_mapping", "endpoint", "governance"
                ):
                    encoded[field] = canonical_json(encoded[field])
                self._connection.execute(
                    """insert into tool(id,source_id,source_spec_hash,accepted,rejection_reasons,method,path,
                                        operation_id,standard,backend_mapping,endpoint,governance,
                                        cluster_key,fingerprint)
                       values(:id,:source_id,:source_spec_hash,:accepted,:rejection_reasons,:method,:path,
                              :operation_id,:standard,:backend_mapping,:endpoint,:governance,
                              :cluster_key,:fingerprint)""",
                    encoded,
                )

    def sources(self) -> list[dict[str, Any]]:
        rows = self._connection.execute(
            """select s.*,
                      count(t.id) operation_count,
                      coalesce(sum(case when t.accepted=1 then 1 else 0 end),0) accepted_count,
                      coalesce(sum(case when t.accepted=0 then 1 else 0 end),0) rejected_count
               from api_source s left join tool t on t.source_id=s.id
               group by s.id order by s.imported_at desc"""
        ).fetchall()
        return [dict(row) for row in rows]

    def source(self, source_id: str) -> dict[str, Any]:
        row = self._connection.execute("select * from api_source where id=?", (source_id,)).fetchone()
        if row is None:
            raise NotFoundError("API Source")
        return dict(row)

    def tools(self, *, accepted: bool | None = None) -> list[dict[str, Any]]:
        sql = """select t.*, s.name source_name from tool t
                 join api_source s on s.id=t.source_id"""
        params: tuple[Any, ...] = ()
        if accepted is not None:
            sql += " where t.accepted=?"
            params = (1 if accepted else 0,)
        sql += " order by s.name, t.path, t.method"
        rows = self._connection.execute(sql, params).fetchall()
        return [self._decode_tool(dict(row)) for row in rows]

    def tools_by_ids(self, ids: Iterable[str]) -> list[dict[str, Any]]:
        ids = list(dict.fromkeys(ids))
        if not ids:
            return []
        placeholders = ",".join("?" for _ in ids)
        rows = self._connection.execute(
            f"""select t.*, s.name source_name from tool t join api_source s on s.id=t.source_id
                where t.id in ({placeholders}) order by t.cluster_key, t.path, t.method""",
            ids,
        ).fetchall()
        result = [self._decode_tool(dict(row)) for row in rows]
        if len(result) != len(ids):
            raise NotFoundError("Tool")
        return result

    def save_pack(self, artifact: dict[str, Any]) -> None:
        with self._lock, self._connection:
            self._connection.execute(
                """insert into pack(id,slug,name,status,artifact_hash,artifact,created_at)
                   values(?,?,?,?,?,?,?)""",
                (
                    artifact["id"], artifact["slug"], artifact["name"], artifact["status"],
                    artifact["artifact_hash"], canonical_json(artifact), artifact["created_at"],
                ),
            )

    def packs(self) -> list[dict[str, Any]]:
        rows = self._connection.execute("select artifact from pack order by created_at desc").fetchall()
        return [json.loads(row["artifact"]) for row in rows]

    def pack(self, pack_id: str) -> dict[str, Any]:
        row = self._connection.execute("select artifact from pack where id=?", (pack_id,)).fetchone()
        if row is None:
            raise NotFoundError("MCP Pack")
        return json.loads(row["artifact"])

    def save_registration(self, registration: dict[str, Any]) -> None:
        encoded = dict(registration)
        encoded["raw"] = canonical_json(encoded["raw"])
        with self._lock, self._connection:
            self._connection.execute(
                """insert into nacos_registration(pack_id,nacos_server_id,nacos_version,status,
                                                   mcp_name,registered_at,raw)
                   values(:pack_id,:nacos_server_id,:nacos_version,:status,:mcp_name,:registered_at,:raw)
                   on conflict(pack_id) do update set
                     nacos_server_id=excluded.nacos_server_id,
                     nacos_version=excluded.nacos_version,
                     status=excluded.status,
                     mcp_name=excluded.mcp_name,
                     registered_at=excluded.registered_at,
                     raw=excluded.raw""",
                encoded,
            )

    def registrations(self) -> list[dict[str, Any]]:
        rows = self._connection.execute(
            "select * from nacos_registration order by registered_at desc"
        ).fetchall()
        result = []
        for row in rows:
            item = dict(row)
            item["raw"] = json.loads(item["raw"])
            result.append(item)
        return result

    @staticmethod
    def _decode_tool(row: dict[str, Any]) -> dict[str, Any]:
        row["accepted"] = bool(row["accepted"])
        for field in ("rejection_reasons", "standard", "backend_mapping", "endpoint", "governance"):
            row[field] = json.loads(row[field])
        return row
