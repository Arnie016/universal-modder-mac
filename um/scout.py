"""Scout open-source projects and shared posts for native Mac game mashups. Read-only; also an MCP server.

    um scout search "minecraft bridge websocket"          # GitHub + Hacker News + Reddit, newest first
    um scout search "veloren plugin" --sources github --limit 5 --json
    um scout repo veloren/veloren                         # Mac build evidence, mashup hooks, licence, disposition
    um scout post https://x.com/someone/status/123        # a shared X/Twitter post -> its text and linked repos
    um scout installed cyberpunk                          # is it installed here? (Steam, Epic, .app, App Store)
    um scout log --url URL --disposition REFERENCE_ONLY --insight "..."   # keep a finding
    um scout mcp                                          # serve all of the above to an agent over stdio (MCP)

Every result is a *source claim*: nothing here builds, downloads or runs a project, so a repo that says it
works on macOS is labelled that way until someone runs it. X/Twitter has no free search API: `post` reads one
post you already have through X's public oEmbed endpoint, and `search --sources x` only works with your own
X_BEARER_TOKEN (a paid X API plan). GITHUB_TOKEN, when set, raises GitHub's search rate limit.
"""
from __future__ import annotations

import html
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

from um.common import data_dir, die, emit

UA = "universal-modder-scout/0.1 (+https://github.com/Arnie016/universal-modder-mac)"
SOURCES = ("github", "hn", "reddit")
DISPOSITIONS = ("REFERENCE_ONLY", "BOUNDED_EXPERIMENT", "EXISTING_SKILL_UPDATE", "NEW_SKILL_CANDIDATE", "REJECTED")
GITHUB_RE = re.compile(r"github\.com/([A-Za-z0-9][\w.-]*)/([\w.-]+?)(?:\.git)?(?=[/#?\s\"'<)]|$)")
MAC_ASSET_RE = re.compile(r"(\.dmg$|darwin|mac-?os|\bmac\b|osx|apple|universal2?|aarch64-apple|arm64.*(mac|apple))", re.I)
MAC_TEXT_RE = re.compile(r"\b(mac ?os|macos|os x|apple silicon|m[1-5] (pro|max|ultra)?|metal|arm64|\.app\b|homebrew)\b", re.I)
MAC_FILES = ("Package.swift", ".xcodeproj", ".xcworkspace", "Info.plist", ".entitlements", "Podfile", ".command")
HOOK_TERMS = ["mod loader", "modloader", "plugin api", "plugin", "scripting", "lua", "injection", "inject", "dylib",
              "hook", "bridge", "websocket", "ipc", "multiplayer", "server", "mashup", "crossover", "mod api",
              "fabric", "bepinex", "harmony", "redscript", "zscript", "wasm"]
RISK_TERMS = ["anti-cheat bypass", "bypass anti-cheat", "aimbot", "wallhack", "esp hack", "drm bypass", "crack"]


# --------------------------------------------------------------------------- http (swappable in tests)

def _request(url: str, headers: dict | None = None, method: str = "GET", timeout: float = 15.0):
    req = urllib.request.Request(url, headers={"User-Agent": UA, **(headers or {})}, method=method)
    return urllib.request.urlopen(req, timeout=timeout)


def fetch_json(url: str, headers: dict | None = None):
    with _request(url, headers) as r:
        return json.loads(r.read().decode("utf-8", "replace"))


def fetch_text(url: str, headers: dict | None = None) -> str:
    with _request(url, headers) as r:
        return r.read(2_000_000).decode("utf-8", "replace")


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


def expand_url(url: str) -> str:
    """t.co and other shorteners: one HEAD, read Location, never load the target page."""
    if not re.match(r"https?://(t\.co|bit\.ly|buff\.ly|tinyurl\.com)/", url):
        return url
    opener = urllib.request.build_opener(_NoRedirect)
    try:
        opener.open(urllib.request.Request(url, headers={"User-Agent": UA}, method="HEAD"), timeout=10)
    except urllib.error.HTTPError as e:
        return e.headers.get("Location") or url
    except (urllib.error.URLError, OSError):
        pass
    return url


