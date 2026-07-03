"""SSRF guard for web tools."""

from __future__ import annotations

import ipaddress
import socket
from urllib.parse import urlparse


class SsrfGuardError(PermissionError):
    """Raised when a URL targets a blocked network location."""


BLOCKED_HOSTS = {"localhost", "localhost.localdomain"}


def validate_public_http_url(url: str) -> str:
    parsed = urlparse(url)
    if parsed.scheme not in {"http", "https"}:
        raise SsrfGuardError("only http and https URLs are allowed")
    if parsed.username or parsed.password:
        raise SsrfGuardError("URLs with embedded credentials are blocked")
    if not parsed.hostname:
        raise SsrfGuardError("URL must include a hostname")
    host = normalize_hostname(parsed.hostname)
    if host in BLOCKED_HOSTS or host.endswith(".localhost"):
        raise SsrfGuardError("localhost URLs are blocked")

    try:
        addresses = {item[4][0] for item in socket.getaddrinfo(host, parsed.port or _default_port(parsed.scheme))}
    except socket.gaierror as exc:
        raise SsrfGuardError(f"failed to resolve host: {host}") from exc

    for address in addresses:
        ip = ipaddress.ip_address(address)
        if _is_blocked_ip(ip):
            raise SsrfGuardError(f"blocked non-public address: {address}")
    return url


def normalize_hostname(hostname: str) -> str:
    return hostname.lower().strip("[]").rstrip(".")


def _default_port(scheme: str) -> int:
    return 443 if scheme == "https" else 80


def _is_blocked_ip(ip: ipaddress.IPv4Address | ipaddress.IPv6Address) -> bool:
    return any(
        (
            ip.is_loopback,
            ip.is_private,
            ip.is_link_local,
            ip.is_multicast,
            ip.is_reserved,
            ip.is_unspecified,
        )
    )