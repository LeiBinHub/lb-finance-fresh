"""Time window for Telegram reconnect backfill. No Telethon dependency."""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

OVERLAP_SEC = 60


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def aware(value: datetime | None) -> datetime | None:
    if value is None:
        return None
    if value.tzinfo is None:
        return value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc)


def cutoff_at(now: datetime, last_stop: datetime | None, days: int = 7, overlap_sec: int = OVERLAP_SEC) -> datetime:
    """Default one week. If we stopped more recently, resume from that time."""
    current = aware(now) or utc_now()
    floor = current - timedelta(days=max(1, days))
    stopped = aware(last_stop)
    if stopped is not None and stopped > floor:
        return stopped - timedelta(seconds=max(0, overlap_sec))
    return floor


def cursor_stop(data: dict | None) -> datetime | None:
    if not data:
        return None
    raw = data.get("pulledAt")
    if raw is None:
        return None
    try:
        return datetime.fromtimestamp(int(raw) / 1000, tz=timezone.utc)
    except (TypeError, ValueError, OSError):
        return None