def _gh_headers(raw: bool = False) -> dict:
    h = {"Accept": "application/vnd.github.raw" if raw else "application/vnd.github+json"}
    if os.environ.get("GITHUB_TOKEN"):
        h["Authorization"] = f"Bearer {os.environ['GITHUB_TOKEN']}"
    return h


def repos_in(text: str) -> list[str]:
    out = []
    for owner, name in GITHUB_RE.findall(text or ""):
        if owner.lower() in ("orgs", "topics", "sponsors", "features", "marketplace", "settings", "apps"):
            continue
        r = f"{owner}/{name}"
        if r.lower() not in (x.lower() for x in out):
            out.append(r)
    return out


# --------------------------------------------------------------------------- search

def _iso(ts) -> str | None:
    if ts is None:
        return None
    if isinstance(ts, (int, float)):
        return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(ts))
    return str(ts)


def search_github(query: str, limit: int) -> list[dict]:
    q = urllib.parse.quote(query)
    d = fetch_json(f"https://api.github.com/search/repositories?q={q}&sort=updated&order=desc&per_page={limit}", _gh_headers())
    return [dict(source="github", title=i["full_name"], url=i["html_url"], repos=[i["full_name"]],
                 author=(i.get("owner") or {}).get("login"), date=i.get("pushed_at"), score=i.get("stargazers_count"),
                 snippet=(i.get("description") or "")[:300], licence=(i.get("license") or {}).get("spdx_id"),
                 archived=i.get("archived", False))
            for i in d.get("items", [])[:limit]]


def search_hn(query: str, limit: int) -> list[dict]:
    q = urllib.parse.quote(query)
    d = fetch_json(f"https://hn.algolia.com/api/v1/search_by_date?query={q}&tags=story&hitsPerPage={limit}")
    out = []
    for h in d.get("hits", [])[:limit]:
        link = h.get("url") or f"https://news.ycombinator.com/item?id={h.get('objectID')}"
        out.append(dict(source="hn", title=h.get("title"), url=link, repos=repos_in(f"{link} {h.get('story_text') or ''}"),
                        author=h.get("author"), date=h.get("created_at"), score=h.get("points"),
                        snippet=html.unescape(re.sub(r"<[^>]+>", " ", h.get("story_text") or ""))[:300],
                        discussion=f"https://news.ycombinator.com/item?id={h.get('objectID')}"))
    return out


def search_reddit(query: str, limit: int) -> list[dict]:
    q = urllib.parse.quote(query)
    d = fetch_json(f"https://www.reddit.com/search.json?q={q}&sort=new&limit={limit}&raw_json=1")
    out = []
    for c in (d.get("data") or {}).get("children", [])[:limit]:
        p = c.get("data", {})
        out.append(dict(source="reddit", title=p.get("title"), url=p.get("url_overridden_by_dest") or f"https://www.reddit.com{p.get('permalink', '')}",
                        repos=repos_in(f"{p.get('url', '')} {p.get('selftext', '')}"), author=p.get("author"),
                        date=_iso(p.get("created_utc")), score=p.get("score"), snippet=(p.get("selftext") or "")[:300],
                        subreddit=p.get("subreddit"), discussion=f"https://www.reddit.com{p.get('permalink', '')}"))
    return out


def search_x(query: str, limit: int) -> list[dict]:
    token = os.environ.get("X_BEARER_TOKEN")
    if not token:
        raise RuntimeError("X search needs X_BEARER_TOKEN (a paid X API plan); use `post` on a post URL instead")
    q = urllib.parse.quote(query)
    d = fetch_json(f"https://api.x.com/2/tweets/search/recent?query={q}&max_results={max(10, min(limit, 100))}"
                   "&tweet.fields=created_at,author_id,entities", {"Authorization": f"Bearer {token}"})
    out = []
    for t in d.get("data", [])[:limit]:
        urls = [u.get("expanded_url", "") for u in ((t.get("entities") or {}).get("urls") or [])]
        out.append(dict(source="x", title=t.get("text", "")[:120], url=f"https://x.com/i/status/{t.get('id')}",
                        repos=repos_in(" ".join(urls)), author=t.get("author_id"), date=t.get("created_at"),
                        score=None, snippet=t.get("text", "")[:300]))
    return out


