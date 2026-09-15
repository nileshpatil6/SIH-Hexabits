# iTantra mesh protocol v1

Written from scratch for this project, following ideas in the public-domain BitChat whitepaper. No BitChat (GPL) code is used.

## Packet

All integers are big endian.

| Field | Bytes | Notes |
|---|---|---|
| version | 1 | `1` |
| type | 1 | see below |
| ttl | 1 | starts at 7 |
| flags | 1 | `0x01` has recipient, `0x02` signed |
| timestampMs | 8 | sender clock |
| senderId | 8 | first 8 bytes of SHA-256(Ed25519 public key) |
| recipientId | 8 | only when flag `0x01` |
| payloadLen | 2 | |
| payload | n | |
| signature | 64 | Ed25519 over all preceding bytes, only when flag `0x02` |

Header is 22 bytes (30 with recipient).

## Types

| Code | Name | Payload |
|---|---|---|
| 1 | ANNOUNCE | nickLen(1) nick langLen(1) lang x25519Pub(32) ed25519Pub(32), signed, every 15 s |
| 2 | TEXT | langLen(1) lang seq(4) utteranceMs(2) textLen(2) utf8, signed |
| 3 | ALERT | same as TEXT; relayed without density cap, acked, played at alarm volume |
| 4 | ACK | originalSender(8) originalTimestamp(8) |
| 5 | FRAGMENT | fragId(8) index(1) total(1) data |
| 6 | DM | ChaCha20-Poly1305(nonce(12) + ct + tag(16)) of a TEXT payload, key = HKDF(X25519), AAD = sender+recipient |
| 7, 8 | PACK_OFFER / PACK_CHUNK | reserved for model pack sharing over Wi-Fi Direct |

## Routing

- Flood with dedup: LRU of 1000 keys (sender, timestamp, type, payload hash), 5 minute expiry.
- Relay TTL: `ttl - 1`, capped at 5 when the node has 6 or more links, 6 for 3 to 5 links, uncapped for sparse chains. Alerts are never capped.
- Relay jitter 10 to 220 ms so neighbours do not collide.
- Hop count shown in the UI = `7 - ttl + 1`.
- Outbox: when no neighbour is connected, up to 100 packets are kept for 24 h and flushed when a link comes up.

## Links

- **BLE**: service `1a7a0001-6e73-4b5a-9c3a-1749a1d0c0de`, characteristic `1a7a0002-...` (write without response + notify). Every phone is both peripheral and central, up to 6 outgoing links. Link framing is `len(2) + packet`, split into `MTU - 3` byte writes. Packets over 499 B are fragmented into 469 B data chunks.
- **Wi-Fi Direct**: group owner listens on TCP 47474; frames are `len(4) + packet`.

## Size example

"बाढ़ का पानी स्कूल के पास बढ़ रहा है, ऊँची जगह पर जाएँ" is 3.5 s of speech.

- As 16 kbps Opus audio: about 7 000 B.
- As an iTantra TEXT packet: 22 B header + 9 B payload header + 139 B UTF-8 + 64 B signature = 234 B.
