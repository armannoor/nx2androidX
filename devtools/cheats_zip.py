#!/usr/bin/env python3
"""Cut a package of the cheat files: the player imports it into the app (Settings → Environment → Cheats).

The app does not carry the cheats: they are the community's Atmosphère files, one per build, and they change
when the community changes them. The package holds the repository's cheat folders (cheats/<title ID>/) and a
manifest that says what it is.

    python3 devtools/cheats_zip.py --repo <path of the cheat repository>
    python3 devtools/cheats_zip.py --repo <path> --out <file>

The package is reproducible: the same files give the same bytes, so the digest of the file is the digest of
its content. The entries carry a fixed timestamp and a fixed mode, and the manifest inside says what the
cheats are (the commit they were taken from), not when the package was cut.
"""
import argparse, hashlib, json, re, time, zipfile
from pathlib import Path

ID = re.compile(r'^[0-9A-F]{16}$')


def cut(repo: Path, out: Path, commit: str):
    """Writes the package: the cheat folders and the manifest that says what it is"""
    cheats = repo / 'cheats'
    if not cheats.is_dir():
        raise SystemExit(f'{cheats} is not there')
    ids = sorted(d.name for d in cheats.iterdir() if d.is_dir() and ID.match(d.name))
    if not ids:
        raise SystemExit(f'{cheats} holds no cheat file')
    manifest = {'commit': commit, 'count': len(ids), 'ids': ids}
    out.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as zf:
        _write(zf, 'manifest.json', json.dumps(manifest, indent=2) + '\n')
        for i in ids:
            for p in sorted((cheats / i).rglob('*')):
                if p.is_file():
                    _add(zf, f'cheats/{i}/{p.relative_to(cheats / i).as_posix()}', p)
    digest = hashlib.sha256(out.read_bytes()).hexdigest()
    return dict(manifest, date=time.strftime('%Y-%m-%d'), name=out.name, sha256=digest, size=out.stat().st_size), out


def _entry(name: str):
    """One entry of the package: a fixed timestamp and a fixed mode, so the package is reproducible"""
    info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o644 << 16
    return info


def _write(zf: zipfile.ZipFile, name: str, data: bytes):
    zf.writestr(_entry(name), data)


def _add(zf: zipfile.ZipFile, name: str, path: Path):
    with zf.open(_entry(name), 'w') as dst, open(path, 'rb') as src:
        dst.write(src.read())


def commit_of(repo: Path) -> str:
    """The commit the files were taken from (the manifest says it)"""
    head = repo / '.git' / 'HEAD'
    if head.is_file():
        ref = head.read_text().strip()
        if ref.startswith('ref: '):
            p = repo / '.git' / ref[5:]
            if p.is_file():
                return p.read_text().strip()
        return ref
    return ''


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--repo', required=True, help='the cheat repository')
    ap.add_argument('--out', default='', help='the package to write (default: <out dir>/nx2a-cheats-<commit>.zip)')
    ap.add_argument('--out-dir', default='dist', help='where the package goes when --out is not given')
    a = ap.parse_args()
    repo = Path(a.repo).resolve()
    commit = commit_of(repo) or 'unknown'
    out = Path(a.out) if a.out else Path(a.out_dir) / f'nx2a-cheats-{commit[:8]}.zip'
    manifest, _ = cut(repo, out, commit)
    out.with_suffix('.json').write_text(json.dumps(manifest, indent=2) + '\n', encoding='utf-8')
    print(f'{out}\n{manifest["count"]} game(s), {commit[:8]}, {manifest["size"] / 2**20:.1f} MB, sha256 {manifest["sha256"][:16]}')


if __name__ == '__main__':
    main()
