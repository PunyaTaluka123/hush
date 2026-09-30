"""Thin stdlib client for the Firebase emulator suite (Auth, Functions, Firestore)."""
from __future__ import annotations

import base64
import json
import os
import urllib.error
import urllib.request

PROJECT = os.environ.get("HUSH_PROJECT", "demo-hush")
HOST = os.environ.get("HUSH_EMULATOR_HOST", "127.0.0.1")
FN = f"http://{HOST}:5001/{PROJECT}/asia-southeast1"
FS = f"http://{HOST}:8080/v1/projects/{PROJECT}/databases/(default)/documents"
AUTH = f"http://{HOST}:9099"
OWNER = "owner"  # emulator admin token: bypasses security rules


class CallError(Exception):
    def __init__(self, status: str, message: str):
        super().__init__(f"{status}: {message}")
        self.status = status


def http(method: str, url: str, body=None, token: str | None = None) -> tuple[int, dict]:
    data = None if body is None else json.dumps(body, separators=(",", ":")).encode()
    req = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": "application/json"})
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read()
            return r.status, json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw)
        except ValueError:
            return e.code, {"raw": raw.decode(errors="replace")}


class User:
    def __init__(self, phone: str, uid: str, token: str):
        self.phone, self.uid, self.token = phone, uid, token

    def __repr__(self):
        return f"User({self.phone})"


def sign_in(phone: str) -> User:
    """Phone-number sign-in through the Auth emulator (SMS code read back from it)."""
    _, r = http("POST", f"{AUTH}/identitytoolkit.googleapis.com/v1/accounts:sendVerificationCode?key=fake",
                {"phoneNumber": phone, "recaptchaToken": "x"})
    session = r["sessionInfo"]
    _, codes = http("GET", f"{AUTH}/emulator/v1/projects/{PROJECT}/verificationCodes")
    code = [c for c in codes["verificationCodes"] if c["sessionInfo"] == session][0]["code"]
    _, r = http("POST", f"{AUTH}/identitytoolkit.googleapis.com/v1/accounts:signInWithPhoneNumber?key=fake",
                {"sessionInfo": session, "code": code})
    return User(phone, r["localId"], r["idToken"])


def set_claims(user: User, claims: dict) -> User:
    http("POST", f"{AUTH}/identitytoolkit.googleapis.com/v1/projects/{PROJECT}/accounts:update",
         {"localId": user.uid, "customAttributes": json.dumps(claims)}, OWNER)
    return sign_in(user.phone)  # fresh token carries the claims


def call_raw(user: User | None, name: str, body: bytes) -> tuple[int, dict]:
    req = urllib.request.Request(f"{FN}/{name}", data=body, method="POST", headers={"Content-Type": "application/json"})
    if user:
        req.add_header("Authorization", f"Bearer {user.token}")
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


def call(user: User | None, name: str, data: dict):
    status, r = call_raw(user, name, json.dumps({"data": data}, separators=(",", ":")).encode())
    if "error" in r:
        raise CallError(r["error"].get("status", str(status)), r["error"].get("message", ""))
    return r["result"]


# ---------------------------------------------------------------- Firestore REST
def _dec(v: dict):
    (k, x), = v.items()
    if k == "mapValue":
        return {a: _dec(b) for a, b in x.get("fields", {}).items()}
    if k == "arrayValue":
        return [_dec(i) for i in x.get("values", [])]
    if k == "integerValue":
        return int(x)
    if k == "bytesValue":
        return base64.b64decode(x)
    if k == "nullValue":
        return None
    return x  # string, double, boolean, timestamp, reference


def _enc(v):
    if v is None:
        return {"nullValue": None}
    if isinstance(v, bool):
        return {"booleanValue": v}
    if isinstance(v, int):
        return {"integerValue": str(v)}
    if isinstance(v, float):
        return {"doubleValue": v}
    if isinstance(v, dict):
        return {"mapValue": {"fields": {k: _enc(x) for k, x in v.items()}}}
    if isinstance(v, list):
        return {"arrayValue": {"values": [_enc(x) for x in v]}}
    return {"stringValue": str(v)}


def doc_of(d: dict) -> dict:
    out = {k: _dec(v) for k, v in d.get("fields", {}).items()}
    out["_id"] = d["name"].rsplit("/", 1)[1]
    return out


def get(path: str, token: str = OWNER) -> tuple[int, dict | None]:
    status, r = http("GET", f"{FS}/{path}", token=token)
    return status, doc_of(r) if status == 200 else None


def put(path: str, fields: dict, token: str = OWNER) -> int:
    status, _ = http("PATCH", f"{FS}/{path}", {"fields": {k: _enc(v) for k, v in fields.items()}}, token)
    return status


def ls(path: str, token: str = OWNER) -> list[dict]:
    out, page = [], ""
    while True:
        status, r = http("GET", f"{FS}/{path}?pageSize=300{'&pageToken=' + page if page else ''}", token=token)
        assert status == 200, r
        out += [doc_of(d) for d in r.get("documents", [])]
        page = r.get("nextPageToken")
        if not page:
            return out


def query(collection: str, filters: list[tuple[str, str, object]], token: str = OWNER, parent: str = "") -> tuple[int, list[dict]]:
    ops = {"==": "EQUAL", "array-contains": "ARRAY_CONTAINS", "<=": "LESS_THAN_OR_EQUAL"}
    q = {"structuredQuery": {"from": [{"collectionId": collection}], "where": {"compositeFilter": {"op": "AND", "filters": [
        {"fieldFilter": {"field": {"fieldPath": f}, "op": ops[op], "value": _enc(v)}} for f, op, v in filters]}}}}
    status, r = http("POST", f"{FS}{'/' + parent if parent else ''}:runQuery", q, token)
    if status != 200:
        return status, []
    return status, [doc_of(x["document"]) for x in r if "document" in x]


# ---------------------------------------------------------------- test controls
def reset():
    http("DELETE", f"http://{HOST}:8080/emulator/v1/projects/{PROJECT}/databases/(default)/documents")
    http("DELETE", f"{AUTH}/emulator/v1/projects/{PROJECT}/accounts")


def clock(now_ms: int):
    """Sets the backend's fake clock and runs the timer sweep (emulator only)."""
    with urllib.request.urlopen(f"{FN}/testClock?now={now_ms}", timeout=60) as r:
        assert json.loads(r.read())["ok"]


def up() -> bool:
    try:
        urllib.request.urlopen(f"http://{HOST}:8080/", timeout=2)
        return True
    except urllib.error.HTTPError:
        return True
    except OSError:
        return False
