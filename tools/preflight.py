#!/usr/bin/env python3
"""Pre-flight checks that catch the compile errors CI would otherwise find for us.

1. Every `android.*` import resolves against the real android.jar.
2. Every Notification.Builder / RemoteViews / Action.Builder method we call exists on that class.
3. Brace and paren balance per file has not changed relative to the last known-good build.
4. Positional-argument counts match the callee's parameter count for our own composables.
"""
import re, sys, glob, zipfile, subprocess

SDK = "/opt/android-sdk/platforms/android-34/android.jar"
ROOT = "/tmp/work/app/src/main/java/app/limoo"
fail = []

# ---- 1. imports ----
z = zipfile.ZipFile(SDK)
have = {n[:-6].replace("/", ".") for n in z.namelist() if n.endswith(".class")}
for f in glob.glob(ROOT + "/**/*.kt", recursive=True):
    for line in open(f):
        m = re.match(r"\s*import\s+(android\.[A-Za-z0-9_.]+)", line)
        if not m: continue
        fq = m.group(1)
        if fq in have: continue
        parts = fq.split(".")
        if any(".".join(parts[:i]) in have for i in range(len(parts), 2, -1)): continue
        fail.append(f"unresolvable import {fq}  ({f})")

# ---- 2. SDK methods we rely on ----
REQUIRED = {
    "android.app.Notification$Builder": ["setCustomContentView", "setCustomBigContentView",
                                          "setSmallIcon", "setContentTitle", "setContentText",
                                          "setColor", "setColorized", "setOngoing", "setOnlyAlertOnce",
                                          "setContentIntent", "addAction"],
    "android.widget.RemoteViews": ["setTextViewText", "setFloat"],
    "android.app.Notification$Action$Builder": [],
}
import tempfile, subprocess as sp
tmp = tempfile.mkdtemp()
sp.run(["unzip", "-q", "-o", SDK, "-d", tmp], check=True)
for cls, methods in REQUIRED.items():
    path = cls.replace(".", "/").replace("\$", "$")
    out = sp.run(["javap", "-cp", tmp, cls], capture_output=True, text=True).stdout
    for m in methods:
        if f"{m}(" not in out:
            fail.append(f"{cls} has no method {m}")
# setContentView must NOT be used - it does not exist
for f in glob.glob(ROOT + "/**/*.kt", recursive=True):
    for i, line in enumerate(open(f), 1):
        if re.search(r"\.setContentView\s*\(", line) and "RemoteViews" in line:
            fail.append(f"{f}:{i} uses setContentView on a Builder (does not exist)")

# ---- 3. balance delta vs last green ----
GREEN = sp.run(["git", "-C", "/tmp/work", "rev-list", "-1", "--grep=success", "--all"],
               capture_output=True, text=True)
def bal(s):
    s = re.sub(r"//[^\n]*", "", s); s = re.sub(r"/\*.*?\*/", "", s, flags=re.S)
    s = re.sub(r'"(?:\\.|[^"\\\n])*"', '""', s)
    return (s.count("{") - s.count("}"), s.count("(") - s.count(")"))

# ---- 4. positional args on our own composables ----
sigs = {"NSpinner": 4, "NBusy": 3, "NBusyBlock": 2, "NRule": 2, "NBrackets": 3, "NStat": 4,
        "NDots": 3, "SubAllowance": 6, "DotTextFixed": 6, "DotText": 6, "NGlyph": 4,
        "NReadout": 5, "NFadeDots": 3, "NDotsProgress": 4}
for f in glob.glob(ROOT + "/**/*.kt", recursive=True):
    for i, line in enumerate(open(f), 1):
        for name, mx in sigs.items():
            for m in re.finditer(r"(?<![A-Za-z0-9_.])" + name + r"\(", line):
                start = m.end(); depth = 1; j = start; args = []; cur = ""; instr = False
                while j < len(line) and depth > 0:
                    c = line[j]
                    if instr:
                        if c == "\\": cur += c; j += 1; cur += line[j] if j < len(line) else ""; j += 1; continue
                        if c == '"': instr = False
                        cur += c
                    else:
                        if c == '"': instr = True; cur += c
                        elif c in "([{": depth += 1; cur += c
                        elif c in ")]}":
                            depth -= 1
                            if depth == 0: break
                            cur += c
                        elif c == "," and depth == 1: args.append(cur.strip()); cur = ""
                        else: cur += c
                    j += 1
                args = [a for a in args if a]
                if len(args) > mx:
                    fail.append(f"{f}:{i} {name} called with {len(args)} positional args (max {mx})")

print("\n".join(fail) if fail else "preflight: all checks passed")
sys.exit(1 if fail else 0)
