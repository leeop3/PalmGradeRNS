#!/usr/bin/env python3
"""
office_receiver.py
==================
PalmGrade RNS — Office WiFi Upload Receiver

Run this on the office PC/server at end of shift.
The Android app's Export tab sends a multipart/form-data POST with the
daily ZIP file to http://<server_ip>:8080/upload.

Requirements:
    pip install flask

Usage:
    python3 office_receiver.py [--port 8080] [--output ./received]

The server will:
  1. Accept the ZIP file upload
  2. Save it to --output directory with a timestamp prefix
  3. Extract and validate the manifest.json (checksum verification)
  4. Print a per-record summary to stdout
  5. Return HTTP 200 with a JSON result to the Android app

Run with --help for all options.
"""

import argparse
import hashlib
import json
import os
import sys
import zipfile
from datetime import datetime
from pathlib import Path

try:
    from flask import Flask, request, jsonify
except ImportError:
    print("ERROR: Flask not installed. Run:  pip install flask")
    sys.exit(1)

app = Flask(__name__)
OUTPUT_DIR = Path("./received")


# ─────────────────────────────────────────────────────────────────────────────
# Upload endpoint
# ─────────────────────────────────────────────────────────────────────────────

@app.route("/upload", methods=["POST"])
def upload():
    if "file" not in request.files:
        return jsonify({"status": "error", "message": "No file in request"}), 400

    upload_file = request.files["file"]
    if not upload_file.filename.endswith(".zip"):
        return jsonify({"status": "error", "message": "Expected a .zip file"}), 400

    # Save to disk with timestamp prefix to avoid overwrites
    ts    = datetime.now().strftime("%Y%m%d_%H%M%S")
    fname = f"{ts}_{upload_file.filename}"
    dest  = OUTPUT_DIR / fname
    upload_file.save(dest)

    file_size_kb = dest.stat().st_size // 1024
    print(f"\n{'='*60}")
    print(f"  Received: {fname}  ({file_size_kb} KB)")
    print(f"  Saved to: {dest}")

    # Validate and summarise
    try:
        result = validate_zip(dest)
        print(f"  Records : {result['record_count']}")
        print(f"  Photos  : {result['photo_count']}")
        print(f"  Date    : {result['date']}")
        print(f"  Harvester RNS: {result['harvester_rns']}")
        if result["checksum_errors"]:
            print(f"  ⚠  Checksum errors: {result['checksum_errors']}")
        else:
            print("  ✓ All checksums verified")
        print(f"{'='*60}\n")

        return jsonify({
            "status":           "ok",
            "filename":         fname,
            "size_kb":          file_size_kb,
            "record_count":     result["record_count"],
            "photo_count":      result["photo_count"],
            "checksum_errors":  result["checksum_errors"],
        }), 200

    except Exception as e:
        print(f"  ⚠  Validation error: {e}")
        return jsonify({
            "status":  "ok_with_warnings",
            "filename": fname,
            "warning": str(e),
        }), 200


# ─────────────────────────────────────────────────────────────────────────────
# Validation helpers
# ─────────────────────────────────────────────────────────────────────────────

def validate_zip(zip_path: Path) -> dict:
    """
    Open the ZIP, parse manifest.json, verify SHA-256 checksums.
    Returns a summary dict.
    """
    errors = []

    with zipfile.ZipFile(zip_path, "r") as zf:
        names = zf.namelist()

        # ── manifest.json ──
        if "manifest.json" not in names:
            raise ValueError("manifest.json missing from ZIP")

        manifest = json.loads(zf.read("manifest.json").decode("utf-8"))

        date          = manifest.get("date", "?")
        record_count  = manifest.get("record_count", 0)
        photo_count   = manifest.get("photo_count", 0)
        harvester_rns = manifest.get("harvester_rns", "?")
        records       = manifest.get("records", [])

        # ── Per-record checksum verification ──
        for rec in records:
            uuid       = rec.get("uuid", "?")
            photo_file = rec.get("photo_file", "")
            photo_sha  = rec.get("photo_sha256", "")

            if photo_file and photo_file in names:
                actual = hashlib.sha256(zf.read(photo_file)).hexdigest()
                if actual != photo_sha:
                    errors.append(
                        f"Photo checksum mismatch for {uuid[:8]}: "
                        f"expected {photo_sha[:8]}… got {actual[:8]}…"
                    )
            elif photo_file:
                errors.append(f"Photo missing for {uuid[:8]}: {photo_file}")

        # ── Print CSV preview ──
        csv_candidates = [n for n in names if n.endswith(".csv")]
        for csv_name in csv_candidates:
            lines = zf.read(csv_name).decode("utf-8").splitlines()
            print(f"\n  CSV preview ({csv_name}):")
            for line in lines[:6]:
                print(f"    {line}")
            if len(lines) > 6:
                print(f"    … ({len(lines) - 1} data rows total)")

        # ── Extract to subfolder ──
        subfolder = zip_path.parent / zip_path.stem
        subfolder.mkdir(exist_ok=True)
        zf.extractall(subfolder)
        print(f"  Extracted to: {subfolder}")

    return {
        "date":             date,
        "record_count":     record_count,
        "photo_count":      photo_count,
        "harvester_rns":    harvester_rns,
        "checksum_errors":  errors,
    }


# ─────────────────────────────────────────────────────────────────────────────
# Entry point
# ─────────────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description="PalmGrade RNS — Office WiFi receiver")
    parser.add_argument("--port",   type=int, default=8080,
                        help="TCP port to listen on (default: 8080)")
    parser.add_argument("--host",   default="0.0.0.0",
                        help="Bind address (default: 0.0.0.0 = all interfaces)")
    parser.add_argument("--output", default="./received",
                        help="Directory to save uploads (default: ./received)")
    args = parser.parse_args()

    global OUTPUT_DIR
    OUTPUT_DIR = Path(args.output)
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)

    print("╔══════════════════════════════════════════╗")
    print("║   PalmGrade RNS — Office Upload Server   ║")
    print("╚══════════════════════════════════════════╝")
    print(f"  Listening : http://{args.host}:{args.port}/upload")
    print(f"  Output dir: {OUTPUT_DIR.resolve()}")
    print(f"  Started   : {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print("\n  Waiting for uploads from field devices…\n")

    app.run(host=args.host, port=args.port, debug=False)


if __name__ == "__main__":
    main()
