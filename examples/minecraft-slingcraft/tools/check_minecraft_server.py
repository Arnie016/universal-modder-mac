#!/usr/bin/env python3
"""Read-only Java server-list handshake. Does not log in or send game inputs."""
import argparse
import json
import socket
import struct
import time


def varint(value):
    value &= 0xffffffff
    out = bytearray()
    while True:
        byte = value & 127
        value >>= 7
        out.append(byte | (128 if value else 0))
        if not value:
            return bytes(out)


def exact(sock, size):
    out = bytearray()
    while len(out) < size:
        block = sock.recv(size - len(out))
        if not block:
            raise EOFError('Server closed the connection before the response completed')
        out.extend(block)
    return bytes(out)


def read_varint(sock):
    value = 0
    for shift in range(0, 35, 7):
        byte = exact(sock, 1)[0]
        value |= (byte & 127) << shift
        if not byte & 128:
            return value
    raise ValueError('Invalid protocol integer')


def packet(sock, payload):
    sock.sendall(varint(len(payload)) + payload)


def probe(host, port, timeout=5):
    began = time.monotonic()
    encoded = host.encode('utf-8')
    if len(encoded) > 255:
        raise ValueError('Hostname is too long')
    with socket.create_connection((host, port), timeout=timeout) as sock:
        packet(sock, b'\x00' + varint(-1) + varint(len(encoded)) + encoded + struct.pack('>H', port) + b'\x01')
        packet(sock, b'\x00')
        size = read_varint(sock)
        if not 3 <= size <= 2 * 1024 * 1024:
            raise ValueError('Invalid server-list response size')
        # Parse within a bounded packet so a malformed server cannot consume the next message.
        import io
        class PacketReader(io.BytesIO):
            recv = io.BytesIO.read
        body = PacketReader(exact(sock, size))
        if read_varint(body) != 0:
            raise ValueError('Unexpected response packet')
        text_size = read_varint(body)
        if text_size > size:
            raise ValueError('Invalid JSON size')
        status = json.loads(exact(body, text_size))
    return {'host': host, 'port': port, 'statusRoundTripMs': round((time.monotonic() - began) * 1000),
            'version': status.get('version'), 'players': status.get('players'),
            'description': status.get('description'), 'statusHandshake': 'PASSED',
            'authenticatedPlayerJoin': 'NOT_TESTED'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('host')
    parser.add_argument('--port', type=int, default=25565)
    args = parser.parse_args()
    print(json.dumps(probe(args.host, args.port), indent=2))