SEARCHERS = {"github": search_github, "hn": search_hn, "reddit": search_reddit, "x": search_x}


def search(query: str, sources=SOURCES, limit: int = 8) -> dict:
    results, errors = [], {}
    for s in sources:
        if s not in SEARCHERS:
            errors[s] = f"unknown source (use {', '.join(SEARCHERS)})"
            continue
        try:
            results += SEARCHERS[s](query, limit)
        except Exception as e:  # one dead source must not hide the others
            errors[s] = f"{type(e).__name__}: {e}"
    results.sort(key=lambda r: r.get("date") or "", reverse=True)
    return dict(query=query, results=results, errors=errors, evidence="source claims; nothing built or run")


# --------------------------------------------------------------------------- repo assessment

def _hits(text: str, terms: list[str]) -> list[str]:
    t = text.lower()
    return [w for w in terms if re.search(r"(?<![a-z])" + re.escape(w) + r"(?![a-z])", t)]


def assess(meta: dict, readme: str, files: list[str], assets: list[str], now: float | None = None) -> dict:
    """Pure scoring from what GitHub returned, so it is testable offline."""
    now = now or time.time()
    text = " ".join([meta.get("description") or "", " ".join(meta.get("topics") or []), readme])
    mac = []
    mac += [f"release asset: {a}" for a in assets if MAC_ASSET_RE.search(a)][:5]
    mac += [f"file: {f}" for f in files if any(f.endswith(m) or m in f for m in MAC_FILES)][:5]
    words = sorted({m.group(0).lower() for m in MAC_TEXT_RE.finditer(text)})
    if words:
        mac.append("readme/description mentions: " + ", ".join(words[:8]))
    hooks, risks = _hits(text, HOOK_TERMS), _hits(text, RISK_TERMS)
    licence = (meta.get("license") or {}).get("spdx_id")
    pushed = meta.get("pushed_at")
    stale_days = None
    if pushed:
        try:
            stale_days = int((now - time.mktime(time.strptime(pushed[:19], "%Y-%m-%dT%H:%M:%S"))) / 86400)
        except ValueError:
            pass
    flags = []
    if meta.get("archived"):
        flags.append("archived")
    if not licence or licence == "NOASSERTION":
        flags.append("no clear licence: reading is fine, reuse is not")
    if stale_days is not None and stale_days > 365:
        flags.append(f"no push for {stale_days} days")
    flags += [f"risk term: {r}" for r in risks]
    native = [m for m in mac if not m.startswith("readme")]
    if meta.get("archived") or risks or not mac:
        disposition, why = "REJECTED", "archived, risky, or no Mac evidence at all"
    elif native and hooks and licence and licence != "NOASSERTION":
        disposition, why = "BOUNDED_EXPERIMENT", "Mac build evidence + a documented hook + a licence: worth one pinned local test"
    else:
        disposition, why = "REFERENCE_ONLY", "useful to read; Mac support or hooks are only claimed in prose"
    return dict(repo=meta.get("full_name"), url=meta.get("html_url"), description=meta.get("description"),
                licence=licence, stars=meta.get("stargazers_count"), pushed_at=pushed, default_branch=meta.get("default_branch"),
                mac_evidence=mac, hooks=hooks, flags=flags, disposition=disposition, why=why,
                evidence_state="source-verified listing only; not built, installed or run")


