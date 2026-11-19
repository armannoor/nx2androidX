#!/usr/bin/env python3
"""Repack an APK without the slack: the debug APK is mostly empty space.

The Android build (AGP's zipflinger) leaves a big hole in the debug APK so that the next build can write into
it (that is what makes Android Studio's "Apply Changes" fast). The hole is hundreds of megabytes: the APK's
content is a few tens of megabytes, the file is ten times that. Installing it over adb sends all of it, which
is slow for nothing.

This writes the same APK without the hole: the entries as they are, nothing between them. The result is signed
by apksigner afterwards (the signatures live in the zip's tail, so they cannot survive the rewrite).

    python3 devtools/apk_repack.py <in.apk> <out.apk>
"""
import sys, zipfile


def repack(src: str, dst: str):
    """Every entry of the archive, in the same order, without the space between them"""
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as zout:
        for info in zin.infolist():
            data = zin.read(info.filename)
            # the directories stay directories (an empty entry)
            out = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            out.compress_type = zipfile.ZIP_STORED if info.is_dir() else zipfile.ZIP_DEFLATED
            out.external_attr = info.external_attr
            out.create_system = info.create_system
            zout.writestr(out, data)
    return dst


def main():
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    print(repack(sys.argv[1], sys.argv[2]))


if __name__ == '__main__':
    main()
