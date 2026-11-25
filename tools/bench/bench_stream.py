#!/usr/bin/env python3
"""
Passive stream benchmark for the EasyControlNext server.

It pushes a server jar, starts it with app_process, connects the same way the
Android client does (main socket first, then video socket) through `adb forward`,
and records every video frame. It never injects touch or key events: the only
thing it sends on the control socket is the keep-alive byte (type 4).

Metrics:
  - fps, average bitrate, frame size p50/p95/max, IDR count and sizes
  - inter-arrival time of frames at the host (p50/p95/p99/max) -> stutter
  - one-way delay variation: (arrival - pts) normalised to its minimum -> jitter
  - encoder latency reported by the server when started with stats=1

Reconnect test (--reconnect-cycles N): after the measurement window the sockets are
dropped abruptly N times; each time the tool reconnects and checks that the server
resends the handshake and resumes with codec config + an IDR frame, and reports how
long that took. With --detached the server is started the way the app starts it
(setsid, outside the adb shell session), which is what lets it survive an adb drop.

Usage:
  python bench_stream.py --serial 192.168.18.61:5560 --jar path/to/server.apk \
      --seconds 30 --label baseline [--server-args "maxFps=60 maxVideoBit=4"]
"""
import argparse
import json
import os
import secrets
import socket
import statistics
import struct
import subprocess
import sys
import threading
import time

REMOTE_JAR = "/data/local/tmp/easycontrolnext_bench.jar"
SERVER_CLASS = "com.shiyunjin.easycontrolnext.server.Server"


def pct(values, p):
    if not values:
        return None
    s = sorted(values)
    k = max(0, min(len(s) - 1, int(round((p / 100.0) * (len(s) - 1)))))
    return s[k]


def recv_exact(sock, n):
    buf = bytearray(n)
    view = memoryview(buf)
    got = 0
    while got < n:
        r = sock.recv_into(view[got:], n - got)
        if r == 0:
            raise ConnectionError("socket closed")
        got += r
    return bytes(buf)


def h264_nal_types(payload):
    """Return the set of NAL unit types found in an Annex-B H.264 payload."""
    types = set()
    i, n = 0, len(payload)
    while i + 3 < n:
        if payload[i] == 0 and payload[i + 1] == 0 and (payload[i + 2] == 1 or (payload[i + 2] == 0 and i + 3 < n and payload[i + 3] == 1)):
            j = i + 3 if payload[i + 2] == 1 else i + 4
            if j < n:
                types.add(payload[j] & 0x1F)
            i = j
            if len(types) > 3:
                break
        else:
            i += 1
        if i > 256:  # NAL headers we care about are at the start
            break
    return types


ROLE_MAIN, ROLE_VIDEO = 0, 1


