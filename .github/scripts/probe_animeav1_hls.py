#!/usr/bin/env python3
"""Check that the HLS the extension serves stays in sync when it is muxed with ffmpeg.

Reproduces the download command Anikku runs (AnimeDownloader.ffmpegDownload:
`-headers ... -i <m3u8> -map 0:v -map 0:a? -f matroska -c:a copy -c:v copy`) over a
slice of the real zilla playlist and measures the A/V drift of the resulting MKV.

The ffmpeg flags are read from the extension source, so dropping the fix from
AnimeAv1.kt makes this probe fail again.

Usage: probe_animeav1_hls.py [repo_root]
Exit codes: 0 = in sync, 1 = desync, 2 = could not run.
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile

N_SEGS = 10
TOLERANCE = 0.5  # seconds
PLAYLIST = "https://player.zilla-networks.com/m3u8/96effc6039536ef88cbf268d5b14eb4b"
CDN = "https://player.zilla-networks.com/segs/96effc6039536ef88cbf268d5b14eb4b"
HDRS = ["-H", "Sec-Fetch-Dest: empty", "-H", "Sec-Fetch-Mode: cors", "-H", "Sec-Fetch-Site: same-origin"]


def extension_flags(repo):
    """Read the ffmpeg flags the extension declares for the HLS video."""
    src_path = os.path.join(
        repo,
        "src/es/animeav1/src/eu/kanade/tachiyomi/animeextension/es/animeav1/AnimeAv1.kt",
    )
    src = open(src_path, encoding="utf-8").read()
    idx = src.find('"player.zilla"')
    if idx < 0:
        print("no se encuentra la rama player.zilla en AnimeAv1.kt")
        sys.exit(2)
    block = src[idx:idx + 2000]
    m = re.search(r"ffmpegVideoArgs\s*=\s*listOf\((.*?)\)", block, re.S)
    if not m:
        return []
    return re.findall(r'\(\s*"([^"]+)"\s+to\s+"([^"]*)"\s*\)', m.group(1))


def fetch(url, out):
    r = subprocess.run(["curl", "-s", "--max-time", "120", "-o", out, url, *HDRS], capture_output=True)
    return r.returncode == 0 and os.path.exists(out) and os.path.getsize(out) > 0


def packets(stream, path):
    raw = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", stream, "-show_entries", "packet=pts_time",
         "-of", "csv=p=0", path],
        capture_output=True, text=True,
    ).stdout
    out = []
    for x in raw.splitlines():
        if x and x != "N/A":
            try:
                out.append(float(x.split(",")[0]))
            except ValueError:
                pass
    return out


def main():
    repo = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("GITHUB_WORKSPACE", os.getcwd())
    flags = extension_flags(repo)
    print(f"flags ffmpeg declarados por la extension: {flags}")

    work = tempfile.mkdtemp(prefix="animeav1-hls-")
    try:
        segdir = os.path.join(work, "segs")
        os.makedirs(segdir)

        playlist = subprocess.run(["curl", "-s", "--max-time", "60", PLAYLIST, *HDRS],
                                  capture_output=True, text=True, check=True).stdout
        if not playlist.startswith("#EXTM3U"):
            print("no se pudo leer la playlist del CDN")
            return 2

        if not fetch(f"{CDN}/init.html", f"{segdir}/init.html"):
            print("no se pudo descargar el init segment")
            return 2
        shutil.copy(f"{segdir}/init.html", f"{segdir}/init.mp4")

        entries, n = [], 0
        for line in playlist.splitlines():
            if line.startswith("#EXT-X-MAP"):
                entries.append('#EXT-X-MAP:URI="file://' + os.path.join(segdir, "init.mp4") + '"')
            elif line.startswith("#EXTINF"):
                n += 1
                if n <= N_SEGS:
                    entries.append(line)
            elif line.startswith("https://"):
                if n > N_SEGS:
                    continue
                stem = line.rsplit("/", 1)[-1].rsplit(".", 1)[0]
                if not fetch(line, f"{segdir}/{stem}.html"):
                    print(f"no se pudo descargar el segmento {stem}")
                    return 2
                shutil.copy(f"{segdir}/{stem}.html", f"{segdir}/{stem}.mp4")
                entries.append("file://" + os.path.join(segdir, f"{stem}.mp4"))
            elif line.startswith("#"):
                entries.append(line)
        entries.append("#EXT-X-ENDLIST")

        m3u8 = os.path.join(work, "probe.m3u8")
        with open(m3u8, "w") as fh:
            fh.write("\n".join(entries) + "\n")

        out = os.path.join(work, "probe.mkv")
        cmd = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-protocol_whitelist", "file,crypto,data",
               "-i", m3u8, "-map", "0:v", "-map", "0:a?", "-f", "matroska", "-c:a", "copy", "-c:v", "copy"]
        for k, v in flags:
            cmd += [f"-{k}", v]
        cmd += [out, "-y"]
        r = subprocess.run(cmd, capture_output=True, text=True)
        if r.returncode != 0:
            print("ffmpeg fallo:", (r.stderr.strip().splitlines() or ["?"])[-1])
            return 2

        v, a = packets("v:0", out), packets("a:0", out)
        if not v or not a:
            print("el MKV no tiene ambos streams")
            return 2

        drift = a[0] - v[0]
        print(f"video empieza en {v[0]:.3f}s | audio empieza en {a[0]:.3f}s | desfase inicial = {drift:+.3f}s")
        if abs(drift) > TOLERANCE:
            print(f"FAIL: el audio va {abs(drift):.3f}s desfasado al principio (tolerancia {TOLERANCE}s)")
            return 1
        print(f"OK: audio y video en sincronia (desfase {drift:+.3f}s)")
        return 0
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
