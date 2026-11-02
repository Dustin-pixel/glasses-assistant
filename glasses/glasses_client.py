#!/usr/bin/env python3
"""
Glasses-side client for the Pi Zero 2 W.

Connects to the phone's WebSocket server over the phone's hotspot, answers
capture requests with a JPEG from the camera, shows overlays, and sends a
"button" event to start a conversation turn.

  pip install websockets
  python3 glasses_client.py --token ABCD1234            # phone = default gateway
  python3 glasses_client.py --token ABCD1234 --phone 192.168.1.50

Without a Pi camera it still runs: captures fail cleanly and Enter acts as the button,
so you can test the link from a laptop on the same network.
"""
import argparse
import asyncio
import io
import json
import subprocess
import sys

import websockets

PORT = 8765


# ------------------------------------------------------------------ hardware

class Camera:
    """Pi camera via picamera2 if available; otherwise captures report failure."""

    def __init__(self):
        self.cam = None
        try:
            from picamera2 import Picamera2
            self.cam = Picamera2()
            self.cam.configure(self.cam.create_still_configuration(main={"size": (1280, 960)}))
            self.cam.start()
        except Exception as e:  # no camera, not a Pi, etc.
            print(f"[camera] unavailable: {e}")

    def jpeg(self) -> bytes:
        if self.cam is None:
            raise RuntimeError("no camera")
        buf = io.BytesIO()
        self.cam.capture_file(buf, format="jpeg")
        return buf.getvalue()


class Display:
    """Overlay renderer. TODO: draw onto the passthrough feed on the microdisplay."""

    def __init__(self):
        self._clear_task = None

    def show(self, text: str, pos: str, ttl_ms: int):
        print(f"[display:{pos}] {text}")
        if self._clear_task:
            self._clear_task.cancel()
        self._clear_task = asyncio.get_running_loop().call_later(ttl_ms / 1000, self.clear)

    def clear(self):
        print("[display] (cleared)")


def default_gateway() -> str:
    """On the phone's hotspot, the default gateway is the phone."""
    out = subprocess.run(["ip", "route"], capture_output=True, text=True).stdout
    for line in out.splitlines():
        parts = line.split()
        if parts[:2] == ["default", "via"]:
            return parts[2]
    raise RuntimeError("No default gateway; pass --phone")


# ------------------------------------------------------------------ link

async def handle_messages(ws, camera: Camera, display: Display):
    async for raw in ws:
        if isinstance(raw, bytes):
            continue
        msg = json.loads(raw)
        kind = msg.get("type")
        if kind == "capture":
            try:
                frame = await asyncio.to_thread(camera.jpeg)
                await ws.send(frame)
            except Exception as e:
                await ws.send(json.dumps({"type": "capture_failed", "id": msg.get("id"), "reason": str(e)}))
        elif kind == "overlay":
            display.show(msg.get("text", ""), msg.get("pos", "bottom"), int(msg.get("ttl_ms", 6000)))
        elif kind == "clear":
            display.clear()


async def button_presses(ws):
    """Enter key = button. TODO: replace with a GPIO button (gpiozero.Button)."""
    loop = asyncio.get_running_loop()
    while True:
        await loop.run_in_executor(None, sys.stdin.readline)
        await ws.send(json.dumps({"type": "button"}))
        print("[button] sent")


async def run(phone: str, token: str):
    camera, display = Camera(), Display()
    url = f"ws://{phone}:{PORT}"
    backoff = 1
    while True:
        try:
            async with websockets.connect(url, max_size=None) as ws:
                await ws.send(json.dumps({"type": "hello", "token": token, "device": "pi-glasses"}))
                print(f"[link] connected to {url}  (press Enter to talk)")
                backoff = 1
                tasks = [asyncio.create_task(handle_messages(ws, camera, display)),
                         asyncio.create_task(button_presses(ws))]
                done, pending = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
                for t in pending:
                    t.cancel()
                for t in done:
                    t.result()
        except (OSError, websockets.ConnectionClosed) as e:
            print(f"[link] {e}; retrying in {backoff}s")
        await asyncio.sleep(backoff)
        backoff = min(backoff * 2, 15)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--token", required=True, help="Link token shown in the phone app")
    ap.add_argument("--phone", help="Phone IP (default: default gateway)")
    args = ap.parse_args()
    try:
        asyncio.run(run(args.phone or default_gateway(), args.token))
    except KeyboardInterrupt:
        pass
