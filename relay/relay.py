#!/usr/bin/env python3
"""MambaDrone relay.

Public side (behind nginx, 127.0.0.1:8792):
  GET  /drone/ws?token=...            phone app websocket
  POST /drone/upload?token=&mission=&name=   raw photo upload from the app
  GET  /drone/share/<name>?token=     download a file from data/share (hand-off to other machines)
Local side (127.0.0.1:8793, never proxied):
  GET  /status                        last telemetry + link state
  POST /cmd  {"cmd":..., "args":{...}, "timeout":s}   send to app, wait for result
  GET  /events?since=N                app events/log lines
"""
import asyncio, json, os, re, time, itertools
from pathlib import Path
from aiohttp import web, WSMsgType

BASE = Path(__file__).resolve().parent
DATA = BASE / "data"
TOKEN = (BASE / ".token").read_text().strip()

state = {"ws": None, "telemetry": None, "tele_at": 0, "connected_at": 0}
pending: dict[int, asyncio.Future] = {}
events: list[dict] = []
seq = itertools.count(1)
evseq = itertools.count(1)


def add_event(ev):
    ev["n"] = next(evseq)
    ev.setdefault("t", time.time())
    events.append(ev)
    del events[:-500]


def authed(req):
    return req.query.get("token") == TOKEN


async def ws_handler(req):
    if not authed(req):
        raise web.HTTPUnauthorized()
    ws = web.WebSocketResponse(heartbeat=15)
    await ws.prepare(req)
    old = state["ws"]
    state["ws"], state["connected_at"] = ws, time.time()
    if old is not None and not old.closed:
        await old.close()
    add_event({"type": "link", "msg": "app connected"})
    try:
        async for msg in ws:
            if msg.type != WSMsgType.TEXT:
                continue
            m = json.loads(msg.data)
            t = m.get("type")
            if t == "telemetry":
                state["telemetry"], state["tele_at"] = m, time.time()
            elif t == "result":
                f = pending.pop(m.get("id"), None)
                if f and not f.done():
                    f.set_result(m)
                add_event(m)
            else:
                add_event(m)
    finally:
        if state["ws"] is ws:
            state["ws"] = None
            add_event({"type": "link", "msg": "app disconnected"})
    return ws


async def share(req):
    if not authed(req):
        raise web.HTTPUnauthorized()
    name = re.sub(r"[^\w.-]", "_", req.match_info["name"])
    f = DATA / "share" / name
    if not f.is_file():
        raise web.HTTPNotFound()
    return web.FileResponse(f)


async def upload(req):
    if not authed(req):
        raise web.HTTPUnauthorized()
    mission = re.sub(r"[^\w.-]", "_", req.query.get("mission", "misc"))
    name = re.sub(r"[^\w.-]", "_", req.query.get("name", f"{int(time.time()*1000)}.jpg"))
    d = DATA / mission
    d.mkdir(parents=True, exist_ok=True)
    tmp = d / (name + ".part")
    n = 0
    with open(tmp, "wb") as f:
        async for chunk in req.content.iter_chunked(1 << 16):
            f.write(chunk)
            n += len(chunk)
    tmp.rename(d / name)
    add_event({"type": "upload", "mission": mission, "name": name, "bytes": n})
    return web.json_response({"ok": True, "bytes": n})


async def status(req):
    ws = state["ws"]
    return web.json_response({
        "app_connected": ws is not None and not ws.closed,
        "telemetry_age_s": round(time.time() - state["tele_at"], 1) if state["tele_at"] else None,
        "telemetry": state["telemetry"],
    })


async def cmd(req):
    body = await req.json()
    ws = state["ws"]
    if ws is None or ws.closed:
        return web.json_response({"ok": False, "msg": "app not connected"}, status=503)
    i = next(seq)
    fut = asyncio.get_running_loop().create_future()
    pending[i] = fut
    await ws.send_json({"id": i, "cmd": body["cmd"], "args": body.get("args", {})})
    try:
        res = await asyncio.wait_for(fut, body.get("timeout", 30))
    except asyncio.TimeoutError:
        pending.pop(i, None)
        return web.json_response({"ok": None, "id": i, "msg": "no result yet (still running on phone?)"})
    return web.json_response(res)


async def get_events(req):
    since = int(req.query.get("since", 0))
    return web.json_response([e for e in events if e["n"] > since])


async def main():
    pub = web.Application(client_max_size=64 << 20)
    pub.add_routes([web.get("/drone/ws", ws_handler), web.post("/drone/upload", upload),
                    web.get("/drone/share/{name}", share)])
    loc = web.Application()
    loc.add_routes([web.get("/status", status), web.post("/cmd", cmd), web.get("/events", get_events)])
    for app, port in ((pub, 8792), (loc, 8793)):
        r = web.AppRunner(app)
        await r.setup()
        await web.TCPSite(r, "127.0.0.1", port).start()
    await asyncio.Event().wait()


if __name__ == "__main__":
    asyncio.run(main())