def assess_repo(repo: str) -> dict:
    repo = repos_in(repo)[0] if "github.com" in repo else repo.strip().strip("/")
    if not re.fullmatch(r"[\w.-]+/[\w.-]+", repo):
        raise ValueError(f"expected owner/name or a GitHub URL, got {repo!r}")
    meta = fetch_json(f"https://api.github.com/repos/{repo}", _gh_headers())
    try:
        readme = fetch_text(f"https://api.github.com/repos/{repo}/readme", _gh_headers(raw=True))[:200_000]
    except urllib.error.HTTPError:
        readme = ""
    try:
        rel = fetch_json(f"https://api.github.com/repos/{repo}/releases?per_page=5", _gh_headers())
        assets = [a["name"] for r in rel for a in r.get("assets", [])]
    except urllib.error.HTTPError:
        assets = []
    try:
        tree = fetch_json(f"https://api.github.com/repos/{repo}/git/trees/{meta.get('default_branch', 'HEAD')}?recursive=1", _gh_headers())
        files = [t["path"] for t in tree.get("tree", [])][:20_000]
    except urllib.error.HTTPError:
        files = []
    return assess(meta, readme, files, assets)


# --------------------------------------------------------------------------- shared posts

def _strip_tags(s: str) -> str:
    return html.unescape(re.sub(r"\s+", " ", re.sub(r"<[^>]+>", " ", s))).strip()


def resolve_post(url: str, assess_repos: int = 3) -> dict:
    """An X/Twitter post (oEmbed, no account) or any web page: text, outbound links, linked GitHub repos assessed."""
    out = dict(url=url, kind="web", text=None, author=None, links=[], repos=[], assessed=[], errors=[])
    if re.match(r"https?://(www\.)?(x|twitter)\.com/[^/]+/status/\d+", url):
        out["kind"] = "x"
        d = fetch_json("https://publish.twitter.com/oembed?omit_script=1&dnt=true&url=" + urllib.parse.quote(url, safe=""))
        body = d.get("html", "")
        out["author"] = d.get("author_name")
        out["text"] = _strip_tags(body.split("&mdash;")[0])
        out["links"] = [expand_url(h) for h in re.findall(r'href="([^"]+)"', body)
                        if not re.match(r"https?://(twitter|x)\.com/", h)]
    else:
        page = fetch_text(url)
        m = re.search(r"<title[^>]*>(.*?)</title>", page, re.I | re.S)
        out["text"] = _strip_tags(m.group(1)) if m else None
        out["links"] = sorted(set(re.findall(r'href="(https?://[^"]+)"', page)))[:200]
    out["repos"] = repos_in(" ".join(out["links"] + [out["text"] or "", url]))
    for r in out["repos"][:assess_repos]:
        try:
            out["assessed"].append(assess_repo(r))
        except Exception as e:
            out["errors"].append(f"{r}: {type(e).__name__}: {e}")
    out["note"] = "a post is a discovery signal, not proof; resolve claims to the linked source before relying on them"
    return out


# --------------------------------------------------------------------------- local games and the findings ledger

def installed(match: str | None = None) -> dict:
    from um import scan
    games = scan.all_games()
    if match:
        games = [g for g in games if match.lower() in (g.get("name") or "").lower() or match.lower() in g.get("path", "").lower()]
    return dict(match=match, games=games, checked=["Steam", "Epic manifests", ".app folders (macOS)", "Xbox (Windows)"],
                note="installed files only; store ownership (Epic/Steam/GOG/App Store library) needs the store itself")


def ledger_path():
    return data_dir() / "mashup-scout.jsonl"


def log_finding(url: str, disposition: str, insight: str, applies_to: str = "", evidence: str = "source claim",
                smallest_test: str = "") -> dict:
    if disposition not in DISPOSITIONS:
        raise ValueError(f"disposition must be one of {', '.join(DISPOSITIONS)}")
    rec = dict(at=time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), url=url, disposition=disposition, insight=insight,
               applies_to=applies_to, evidence=evidence, smallest_test=smallest_test)
    with ledger_path().open("a", encoding="utf-8") as f:
        f.write(json.dumps(rec) + "\n")
    return dict(saved=str(ledger_path()), record=rec)


