from __future__ import annotations

import asyncio
import json

import p2p_rendezvous as rendezvous


class _Client(asyncio.DatagramProtocol):
    """A UDP client that collects whatever the rendezvous sends back."""

    def __init__(self) -> None:
        self.transport: asyncio.DatagramTransport | None = None
        self.queue: asyncio.Queue[dict] = asyncio.Queue()

    def connection_made(self, transport) -> None:  # type: ignore[override]
        self.transport = transport

    def datagram_received(self, data: bytes, addr) -> None:  # type: ignore[override]
        self.queue.put_nowait(json.loads(data.decode("utf-8")))

    async def send(self, payload: dict) -> None:
        assert self.transport is not None
        self.transport.sendto(json.dumps(payload).encode("utf-8"))

    async def receive(self, timeout: float = 2.0) -> dict:
        return await asyncio.wait_for(self.queue.get(), timeout)


def _hello(session_id: str, role: str, token: str) -> dict:
    return {"type": "hello", "session_id": session_id, "role": role, "token": token}


def _keepalive(session_id: str, role: str, token: str) -> dict:
    return {"type": "keepalive", "session_id": session_id, "role": role, "token": token}


def test_store_requires_matching_role_token() -> None:
    store = rendezvous.P2PSessionStore()
    session, control_token, device_token = store.create("device-1")

    assert store.register(session.session_id, "control", device_token, ("1.1.1.1", 1)) == (None, None)
    assert store.register(session.session_id, "nobody", control_token, ("1.1.1.1", 1)) == (None, None)
    assert store.register("missing", "control", control_token, ("1.1.1.1", 1)) == (None, None)

    registered, peer = store.register(session.session_id, "control", control_token, ("1.1.1.1", 1001))
    assert registered is not None and peer is None
    registered, peer = store.register(session.session_id, "device", device_token, ("2.2.2.2", 2002))
    assert registered is not None
    assert peer == ("1.1.1.1", 1001)
    # The control side learns the device endpoint the next time it knocks.
    registered, peer = store.register(session.session_id, "control", control_token, ("1.1.1.1", 1001))
    assert peer == ("2.2.2.2", 2002)


def test_session_ttl_slides_on_register_and_keepalive(monkeypatch) -> None:
    clock = {"now": 10_000.0}
    monkeypatch.setattr(rendezvous.time, "time", lambda: clock["now"])
    store = rendezvous.P2PSessionStore()
    session, control_token, _device_token = store.create("device-1")
    assert session.expires_at == 10_000 + rendezvous.SESSION_TTL_SECONDS

    clock["now"] += 60
    store.register(session.session_id, "control", control_token, ("1.1.1.1", 1001))
    assert session.expires_at == 10_060 + rendezvous.SESSION_TTL_SECONDS

    clock["now"] += 60
    assert store.keepalive(session.session_id, "control", control_token) is session
    assert session.expires_at == 10_120 + rendezvous.SESSION_TTL_SECONDS
    assert session.last_seen_at == 10_120

    # Once nothing has refreshed it, the session is pruned instead of lingering forever.
    clock["now"] += rendezvous.SESSION_TTL_SECONDS + 1
    assert store.lookup(session.session_id) is None
    assert store.keepalive(session.session_id, "control", control_token) is None


def test_keepalive_rejects_wrong_token() -> None:
    store = rendezvous.P2PSessionStore()
    session, control_token, _device_token = store.create("device-1")
    assert store.keepalive(session.session_id, "control", "not-the-token") is None
    assert store.keepalive(session.session_id, "control", control_token) is not None


def test_rendezvous_exchanges_peer_endpoints_and_answers_keepalive() -> None:
    async def scenario() -> None:
        store = rendezvous.P2PSessionStore()
        session, control_token, device_token = store.create("device-1")
        loop = asyncio.get_running_loop()
        server_transport, _protocol = await loop.create_datagram_endpoint(
            lambda: rendezvous.RendezvousProtocol(store), local_addr=("127.0.0.1", 0)
        )
        _server_host, server_port = server_transport.get_extra_info("sockname")
        control_transport, control = await loop.create_datagram_endpoint(
            _Client, local_addr=("127.0.0.1", 0), remote_addr=("127.0.0.1", server_port)
        )
        device_transport, device = await loop.create_datagram_endpoint(
            _Client, local_addr=("127.0.0.1", 0), remote_addr=("127.0.0.1", server_port)
        )
        _control_host, control_port = control_transport.get_extra_info("sockname")
        _device_host, device_port = device_transport.get_extra_info("sockname")
        try:
            # The device knocks first: nobody to pair with yet.
            await device.send(_hello(session.session_id, "device", device_token))
            waiting = await device.receive()
            assert waiting["type"] == "waiting"
            assert waiting["ttl"] == rendezvous.SESSION_TTL_SECONDS

            # The control side knocks second: both sides learn the other's public endpoint.
            await control.send(_hello(session.session_id, "control", control_token))
            control_peer = await control.receive()
            device_peer = await device.receive()
            assert control_peer["type"] == "peer"
            assert (control_peer["host"], control_peer["port"]) == ("127.0.0.1", device_port)
            assert device_peer["type"] == "peer"
            assert (device_peer["host"], device_peer["port"]) == ("127.0.0.1", control_port)

            # Keepalive refreshes the deadline and reports it back.
            before = store.lookup(session.session_id)
            assert before is not None
            await control.send(_keepalive(session.session_id, "control", control_token))
            alive = await control.receive()
            assert alive["type"] == "alive"
            assert alive["expires_at"] >= before.expires_at

            # A bad token is told to renegotiate instead of silently spinning.
            await device.send(_keepalive(session.session_id, "device", "wrong"))
            expired = await device.receive()
            assert expired["type"] == "expired"
        finally:
            control_transport.close()
            device_transport.close()
            server_transport.close()

    asyncio.run(scenario())


def test_oversized_datagrams_are_ignored() -> None:
    async def scenario() -> None:
        store = rendezvous.P2PSessionStore()
        loop = asyncio.get_running_loop()
        server_transport, _ = await loop.create_datagram_endpoint(
            lambda: rendezvous.RendezvousProtocol(store), local_addr=("127.0.0.1", 0)
        )
        _host, port = server_transport.get_extra_info("sockname")
        client_transport, client = await loop.create_datagram_endpoint(
            _Client, local_addr=("127.0.0.1", 0), remote_addr=("127.0.0.1", port)
        )
        try:
            assert client.transport is not None
            client.transport.sendto(b"{" + b"x" * rendezvous.MAX_DATAGRAM_BYTES)
            try:
                await asyncio.wait_for(client.queue.get(), timeout=0.4)
            except asyncio.TimeoutError:
                return
            raise AssertionError("oversized datagram should be dropped")
        finally:
            client_transport.close()
            server_transport.close()

    asyncio.run(scenario())
