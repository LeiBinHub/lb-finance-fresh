#!/usr/bin/env python3
"""User-session Telegram listener. Phone-app login is a different session."""

from __future__ import annotations

import asyncio
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

from telethon import TelegramClient, events
from telethon.errors import ChannelPrivateError, FloodWaitError, SessionPasswordNeededError, UserAlreadyParticipantError
from telethon.tl.functions.channels import JoinChannelRequest

CHANNELS = [
    "TreeNewsFeed",
    "WalterBloomberg",
    "jin10light",
    "unusual_whales_TG1",
    "investinglivenews",
]
ALIASES = {
    "investinglivenews": "forexlive",
    "investinglive": "forexlive",
}


def read_dotenv(path: Path) -> dict[str, str]:
    out: dict[str, str] = {}
    if not path.is_file():
        return out
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        value = value.strip().strip("'").strip('"')
        out[key.strip()] = value
    return out


def env(name: str, default: str = "") -> str:
    value = os.environ.get(name, "").strip()
    if value:
        return value
    for path in (Path("/app/.env"), Path(".env"), Path("../.env")):
        found = read_dotenv(path).get(name, "").strip()
        if found:
            return found
    return default


API_ID = env("TELEGRAM_API_ID")
API_HASH = env("TELEGRAM_API_HASH")
SESSION = env("TELEGRAM_SESSION_PATH", "/app/data/telegram")
INGEST = env("TELEGRAM_INGEST_URL", "http://127.0.0.1:8092/api/tree-news/ingest")
HISTORY = int(env("TELEGRAM_HISTORY", "80") or "80")


def session_file() -> Path:
    return Path(SESSION + ".session")


def canonical(handle: str) -> str:
    name = (handle or "").replace("@", "").strip()
    return ALIASES.get(name.lower(), name)


def post_flashes(rows: list[dict]) -> None:
    if not rows:
        return
    payload = json.dumps(rows).encode("utf-8")
    last: Exception | None = None
    for attempt in range(5):
        req = urllib.request.Request(
            INGEST,
            data=payload,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=20) as response:
                response.read()
            return
        except (urllib.error.URLError, TimeoutError, ConnectionError) as exc:
            last = exc
            time.sleep(1.5 * (attempt + 1))
    raise last or RuntimeError("投递失败")


def wait_for_app() -> None:
    for _ in range(60):
        try:
            post_flashes([])
            return
        except Exception:
            time.sleep(2)
    raise SystemExit("Java 应用还没起来，Telethon 无法投递消息")


def flash(handle: str, message) -> dict | None:
    text = (message.raw_text or "").strip()
    if not text or message.id is None:
        return None
    date = message.date
    epoch = int(date.timestamp() * 1000) if date is not None else 0
    return {
        "channel": canonical(handle),
        "id": int(message.id),
        "epoch": epoch,
        "text": text,
    }


def handle_of(event) -> str:
    username = getattr(event.chat, "username", None) or ""
    match = next((name for name in CHANNELS if name.lower() == username.lower()), "")
    return match or username


async def ensure_channel(client: TelegramClient, handle: str):
    entity = await client.get_entity(handle)
    try:
        await client(JoinChannelRequest(entity))
    except UserAlreadyParticipantError:
        pass
    except FloodWaitError as exc:
        print(f"@{handle} 加入限流，等待 {exc.seconds}s", flush=True)
        await asyncio.sleep(exc.seconds + 1)
    return entity


async def backfill(client: TelegramClient, handle: str, entity) -> int:
    rows = []
    async for message in client.iter_messages(entity, limit=HISTORY):
        item = flash(handle, message)
        if item:
            rows.append(item)
    post_flashes(rows)
    return len(rows)


async def listen_once() -> None:
    client = TelegramClient(
        SESSION,
        int(API_ID),
        API_HASH,
        connection_retries=5,
        retry_delay=5,
        auto_reconnect=False,
        use_ipv6=False,
        timeout=30,
    )
    print("正在用 Telethon 用户会话连接 Telegram…", flush=True)
    try:
        await client.start()
    except SessionPasswordNeededError:
        print("这个账号开了两步验证，登录时还要输入云密码。", flush=True)
        raise

    entities = {}
    for handle in CHANNELS:
        try:
            entities[handle] = await ensure_channel(client, handle)
            n = await backfill(client, handle, entities[handle])
            print(f"已接入 @{handle}，回补 {n} 条", flush=True)
        except ChannelPrivateError:
            print(f"@{handle} 是私密频道，需要先在 Telegram App 里用邀请链接加入", flush=True)
        except FloodWaitError as exc:
            print(f"@{handle} 限流 {exc.seconds}s: {type(exc).__name__}", flush=True)
            await asyncio.sleep(exc.seconds + 1)
        except Exception as exc:
            print(f"@{handle} 接入失败: {type(exc).__name__}: {exc}", flush=True)

    @client.on(events.NewMessage(chats=list(entities.values()) or CHANNELS))
    async def on_new(event):
        item = flash(handle_of(event), event.message)
        if item:
            try:
                post_flashes([item])
            except Exception as exc:
                print(f"投递失败: {type(exc).__name__}", flush=True)

    print("账号监听已就绪，等待新帖。", flush=True)
    try:
        await client.run_until_disconnected()
    finally:
        await client.disconnect()


async def run() -> None:
    if not API_ID or not API_HASH:
        print("还没有 TELEGRAM_API_ID / TELEGRAM_API_HASH", flush=True)
        while True:
            await asyncio.sleep(60)
        return
    session = session_file()
    session.parent.mkdir(parents=True, exist_ok=True)
    if not session.exists() and not sys.stdin.isatty():
        print("还没有 Telegram 用户会话文件。", flush=True)
        print("手机 App 已登录、已关注频道，不等于这段程序已登录。", flush=True)
        print("在 lb-finance-fresh 目录执行一次：", flush=True)
        print("  docker compose run --rm -it telegram-listen", flush=True)
        print("按提示输入手机号和验证码，生成 data/telegram.session 后即可持续收帖。", flush=True)
        while True:
            await asyncio.sleep(60)
        return

    wait_for_app()
    delay = 15
    while True:
        try:
            await listen_once()
            delay = 15
            print("Telegram 连接断开，准备重连并回补漏掉的帖。", flush=True)
        except Exception as exc:
            print(f"监听中断: {type(exc).__name__}: {exc}", flush=True)
        await asyncio.sleep(delay)
        delay = min(delay * 2, 120)


if __name__ == "__main__":
    asyncio.run(run())