def findings(limit: int = 50) -> dict:
    p = ledger_path()
    rows = [json.loads(l) for l in p.read_text(encoding="utf-8").splitlines() if l.strip()] if p.exists() else []
    return dict(path=str(p), count=len(rows), findings=rows[-limit:])


# --------------------------------------------------------------------------- MCP over stdio (no SDK needed)

TOOLS = [
    dict(name="scout_search", fn=lambda a: search(a["query"], tuple(a.get("sources") or SOURCES), int(a.get("limit", 8))),
         description="Search GitHub, Hacker News and Reddit (and X with your own X_BEARER_TOKEN) for game mashup / Mac modding "
                     "projects. Returns source claims, newest first, with linked GitHub repos.",
         inputSchema=dict(type="object", required=["query"], properties=dict(
             query=dict(type="string"), limit=dict(type="integer", minimum=1, maximum=30, default=8),
             sources=dict(type="array", items=dict(type="string", enum=list(SEARCHERS)))))),
    dict(name="assess_repo", fn=lambda a: assess_repo(a["repo"]),
         description="Read one GitHub repo (owner/name or URL): Mac build evidence (release assets, Xcode/Swift files, README), "
                     "mod/bridge hooks, licence, staleness, risk terms, and a disposition. Read-only.",
         inputSchema=dict(type="object", required=["repo"], properties=dict(repo=dict(type="string")))),
    dict(name="resolve_post", fn=lambda a: resolve_post(a["url"]),
         description="Resolve a shared post (X/Twitter via public oEmbed, or any web page) to its text, outbound links and "
                     "the GitHub repos it points to, each assessed. Use this for X links; X has no free search.",
         inputSchema=dict(type="object", required=["url"], properties=dict(url=dict(type="string")))),
    dict(name="installed_games", fn=lambda a: installed(a.get("match")),
         description="List games installed on this machine (Steam, Epic, .app and App Store on macOS), optionally filtered "
                     "by name. Installed files only, not store ownership.",
         inputSchema=dict(type="object", properties=dict(match=dict(type="string")))),
    dict(name="log_finding", fn=lambda a: log_finding(a["url"], a["disposition"], a["insight"], a.get("applies_to", ""),
                                                      a.get("evidence", "source claim"), a.get("smallest_test", "")),
         description="Append one finding to the local ledger (~/.universal-modder/mashup-scout.jsonl).",
         inputSchema=dict(type="object", required=["url", "disposition", "insight"], properties=dict(
             url=dict(type="string"), disposition=dict(type="string", enum=list(DISPOSITIONS)), insight=dict(type="string"),
             applies_to=dict(type="string"), evidence=dict(type="string"), smallest_test=dict(type="string")))),
    dict(name="list_findings", fn=lambda a: findings(int(a.get("limit", 50))),
         description="Read back the most recent findings from the local ledger.",
         inputSchema=dict(type="object", properties=dict(limit=dict(type="integer", minimum=1, maximum=500)))),
]
PROTOCOL = "2025-06-18"


def handle(msg: dict) -> dict | None:
    """One JSON-RPC message in, one response out (None for notifications)."""
    mid, method, params = msg.get("id"), msg.get("method"), msg.get("params") or {}
    if mid is None:
        return None
    def ok(result):
        return dict(jsonrpc="2.0", id=mid, result=result)
    if method == "initialize":
        return ok(dict(protocolVersion=params.get("protocolVersion") or PROTOCOL, capabilities=dict(tools={}),
                       serverInfo=dict(name="mashup-scout", version="0.1.0"),
                       instructions="Read-only scouting for native Mac game mashups. Results are source claims until run."))
    if method == "ping":
        return ok({})
    if method == "tools/list":
        return ok(dict(tools=[{k: t[k] for k in ("name", "description", "inputSchema")} for t in TOOLS]))
    if method == "tools/call":
        tool = next((t for t in TOOLS if t["name"] == params.get("name")), None)
        if not tool:
            return dict(jsonrpc="2.0", id=mid, error=dict(code=-32602, message=f"unknown tool {params.get('name')!r}"))
        try:
            res = tool["fn"](params.get("arguments") or {})
            return ok(dict(content=[dict(type="text", text=json.dumps(res, indent=1, default=str))], isError=False))
        except Exception as e:
            return ok(dict(content=[dict(type="text", text=f"{type(e).__name__}: {e}")], isError=True))
    return dict(jsonrpc="2.0", id=mid, error=dict(code=-32601, message=f"method not found: {method}"))


