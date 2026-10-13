"""Process counters in Prometheus text format, served on /metrics (plan decision 12).

No labels carry a name or text (rule 10): outcomes are failure codes and step names only.
"""

from __future__ import annotations

import threading
from collections import defaultdict

_lock = threading.Lock()
_counters: dict[tuple[str, tuple[tuple[str, str], ...]], float] = defaultdict(float)
_sums: dict[str, tuple[float, int]] = {}
_gauges: dict[str, float] = defaultdict(float)


def inc(name: str, value: float = 1.0, **labels: str) -> None:
    with _lock:
        _counters[(name, tuple(sorted(labels.items())))] += value


def observe(name: str, seconds: float) -> None:
    with _lock:
        total, count = _sums.get(name, (0.0, 0))
        _sums[name] = (total + seconds, count + 1)


def gauge_add(name: str, delta: float) -> None:
    with _lock:
        _gauges[name] += delta


def reset() -> None:
    with _lock:
        _counters.clear()
        _sums.clear()
        _gauges.clear()


def render() -> str:
    lines: list[str] = []
    with _lock:
        for (name, labels), value in sorted(_counters.items()):
            tag = ",".join(f'{k}="{v}"' for k, v in labels)
            lines.append(f"{name}{{{tag}}} {value:g}" if tag else f"{name} {value:g}")
        for name, (total, count) in sorted(_sums.items()):
            lines.append(f"{name}_seconds_sum {total:.3f}")
            lines.append(f"{name}_seconds_count {count}")
        for name, value in sorted(_gauges.items()):
            lines.append(f"{name} {value:g}")
    return "\n".join(lines) + "\n"