def connect_pair(port, token, timeout=10):
    """Connect main+video with the role/token hello. Returns (main, video, video_header)."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        main_sock = video_sock = None
        try:
            main_sock = socket.create_connection(("127.0.0.1", port), timeout=5)
            main_sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            if token is not None:
                main_sock.sendall(bytes([ROLE_MAIN]) + token)
            else:
                time.sleep(0.15)  # legacy servers assign roles by accept order
            video_sock = socket.create_connection(("127.0.0.1", port), timeout=5)
            video_sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            if token is not None:
                video_sock.sendall(bytes([ROLE_VIDEO]) + token)
            video_sock.settimeout(5)
            hdr = recv_exact(video_sock, 9)
            return main_sock, video_sock, hdr
        except (OSError, ConnectionError):
            for s in (main_sock, video_sock):
                try:
                    s and s.close()
                except OSError:
                    pass
            time.sleep(0.2)
    return None, None, None


def adb(serial, *args, **kw):
    return subprocess.run(["adb", "-s", serial, *args], capture_output=True, text=True, encoding="utf-8", errors="replace", **kw)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--jar", required=True)
    ap.add_argument("--seconds", type=float, default=30)
    ap.add_argument("--label", default="run")
    ap.add_argument("--device-port", type=int, default=25166)
    ap.add_argument("--local-port", type=int, default=27183)
    ap.add_argument("--server-args", default="")
    ap.add_argument("--out", default=None, help="write JSON summary here")
    ap.add_argument("--reconnect-cycles", type=int, default=0)
    ap.add_argument("--detached", action="store_true", help="start server with setsid like the app")
    ap.add_argument("--dump", default=None, help="write raw frames/pongs (host perf_counter_ns, device pts) as JSON")
    ap.add_argument("--legacy", action="store_true", help="server built before the role/token handshake (no hello, no reconnect)")
    a = ap.parse_args()
    # never send the hello to a legacy server: it would parse the token bytes as control packets
    token = None if a.legacy else secrets.token_bytes(16)
    if a.legacy and a.reconnect_cycles:
        sys.exit("--reconnect-cycles needs a current server")

    push = adb(a.serial, "push", a.jar, REMOTE_JAR)
    if push.returncode != 0:
        sys.exit("push failed: " + push.stderr)
    adb(a.serial, "forward", f"tcp:{a.local_port}", f"tcp:{a.device_port}")

    args = {
        "serverPort": str(a.device_port), "listenerClip": "0", "isAudio": "0",
        "maxSize": "1600", "maxFps": "60", "maxVideoBit": "4", "keepAwake": "0",
        "supportH265": "0", "supportOpus": "0", "stats": "1",
    }
    if token is not None:
        args["token"] = token.hex()
    for kv in a.server_args.split():
        k, v = kv.split("=", 1)
        args[k] = v
    cmd = f"app_process -Djava.class.path={REMOTE_JAR} / {SERVER_CLASS} " + " ".join(f"{k}={v}" for k, v in args.items())
    if a.detached:
        log = "/data/local/tmp/easycontrolnext_bench.log"
        cmd = f"trap '' HUP; S=$(command -v setsid); $S {cmd} </dev/null >{log} 2>&1 &"
        adb(a.serial, "shell", cmd, timeout=15)
        server = subprocess.Popen(["adb", "-s", a.serial, "shell", f"sleep 1; tail -f {log}"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace", bufsize=1)
    else:
        server = subprocess.Popen(["adb", "-s", a.serial, "shell", cmd], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace", bufsize=1)
    server_lines = []

    def pump_server():
        for line in server.stdout:
            server_lines.append(line.rstrip())

    threading.Thread(target=pump_server, daemon=True).start()

    main_sock, video_sock, hdr = connect_pair(a.local_port, token, timeout=15)
    if video_sock is None:
        server.kill()
        sys.exit("could not connect to server. server output:\n" + "\n".join(server_lines))

    use_h265, width, height = struct.unpack(">bii", hdr)
    stop = threading.Event()

    pongs = []  # (rtt_us, offset_us) with offset = host_clock_us - device_clock_us

    def keepalive_and_drain():
        main_sock.settimeout(0.2)
        last = 0.0
        while not stop.is_set():
            now = time.time()
            if now - last >= 2.0:
                try:
                    main_sock.sendall(b"\x04")
                except OSError:
                    return
                last = now
            try:
                if not main_sock.recv(65536):
                    return
            except socket.timeout:
                pass
            except OSError:
                return

    def ping_and_parse():
        """Current protocol: ping every second and parse main packets to collect pongs."""
        main_sock.settimeout(1.0)
        buf = bytearray()

        def need(n):
            while len(buf) < n:
                chunk = main_sock.recv(65536)
                if not chunk:
                    raise ConnectionError("main closed")
                buf.extend(chunk)

        def take(n):
            need(n)
            out = bytes(buf[:n])
            del buf[:n]
            return out

        last = 0.0
        header_done = False
        while not stop.is_set():
            now = time.time()
            if now - last >= 1.0:
                try:
                    main_sock.sendall(b"\x0c" + struct.pack(">q", time.perf_counter_ns()))
                except OSError:
                    return
                last = now
            try:
                if not header_done:
                    if take(1) == b"\x01":
                        take(1)
                    header_done = True
                t = take(1)[0]
                if t in (1, 2):
                    take(struct.unpack(">i", take(4))[0])
                elif t == 3:
                    take(8)
                elif t == 5:
                    sent_ns, server_us = struct.unpack(">qq", take(16))
                    now_ns = time.perf_counter_ns()
                    pongs.append(((now_ns - sent_ns) / 1000, (sent_ns + now_ns) / 2 / 1000 - server_us, now_ns))
            except socket.timeout:
                continue
            except (OSError, ConnectionError):
                return

    threading.Thread(target=keepalive_and_drain if token is None else ping_and_parse, daemon=True).start()

    frames = []  # (arrival_ns, pts_us, size, nal_types)
    t_start = time.perf_counter_ns()
    end_ns = t_start + int(a.seconds * 1e9)
    try:
        while time.perf_counter_ns() < end_ns:
            try:
                head = recv_exact(video_sock, 4)
            except socket.timeout:
                continue  # static screen: the encoder may emit nothing for seconds
            size = struct.unpack(">i", head)[0]
            body = recv_exact(video_sock, size)
            arrival = time.perf_counter_ns()
            pts = struct.unpack(">q", body[:8])[0]
            frames.append((arrival, pts, size - 8, h264_nal_types(body[8:]) if not use_h265 else set()))
    except (ConnectionError, socket.timeout) as e:
        print("stream ended early:", e, file=sys.stderr)
    stop.set()
    for s in (main_sock, video_sock):
        try:
            s.close()
        except OSError:
            pass

    reconnects = []
    for cycle in range(a.reconnect_cycles):
        time.sleep(1.0)  # connection is gone; server should be waiting in its grace period
        t0 = time.perf_counter()
        m, v, h = connect_pair(a.local_port, token, timeout=10)
        if v is None:
            reconnects.append({"cycle": cycle, "ok": False, "error": "connect failed"})
            continue
        res = {"cycle": cycle, "ok": False, "header_same": h == hdr}
        try:
            m.settimeout(5)
            first = recv_exact(m, 1)
            has_audio = first == b"\x01"
            opus = has_audio and recv_exact(m, 1) == b"\x01"
            size_evt = recv_exact(m, 9)
            res["main_handshake_ok"] = size_evt[0] == 3
            if has_audio:
                # the client turns the first audio packet into the decoder's csd-0, so after a
                # reconnect it must be the codec config again (OpusHead / AAC AudioSpecificConfig)
                while True:
                    t = recv_exact(m, 1)[0]
                    if t == 4:
                        continue
                    if t in (2, 1):
                        n = struct.unpack(">i", recv_exact(m, 4))[0]
                        payload = recv_exact(m, n)
                        if t == 1:
                            res["first_audio_is_config"] = payload.startswith(b"OpusHead") if opus else n <= 8
                            break
                        continue
                    if t == 3:
                        recv_exact(m, 8)
                        continue
                    res["first_audio_is_config"] = f"unexpected main packet type {t}"
                    break
            got = []
            while True:
                size = struct.unpack(">i", recv_exact(v, 4))[0]
                body = recv_exact(v, size)
                got.append(h264_nal_types(body[8:]))
                if 5 in got[-1] or len(got) > 200:
                    break
            res["ms_to_idr"] = round((time.perf_counter() - t0) * 1000, 1)
            # first packet must carry SPS/PPS (separately, or in-band with the IDR), the IDR follows at once
            res["first_packet_nal"] = sorted(got[0])
            res["packets_until_idr"] = len(got)
            res["ok"] = res["main_handshake_ok"] and bool(got[0] & {7, 8}) and 5 in got[-1] and len(got) <= 2
        except (ConnectionError, socket.timeout, OSError) as e:
            res["error"] = str(e)
        reconnects.append(res)
        for s in (m, v):
            try:
                s.close()
            except OSError:
                pass

    # quit: a deliberate close must make the server exit right away
    quit_ok = None
    if a.reconnect_cycles:
        m, v, h = connect_pair(a.local_port, token, timeout=10)
        if m:
            m.sendall(b"\x0a")
            time.sleep(3)
            alive = adb(a.serial, "shell", "pgrep -f easycontrolnext_bench[.]jar").stdout.strip()
            quit_ok = alive == ""
            for s in (m, v):
                try:
                    s.close()
                except OSError:
                    pass
    time.sleep(0.5)
    server.kill()
    adb(a.serial, "shell", "pkill -f easycontrolnext_bench[.]jar")
    adb(a.serial, "forward", "--remove", f"tcp:{a.local_port}")

    # drop codec-config packets (pts 0 / SPS-PPS only) from timing stats
    media = [f for f in frames if not (f[3] and f[3] <= {7, 8})]
    if len(media) < 3:
        sys.exit("not enough frames: %d" % len(media))
    dur_s = (media[-1][0] - media[0][0]) / 1e9
    sizes = [f[2] for f in media]
    inter_ms = [(media[i][0] - media[i - 1][0]) / 1e6 for i in range(1, len(media))]
    owd = [(f[0] / 1e3) - f[1] for f in media]  # host-ns->us minus device pts-us (clock offset cancels)
    base = min(owd)
    delay_var_ms = [(x - base) / 1e3 for x in owd]
    idr = [f[2] for f in media if 5 in f[3]]

    enc_lines = [l for l in server_lines if l.startswith("STATS")]
    summary = {
        "label": a.label,
        "codec": "h265" if use_h265 else "h264",
        "video_size": [width, height],
        "frames": len(media),
        "duration_s": round(dur_s, 2),
        "fps": round(len(media) / dur_s, 2) if dur_s else None,
        "kbps": round(sum(sizes) * 8 / dur_s / 1000, 1) if dur_s else None,
        "frame_bytes": {"p50": pct(sizes, 50), "p95": pct(sizes, 95), "max": max(sizes)},
        "idr": {"count": len(idr), "max_bytes": max(idr) if idr else None},
        "interarrival_ms": {k: round(pct(inter_ms, p), 2) for k, p in (("p50", 50), ("p95", 95), ("p99", 99))} | {"max": round(max(inter_ms), 2)},
        "gaps_over_50ms": sum(1 for x in inter_ms if x > 50),
        "delay_variation_ms": {k: round(pct(delay_var_ms, p), 2) for k, p in (("p50", 50), ("p95", 95), ("p99", 99))} | {"max": round(max(delay_var_ms), 2)},
        "server_stats": enc_lines[-6:],
        "pong_count": len(pongs),
        "rtt_ms": ({"min": round(min(x[0] for x in pongs) / 1000, 2), "p50": round(pct([x[0] for x in pongs], 50) / 1000, 2)} if pongs else None),
        # capture (device pts) -> arrival at host, using the min-RTT pong for the clock offset (same method as the app overlay)
        "capture_to_host_ms": (lambda off: {k: round(pct([(f[0] / 1e3 - (f[1] + off)) / 1e3 for f in media], q), 2) for k, q in (("p50", 50), ("p95", 95), ("min", 0))})(min(pongs)[1]) if pongs else None,
        "reconnects": reconnects,
        "quit_exits_server": quit_ok,
        "largest_frames": [
            {"t_s": round((f[0] - media[0][0]) / 1e9, 2), "bytes": f[2], "nal": sorted(f[3])}
            for f in sorted(media, key=lambda f: f[2], reverse=True)[:5]
        ],
    }
    if a.dump:
        with open(a.dump, "w") as fh:
            json.dump({"frames": [[f[0], f[1], f[2]] for f in frames], "pongs": [list(x) for x in pongs],
                       "server_stats": enc_lines}, fh)
    print(json.dumps(summary, indent=2))
    if a.out:
        with open(a.out, "w") as fh:
            json.dump(summary, fh, indent=2)
    if not frames:
        print("\n".join(server_lines[-40:]), file=sys.stderr)


if __name__ == "__main__":
    main()
