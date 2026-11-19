#!/usr/bin/env python3
"""Pull the cheat files of a build from the community libraries into the cheat repository.

The repository holds what the app imports: the community's Atmosphère files, one per build, with the credit
beside them. This script fetches one build from the community libraries (the first one that has it), leaves the
resolution patches out (the app has its own render scale) and writes the file into the repository.

    python3 devtools/cheats_pull.py --repo <path> 01004AB00A260000:DF3766A2BB651A3E
    python3 devtools/cheats_pull.py --repo <path> --list

The libraries (in the order they are tried):
  - switch-cheats-db (the aggregator): one JSON per game, one section per build
  - MyNXCheats (one author's collection): the files are already in the Atmosphère format, the folders are named
    by the game, so the repository's file tree says which file belongs to which game and build
  - vgtxc/switch-cheat (the Chinese community's collection, the one the NSboy and tekqart forums share)

The first one that has a usable file wins; nothing is merged across them (two files that patch the same place
would fight).
"""
import argparse, json, re, sys, urllib.request
from pathlib import Path

EDGES = ("cdn.jsdelivr.net", "fastly.jsdelivr.net", "gcore.jsdelivr.net")
DB = "HamletDuFromage/switch-cheats-db"
MYNX = ("Arch9SK7", "MyNXCheats")
VGTXC = ("vgtxc", "switch-cheat")

# the resolution patches: the app has its own render scale, the two would fight
RESOLUTION = re.compile(r'\b\d{3,4}\s*p\b|downscale|upscale|resolution', re.I)

TITLE = re.compile(r'^[0-9A-Fa-f]{16}$')
BUILD = re.compile(r'^[0-9A-Fa-f]{16}$')
HEADER = re.compile(r'^[\[{]([^\]}]*)[\]}]')


def fetch(url: str) -> str:
    """One URL's body: the first edge that answers (one network is often blocked)"""
    last = None
    missing = False
    for edge in EDGES:
        try:
            return fetch_one(url.replace('https://cdn.jsdelivr.net', f'https://{edge}'))
        except FileNotFoundError:
            missing = True          # the library has no such file: the answer, not a failure
        except Exception as e:      # the next edge
            last = e
    if missing:
        # one edge answered "no such file": that is the answer (the edges serve the same content)
        raise FileNotFoundError(url)
    raise SystemExit(f'cannot fetch {url}: {last}')


def fetch_one(url: str) -> str:
    req = urllib.request.Request(url, headers={'User-Agent': 'nx2android'})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.read().decode('utf-8', 'replace')
    except urllib.error.HTTPError as e:
        if e.code == 404:
            raise FileNotFoundError(url)
        raise


def from_db(tid: str, build: str):
    """The aggregator: the build's section of the game's JSON"""
    url = f'https://cdn.jsdelivr.net/gh/{DB}@master/cheats/{tid.upper()}.json'
    try:
        data = json.loads(fetch(url))
    except FileNotFoundError:
        return None
    section = None
    for k, v in data.items():
        if k.upper() == build.upper() and isinstance(v, dict):
            section = v
            break
    if section is None:
        return None
    text = ''.join(body if body.endswith('\n') else body + '\n' for body in section.values())
    credit = ''
    attribution = data.get('attribution')
    if isinstance(attribution, dict):
        lines = [str(v).strip().splitlines()[0].strip() for v in attribution.values() if str(v).strip()]
        credit = ' · '.join(x for x in lines if x)
    return text, f'switch-cheats-db{(" · " + credit) if credit else ""}'


def from_mynx(tid: str, build: str):
    """One author's collection: the file tree says which file belongs to which game and build"""
    owner, repo = MYNX
    tree_url = f'https://data.jsdelivr.com/v1/packages/gh/{owner}/{repo}@master?structure=flat'
    try:
        tree = json.loads(fetch(tree_url))
    except (SystemExit, FileNotFoundError):
        return None
    want = f'/{tid.upper()}/cheats/{build.upper()}.txt'
    for f in tree.get('files', []):
        name = f.get('name', '')
        if name.lower().endswith(want.lower()):
            path = '/'.join(seg.replace(' ', '%20') for seg in name.split('/'))
            url = f'https://cdn.jsdelivr.net/gh/{owner}/{repo}@master{path}'
            return fetch(url), 'MyNXCheats'
    return None


def from_vgtxc(tid: str, build: str):
    """The Chinese community's collection: the path is fixed, no file tree needed"""
    owner, repo = VGTXC
    url = f'https://cdn.jsdelivr.net/gh/{owner}/{repo}@master/cheat/ns-cheat/{tid.upper()}/cheats/{build.upper()}.txt'
    try:
        return fetch(url), 'vgtxc/switch-cheat（NSboy/tekqart 中文社区）'
    except (SystemExit, FileNotFoundError):
        return None


SOURCES = (from_db, from_mynx, from_vgtxc)


def entries(text: str):
    """The file's blocks: the header's name and the text that follows it"""
    out = []
    name, start = None, 0
    offset = 0
    for line in text.split('\n'):
        m = HEADER.match(line.lstrip())
        if m:
            if name is not None:
                out.append((name, text[start:offset]))
            name, start = m.group(1), offset
        offset += len(line) + 1
    if name is not None:
        out.append((name, text[start:]))
    return out


def filter_resolution(text: str):
    """The file without the resolution patches; None when nothing is left"""
    kept = [(n, b) for n, b in entries(text) if not RESOLUTION.search(n)]
    if not kept:
        return None
    return ''.join(b for _, b in kept)


def pull(tid: str, build: str):
    """The file of the build from the first library that has a usable one"""
    for source in SOURCES:
        got = source(tid, build)
        if not got:
            continue
        text, credit = got
        kept = filter_resolution(text)
        if kept is None:
            continue
        return kept, credit
    return None


def write(repo: Path, tid: str, build: str, text: str, credit: str):
    """The file and its credit into the repository"""
    folder = repo / 'cheats' / tid.upper()
    folder.mkdir(parents=True, exist_ok=True)
    out = folder / f'{build.upper()}.txt'
    out.write_text(text, encoding='utf-8')
    (folder / f'{build.upper()}.credit').write_text(credit + '\n', encoding='utf-8')
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--repo', default='', help='the cheat repository (the files go into its cheats/ folder)')
    ap.add_argument('--list', action='store_true', help='what the libraries have for these builds, and nothing else')
    ap.add_argument('builds', nargs='*', help='<title ID>:<build ID> pairs')
    a = ap.parse_args()
    if not a.builds:
        raise SystemExit('give <title ID>:<build ID> pairs, or --list')
    repo = Path(a.repo) if a.repo else None
    for pair in a.builds:
        tid, _, build = pair.partition(':')
        if not BUILD.match(build):
            raise SystemExit(f'{build} is not a build ID (16 hex digits)')
        if not TITLE.match(tid):
            raise SystemExit(f'{tid} is not a title ID (16 hex digits)')
        got = pull(tid.upper(), build.upper())
        if not got:
            print(f'{tid.upper()} {build.upper()}: no library has it')
            continue
        text, credit = got
        if a.list:
            names = [n for n, _ in entries(text)]
            print(f'{tid.upper()} {build.upper()}: {len(names)} entries from {credit}')
            continue
        if not repo:
            raise SystemExit('give --repo (the files go somewhere)')
        out = write(repo, tid, build, text, credit)
        print(f'{out} ({len(text.encode("utf-8"))} bytes) from {credit}')


if __name__ == '__main__':
    main()