def serve(stdin=None, stdout=None):
    stdin, stdout = stdin or sys.stdin, stdout or sys.stdout
    for line in stdin:
        line = line.strip()
        if not line:
            continue
        try:
            msg = json.loads(line)
        except json.JSONDecodeError:
            resp = dict(jsonrpc="2.0", id=None, error=dict(code=-32700, message="parse error"))
        else:
            resp = handle(msg)
        if resp is not None:
            stdout.write(json.dumps(resp) + "\n")
            stdout.flush()


# --------------------------------------------------------------------------- CLI

def _print_results(r: dict):
    for x in r["results"]:
        repos = f"  [{', '.join(x['repos'])}]" if x.get("repos") else ""
        print(f"{x['source']:6} {(x.get('date') or '')[:10]}  {x.get('title')}{repos}\n       {x['url']}")
    for s, e in r["errors"].items():
        print(f"{s}: unavailable ({e})", file=sys.stderr)


def main(args):
    try:
        if args.cmd == "search":
            r = search(args.query, tuple(args.sources.split(",")), args.limit)
            emit(r) if args.json else _print_results(r)
        elif args.cmd == "repo":
            emit(assess_repo(args.repo))
        elif args.cmd == "post":
            emit(resolve_post(args.url))
        elif args.cmd == "installed":
            emit(installed(args.match))
        elif args.cmd == "log":
            emit(log_finding(args.url, args.disposition, args.insight, args.applies_to, args.evidence, args.smallest_test))
        elif args.cmd == "findings":
            emit(findings(args.limit))
        elif args.cmd == "mcp":
            serve()
    except urllib.error.HTTPError as e:
        die(f"{e.url}: HTTP {e.code} {'(rate limited: set GITHUB_TOKEN)' if e.code == 403 else ''}")
    except (urllib.error.URLError, ValueError, RuntimeError) as e:
        die(str(e))


def register(sub):
    p = sub.add_parser("scout", help="find open-source Mac mashup/mod projects and resolve shared posts; MCP server")
    p.description = __doc__
    cs = p.add_subparsers(dest="cmd", required=True)
    s = cs.add_parser("search", help="search GitHub, Hacker News, Reddit (X with X_BEARER_TOKEN)")
    s.add_argument("query")
    s.add_argument("--sources", default=",".join(SOURCES), help=f"comma list from {', '.join(SEARCHERS)}")
    s.add_argument("--limit", type=int, default=8)
    s.add_argument("--json", action="store_true")
    cs.add_parser("repo", help="assess one GitHub repo for Mac evidence and hooks").add_argument("repo")
    cs.add_parser("post", help="resolve an X/Twitter post or web page to its linked repos").add_argument("url")
    cs.add_parser("installed", help="is a game installed here (Steam, Epic, .app)").add_argument("match", nargs="?")
    lg = cs.add_parser("log", help="append a finding to the local ledger")
    lg.add_argument("--url", required=True)
    lg.add_argument("--disposition", required=True, choices=DISPOSITIONS)
    lg.add_argument("--insight", required=True)
    lg.add_argument("--applies-to", default="")
    lg.add_argument("--evidence", default="source claim")
    lg.add_argument("--smallest-test", default="")
    cs.add_parser("findings", help="show the ledger").add_argument("--limit", type=int, default=50)
    cs.add_parser("mcp", help="serve these tools to an agent over stdio (Model Context Protocol)")
    p.set_defaults(func=main)
