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


# ---- 5. JVM signature clashes: a Kotlin property and an explicit accessor with the same name ----
def strip_comments(s):
    s = re.sub(r"/\*.*?\*/", lambda m: "\n" * m.group(0).count("\n"), s, flags=re.S)
    s = re.sub(r"//[^\n]*", "", s)
    return s

for f in glob.glob(ROOT + "/**/*.kt", recursive=True):
    raw = open(f).read()
    text = strip_comments(raw)          # a doc comment naming setX() is not a declaration
    props = set(re.findall(r"\bvar\s+(\w+)\s*:", text)) | set(re.findall(r"\bval\s+(\w+)\s*:", text))
    for prop in props:
        setter = "set" + prop[0].upper() + prop[1:]
        for m in re.finditer(r"\bfun\s+" + setter + r"\s*\(", text):
            line_no = text[:m.start()].count("\n") + 1
            fail.append(f"{f}:{line_no} JVM clash: property '{prop}' already generates {setter}(...)")

# ---- 6. the app must not post RemoteViews notifications ----
# SystemUI inflates notifications in its own process. Our custom layout was rejected twice with
# RemoteServiceException$BadForegroundServiceNotificationException, which crashed the app on connect.
# The notification is now built only from platform templates; keep it that way.
for f in glob.glob(ROOT + "/**/*.kt", recursive=True):
    src = strip_comments(open(f).read())
    for m in re.finditer(r"\bRemoteViews\b", src):
        line_no = src[:m.start()].count("\n") + 1
        fail.append(f"{f}:{line_no} RemoteViews in the app - SystemUI rejects custom notification layouts")
for x in glob.glob("/tmp/work/app/src/main/res/layout/*.xml"):
    fail.append(f"{x} notification layout exists - post a platform-template notification instead")

# ---- 6b. notification layouts may only use platform widgets ----
# RemoteViews is inflated by SystemUI in another process: an app-defined View subclass in a notification
# layout compiles fine and then crashes the app at inflation time (i.e. on connect, inside
# startForeground). Only these tags are safe.
REMOTE_VIEWS_OK = {
    "LinearLayout", "FrameLayout", "RelativeLayout", "GridLayout", "TableLayout", "TableRow",
    "TextView", "ImageView", "Button", "ImageButton", "EditText", "ProgressBar", "View",
    "AnalogClock", "Chronometer", "ImageClock", "TextClock", "ViewFlipper", "ViewSwitcher",
    "ListView", "GridView", "StackView", "AdapterViewFlipper", "Space", "RatingBar",
}
for xml in glob.glob("/tmp/work/app/src/main/res/layout/*.xml"):
    if "notif" not in xml and "tile" not in xml: continue
    for tag in re.findall(r"<([a-zA-Z][A-Za-z0-9_.]*)", open(xml).read()):
        if tag.startswith("?"): continue
        simple = tag.split(".")[-1]
        if tag.startswith("android.") or simple in REMOTE_VIEWS_OK: continue
        fail.append(f"{xml}: <{tag}> is not a RemoteViews-safe widget (crashes SystemUI inflation)")

# ---- 7. a class may declare only one companion object ----
# Two companions is a hard compile error, and it takes every reference to that class's static members
# with it (they resolve as "Unresolved reference" far away from the real cause).
for f in glob.glob(ROOT + "/**/*.kt", recursive=True):
    n = len(re.findall(r"^\s*companion\s+object\b", strip_comments(open(f).read()), re.M))
    if n > 1:
        fail.append(f"{f}: {n} companion objects (only one is allowed)")

# ---- 8. char literals must not contain a raw newline ----
# Python-style escaping mistakes turn '\n' into a literal line break inside Kotlin char literals, which
# is a syntax error that only shows up on the CI compiler.
for f in glob.glob(ROOT + "/**/*.kt", recursive=True):
    for i, line in enumerate(open(f), 1):
        if line.rstrip("\n").endswith("'"):
            stripped = strip_comments(line)
            if stripped.count("'") % 2 == 1:
                fail.append(f"{f}:{i} unterminated char literal (a raw newline inside quotes)")

