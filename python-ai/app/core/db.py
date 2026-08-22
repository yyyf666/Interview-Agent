"""PostgreSQL + pgvector 数据访问（psycopg3 连接池）"""
from __future__ import annotations

from contextlib import contextmanager
from typing import Any, Iterable

from psycopg.rows import dict_row
from psycopg_pool import ConnectionPool

from app.core.config import get_settings

_pool: ConnectionPool | None = None


def _configure_connection(conn) -> None:
    """为连接注册 pgvector 类型适配器（list[float] <-> vector）"""
    from pgvector.psycopg import register_vector

    register_vector(conn)


def get_pool() -> ConnectionPool:
    global _pool
    if _pool is None:
        s = get_settings()
        conninfo = (
            f"host={s.database_host} port={s.database_port} dbname={s.database_name} "
            f"user={s.database_user} password={s.database_password}"
        )
        _pool = ConnectionPool(
            conninfo,
            min_size=1,
            max_size=8,
            kwargs={"row_factory": dict_row, "autocommit": True},
            configure=_configure_connection,
            open=True,
        )
    return _pool


@contextmanager
def get_conn():
    with get_pool().connection() as conn:
        yield conn


def query(sql: str, params: Iterable[Any] = ()) -> list[dict]:
    with get_conn() as conn:
        with conn.cursor() as cur:
            cur.execute(sql, params)
            return cur.fetchall() or []


def query_one(sql: str, params: Iterable[Any] = ()) -> dict | None:
    rows = query(sql, params)
    return rows[0] if rows else None


def execute(sql: str, params: Iterable[Any] = ()) -> None:
    with get_conn() as conn:
        with conn.cursor() as cur:
            cur.execute(sql, params)