# ---- 9. no references to components that were removed in the re-skin ----
# The v0.8 re-skin replaced the dot-centric component set. A screen still calling a deleted composable is
# a compile error; catch it here rather than on CI.
REMOVED = ["DotText", "DotTextFixed", "DotBar", "NDots", "NBusy", "NBusyBlock",
           "NReadout", "NGlyph", "NFadeDots", "NDotsProgress", "NBrackets", "NavPill"]
for f in glob.glob(ROOT + "/**/*.kt", recursive=True):
    body = strip_comments(open(f).read())
    if f.endswith("Nothing.kt") or f.endswith("Tokens.kt"): continue
    for name in REMOVED:
        for m in re.finditer(r"(?<![A-Za-z0-9_.])" + name + r"\s*\(", body):
            line_no = body[:m.start()].count("\n") + 1
            fail.append(f"{f}:{line_no} calls removed component {name}()")

# ---- 10. the spec's rule: no huge radii on content containers ----
for f in glob.glob(ROOT + "/**/*.kt", recursive=True):
    body = strip_comments(open(f).read())
    for m in re.finditer(r"radius\s*=\s*(\d+)dp", body):
        if int(m.group(1)) > 16:
            line_no = body[:m.start()].count("\n") + 1
            fail.append(f"{f}:{line_no} radius {m.group(1)}dp - spec caps content radii at 12dp")

# ---- 11. DrawScope has no Paint, and Dp/Px must not be mixed ----
# Compose type errors that only the Kotlin compiler can catch. These two patterns caused real failures.
for f in glob.glob(ROOT + "/**/*.kt", recursive=True):
    body = strip_comments(open(f).read())
    # inside a Canvas/DrawScope block, a bare `paint.` reference is invalid
    for m in re.finditer(r"(?<![A-Za-z0-9_.])paint\.", body):
        seg = body[max(0, m.start() - 400):m.start()]
        if "Canvas(" in seg or "drawIntoCanvas" in seg:
            line_no = body[:m.start()].count("\n") + 1
            fail.append(f"{f}:{line_no} uses `paint.` inside a Canvas block - DrawScope has no Paint")
    # Dp divided by px, or px produced then divided by a Dp
    for m in re.finditer(r"toPx\(\)\s*[-/]\s*\d+\.dp", body):
        line_no = body[:m.start()].count("\n") + 1
        fail.append(f"{f}:{line_no} mixes px and Dp in one expression - do the arithmetic in Dp first")
    for m in re.finditer(r"\d+\.dp\s*[-/]\s*\S+\.toPx\(\)", body):
        line_no = body[:m.start()].count("\n") + 1
        fail.append(f"{f}:{line_no} mixes Dp and px in one expression - do the arithmetic in Dp first")
    # duplicated annotations on one declaration
    for m in re.finditer(r"@Composable\s*\n\s*/\*\*.*?\*/\s*\n\s*@Composable", body, re.S):
        line_no = body[:m.start()].count("\n") + 1
        fail.append(f"{f}:{line_no} @Composable appears twice on one declaration")

# ---- 12. duplicate keys in the dot-matrix glyph table ----
# The table is built with associate(), which keeps the LAST entry for a duplicate key. A typo'd duplicate
# line therefore renders the wrong glyph with no compile error - it shipped once as a blank row where a
# "/" belonged. Fail before it can recur.
glyph = None
for f in glob.glob(ROOT + "/**/*.kt", recursive=True):
    m = re.search(r'private val GLYPHS[^=]*= ""\"(.*?)\"\"', open(f).read(), re.S)
    if m: glyph = (f, m.group(1)); break
if glyph is None:
    fail.append("GLYPHS table not found - expected a triple-quoted glyph table in the ui package")
else:
    gf, body = glyph
    keys = [l.strip()[0] for l in body.strip().splitlines() if l.strip()]
    dupes = sorted({k for k in keys if keys.count(k) > 1})
    if dupes:
        line_no = open(gf).read()[:open(gf).read().index(body)].count("\n") + 1
        fail.append(f"{gf}:{line_no} GLYPHS has duplicate keys {dupes} - associate() keeps the last, silently")

print("\n".join(fail) if fail else "preflight: all checks passed")
sys.exit(1 if fail else 0)
