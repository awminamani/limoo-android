#!/usr/bin/env python3
"""Pre-flight checks that catch the compile errors CI would otherwise find for us.

1. Every `android.*` import resolves against the real android.jar.
2. Every Notification.Builder / RemoteViews / Action.Builder method we call exists on that class.
3. Brace and paren balance per file has not changed relative to the last known-good build.
4. Positional-argument counts match the callee's parameter count for our own composables.
"""
import os, re, sys, glob, zipfile, subprocess

REPO = os.environ.get("LIMOO_REPO", os.getcwd())
SDK = os.path.join(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", "/opt/android-sdk")), "platforms/android-34/android.jar")
ROOT = os.path.join(REPO, "app/src/main/java/app/limoo")
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
    path = cls.replace(".", "/")
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
GREEN = sp.run(["git", "-C", REPO, "rev-list", "-1", "--grep=success", "--all"],
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
for x in glob.glob(os.path.join(REPO, "app/src/main/res/layout/*.xml")):
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
for xml in glob.glob(os.path.join(REPO, "app/src/main/res/layout/*.xml")):
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

# ---- 13. DotReadout must not take a fixed height ----
# A 5x7 glyph needs exactly 7 * pitch of height. Passing a hard-coded height clipped the bottom rows of
# every character, which is what made the speed figures look garbled. The height is derived internally now.
for f in glob.glob(ROOT + "/**/*.kt", recursive=True):
    body = strip_comments(open(f).read())
    for m in re.finditer(r"DotReadout\s*\(([^)]*)\)", body, re.S):
        args = m.group(1)
        # three positional args means (text, width, height)
        depth = 0; parts = []; cur = ""; instr = False
        for ch in args:
            if instr:
                cur += ch
                if ch == '"': instr = False
                continue
            if ch == '"': instr = True; cur += ch
            elif ch in "([{": depth += 1; cur += ch
            elif ch in ")]}": depth -= 1; cur += ch
            elif ch == "," and depth == 0: parts.append(cur.strip()); cur = ""
            else: cur += ch
        parts.append(cur.strip())
        pos = [a for a in parts if a and "=" not in a]
        if len(pos) >= 3:
            line_no = body[:m.start()].count("\n") + 1
            fail.append(f"{f}:{line_no} DotReadout given a fixed height ({pos[2]}) - the height clips the glyph")

# ---- 14. fully-qualified java.* in .kts scripts ----
# Inside a Gradle Kotlin DSL script `java` resolves to Gradle's `java` extension, not the java package, so
# `java.util.Properties()` there fails to resolve. Import it instead. (Cost a CI round-trip once.)
for kts in glob.glob(os.path.join(REPO, "**/*.gradle.kts"), recursive=True):
    src = open(kts).read()
    for m in re.finditer(r"(?<![A-Za-z0-9_.])java\.(util|net|nio|io)\.", src):
        line_no = src[:m.start()].count("\n") + 1
        cls = src[m.end():].split("(")[0].strip(".")
        imported = f"import java.{m.group(1)}.{cls.split('.')[0]}" in src
        if not imported:
            fail.append(f"{kts}:{line_no} fully-qualified java.{m.group(1)}.{cls.split('.')[0]} in a .kts script - import it")

# ---- 14b. wallpaper assets present, and the mask must keep its alpha ----
# The mask's alpha channel IS the accent-selection mechanism. A flattened or palette-quantised mask would
# silently tint the whole frame instead of only the intended details.
import glob as _g
_wp = os.path.join(REPO, "app/src/main/res/drawable-nodpi")
for _n in ("limoo_wallpaper_base.png", "limoo_accent_mask.png"):
    if not os.path.exists(f"{_wp}/{_n}"):
        fail.append(f"missing wallpaper asset {_n} in res/drawable-nodpi")
if os.path.exists(f"{_wp}/limoo_accent_mask.png"):
    try:
        from PIL import Image as _I
        _im = _I.open(f"{_wp}/limoo_accent_mask.png")
        if _im.mode not in ("RGBA", "LA"):
            fail.append(f"limoo_accent_mask.png is {_im.mode}, not RGBA - its alpha selects which details take the accent")
    except ImportError:
        pass

# ---- 14c. swipe rows must use SwipeActionRow, not the dismiss box ----
# The One UI swipe replaced SwipeToDismissBox, which flicks the row away and cannot be recovered from.
_sv = os.path.join(ROOT, "ui/Servers.kt")
if os.path.exists(_sv):
    _body = strip_comments(open(_sv).read())
    if "SwipeToDismiss" in _body:
        fail.append(f"{_sv}: SwipeToDismissBox is gone - use SwipeActionRow (One UI resistance + snap)")
    if "SwipeActionRow" not in _body:
        fail.append(f"{_sv}: server rows should use SwipeActionRow")
    # Share replaced Favourite on swipe, so the share sheet must offer the raw vless:// link too.
    _sh = os.path.join(ROOT, "ui/Sheets.kt")
    if os.path.exists(_sh) and "copyStandardLinks" not in strip_comments(open(_sh).read()):
        fail.append(f"{_sh}: share sheet should offer the standard vless:// link (copyStandardLinks)")

# ---- 14d. thin wrappers must forward every named parameter their callers use ----
# WallpaperLayers forgot to forward `scrim`, and the build only failed at the call site. Compare the
# wrapper's declared parameters against the named arguments used at its call sites.
# Only single-expression wrappers are considered (a real body may legitimately drop a parameter).
import re as _re


def _strip_strings(s):
    """Blank out string literals so their contents cannot be mistaken for call sites."""
    s = _re.sub(r'"""(?:.|\n)*?"""', lambda m: "\n" * m.group(0).count("\n"), s)
    s = _re.sub(r'(?<!\\)"(?:\\.|[^"\\\n])*"', '""', s)
    s = _re.sub(r"'(?:\\.|[^'\\\n])'", "''", s)
    return s


_kt = {f: strip_comments(_strip_strings(open(f).read())) for f in _g.glob(os.path.join(ROOT, "**/*.kt"), recursive=True)}
for _f, _b in _kt.items():
    for _w in _re.finditer(r"fun\s+(\w+)\s*\(([^)]*)\)\s*=\s*(\w+)\s*\(([^)]*)\)\s*$", _b, _re.M):
        _name, _params = _w.group(1), _w.group(2)
        if "Modifier" not in _params:
            continue
        _decl = set(_re.findall(r"(\w+)\s*:", _params))
        for _u, _ub in _kt.items():
            for _c in _re.finditer(r"(?<![A-Za-z0-9_.])" + _name + r"\(", _ub):
                if _u == _f and _c.start() == _w.start():
                    continue
                # take just this call's argument list, matching nested parens
                _d, _args, _i = 1, [], _c.end()
                while _i < len(_ub) and _d > 0:
                    _ch = _ub[_i]
                    if _ch == "(":
                        _d += 1
                    elif _ch == ")":
                        _d -= 1
                        if _d == 0:
                            break
                    _args.append(_ch)
                    _i += 1
                _arg = "".join(_args)
                # A named argument's value may itself contain `x = ...` (e.g. `scrim = if (a == 0) 0.1f else 0.4f`),
                # and the wrapper's own body ends in `= Target(...)`. Only take `name =` at the top level of
                # the argument list, i.e. at paren/bracket depth 0 relative to the call.
                _named_args = []
                _d2 = 0
                _cur = ""
                for _ch in _arg:
                    if _ch in "([{":
                        _d2 += 1
                    elif _ch in ")]}":
                        _d2 -= 1
                    if _ch == "," and _d2 == 0:
                        _named_args.append(_cur)
                        _cur = ""
                    else:
                        _cur += _ch
                _named_args.append(_cur)
                for _one in _named_args:
                    _m = _re.match(r"\s*(\w+)\s*=(?!=)", _one)
                    if not _m:
                        continue
                    _named = _m.group(1)
                    if _named == "Modifier":
                        continue
                    if _named not in _decl:
                        line_no = _ub[:_c.start()].count("\n") + 1
                        fail.append(f"{_f}: {_name}() does not declare '{_named}' but it is passed at {_u}:{line_no}")

# ---- 14e. no bare `Modifier.clickable {` inside a `.then(...)` ----
# A trailing lambda on Modifier.clickable inside .then(...) gives the compiler no overload to pick from and
# it fails with "None of the following candidates is applicable". Elsewhere `.clickable { }` is perfectly
# valid, so only flag it where it is actually ambiguous.
for _f in _g.glob(os.path.join(ROOT, "**/*.kt"), recursive=True):
    _b = strip_comments(open(_f).read())
    for _m in re.finditer(r"\.then\(", _b):
        # take this .then( call's argument text
        _d, _args, _i = 1, [], _m.end()
        while _i < len(_b) and _d > 0:
            _c = _b[_i]
            if _c == "(":
                _d += 1
            elif _c == ")":
                _d -= 1
                if _d == 0:
                    break
            _args.append(_c)
            _i += 1
        _a = "".join(_args)
        if re.search(r"\.clickable\s*\{", _a):
            line_no = _b[:_m.start()].count("\n") + 1
            fail.append(f"{_f}:{line_no} `.clickable {{` inside .then(...) needs parentheses - write .clickable(...) {{ }}")

# ---- 14f. no raw pixel values fed to Dp-only modifier parameters ----
# onSizeChanged gives Int pixels, but Modifier.width/height/size/offset take Dp. Passing the pixel value
# straight through fails with "None of the following candidates is applicable", and the caret points at the
# wrong line, which is easy to misread as a different function being wrong.
for _f in _g.glob(os.path.join(ROOT, "**/*.kt"), recursive=True):
    _b = strip_comments(open(_f).read())
    # names that conventionally hold pixel measurements in this codebase
    for _m in re.finditer(r"(?:val|var)\s+(\w*(?:[Pp]x|[Ww]idthPx|[Hh]eightPx)\w*)\s*(:[^=\n]+)?=", _b):
        _var = _m.group(1)
        for _w in re.finditer(r"\.(width|height|size|offset)\s*\(", _b[_m.end():]):
            # balance the parens to take exactly this call's argument list (they nest arbitrarily deep)
            _d, _args, _i = 1, [], _w.end()
            while _i < len(_b) - _m.end() and _d > 0:
                _c = _b[_m.end() + _i]
                if _c == "(":
                    _d += 1
                elif _c == ")":
                    _d -= 1
                    if _d == 0:
                        break
                _args.append(_c)
                _i += 1
            _expr = "".join(_args)
            if _var in _expr and ".toDp()" not in _expr and "px" not in _expr:
                line_no = _b[:_m.end() + _w.start()].count("\n") + 1
                fail.append(
                    f"{_f}:{line_no} '{_var}' looks like pixels but is passed to ."
                    f"{_w.group(1)}(...) which needs Dp - add .toDp()"
                )

# ---- 15. Glance symbols: verify against the known-good widget's import set ----
# Glance's API is easy to guess wrong (ColorFilter lives in androidx.glance, defaultWeight in
# .layout, provideGlance is a suspend override). Diff our imports against a known-compiling baseline.
W = os.path.join(ROOT, "widget/LimooWidget.kt")
if os.path.exists(W):
    have = set(re.findall(r"^import (androidx\.glance[\w.]*)", open(W).read(), re.M))
    # Required by anything the widget composes: Image+ColorFilter, modifier size/padding/fill, Row/Column,
    # text, colour providers, and the appwidget entry points.
    required = {
        "androidx.glance.GlanceId", "androidx.glance.GlanceModifier",
        "androidx.glance.Image", "androidx.glance.ImageProvider", "androidx.glance.background",
        "androidx.glance.appwidget.GlanceAppWidget", "androidx.glance.appwidget.GlanceAppWidgetReceiver",
        "androidx.glance.appwidget.action.ActionCallback", "androidx.glance.appwidget.action.actionRunCallback",
        "androidx.glance.appwidget.provideContent", "androidx.glance.appwidget.updateAll",
        "androidx.glance.action.ActionParameters", "androidx.glance.action.clickable",
        "androidx.glance.layout.Alignment", "androidx.glance.layout.Box", "androidx.glance.layout.Column",
        "androidx.glance.layout.Row", "androidx.glance.layout.Spacer", "androidx.glance.layout.fillMaxSize",
        "androidx.glance.layout.padding", "androidx.glance.layout.size", "androidx.glance.layout.width",
        "androidx.glance.text.FontFamily", "androidx.glance.text.Text", "androidx.glance.text.TextStyle",
        "androidx.glance.unit.ColorProvider",
    }
    for miss in sorted(required - have):
        fail.append(f"{W}: missing Glance import {miss}")
    # provideGlance must be a suspend override of GlanceAppWidget
    if "override suspend fun provideGlance" not in open(W).read():
        fail.append(f"{W}: provideGlance must be `override suspend fun provideGlance(context, id)`")

    # Every androidx.glance.* import must exist as a class in the real glance AAR. Guessing the package
    # is the single most common Glance mistake (defaultWeight is a RowScope member, ColorFilter is in
    # androidx.glance not .color), and it is invisible without a compiler.
    # glance and glance-appwidget are separate artifacts and the widget needs classes from both. They
    # live outside the repo, so this check is skipped when they are not present (e.g. on CI).
    jars = [j for j in ("/tmp/gx/classes.jar", "/tmp/gawx/classes.jar") if os.path.exists(j)]
    if jars:
        import zipfile as _z
        names = set()
        for _j in jars:
            with _z.ZipFile(_j) as _zz:
                for n in _zz.namelist():
                    if n.endswith(".class"):
                        names.add(n[:-6].replace("/", "."))
        # A top-level Kotlin extension (size, fillMaxWidth, Row, background, provideContent, ...) is
        # compiled into a file facade class like SizeModifiersKt, and its IMPORT name is the function name,
        # not the class name. So resolve an import by searching every facade's declared members.
        facades = {}
        for n in names:
            if not n.endswith("Kt"):
                continue
            simple = n.rsplit(".", 1)[1][:-2]
            facades.setdefault(n.rsplit(".", 1)[0], set())
        member_of = {}
        for _j in jars:
            with _z.ZipFile(_j) as _zz2:
                for n in _zz2.namelist():
                    if not n.endswith("Kt.class"):
                        continue
                    try:
                        data = _zz2.read(n)
                    except KeyError:
                        continue
                    pkg = n.rsplit("/", 1)[0].replace("/", ".")
                    # The constant pool holds every referenced name; enough to spot the function names.
                    for tok in re.findall(rb"[A-Za-z_][A-Za-z0-9_]{2,40}", data):
                        member_of.setdefault(pkg, set()).add(tok.decode("latin-1"))
                    # Also record the bare filename (e.g. RunCallbackActionKt -> actionRunCallback callers
                    # live in the same package), so a subpackage like .appwidget.action resolves too.
                    member_of.setdefault(pkg.rsplit(".", 1)[0] if "." in pkg else pkg, set()).update(
                        {n.rsplit("/", 1)[1][:-6]}
                    )
        for imp in sorted(have):
            pkg, last = imp.rsplit(".", 1)
            direct = imp in names or any(x.startswith(imp + "$") for x in names)
            if direct:
                continue
            if last in member_of.get(pkg, set()):
                continue
            # defaultWeight is a RowScope member, not a top-level function.
            if last in ("defaultWeight",):
                continue
            fail.append(f"{W}: import {imp} matches no class or extension function in glance 1.1.0")
        # TextStyle collides with java.time.format.TextStyle - one of them must be aliased.
        body = open(W).read()
        if "java.time.format.TextStyle" in body and "import java.time.format.TextStyle as" not in body:
            fail.append(f"{W}: java.time.format.TextStyle clashes with androidx.glance.text.TextStyle - alias it")
        # defaultWeight is a RowScope/ColumnScope extension on GlanceModifier, so GlanceModifier.defaultWeight()
        # is the correct form - but it is only valid inside a Row/Column content lambda.
        for m in re.finditer(r"(?<![\w.])defaultWeight\(\)", body):
            line_no = body[:m.start()].count("\n") + 1
            window = body[max(0, m.start() - 600):m.start()]
            if not re.search(r"\b(Row|Column)\s*\(", window):
                fail.append(f"{W}:{line_no} defaultWeight() used outside a Row/Column - it is a scope member")
        # background(ImageProvider, ColorFilter) - the 2nd positional param is contentScale, not colorFilter.
        for m in re.finditer(r"\.background\(ImageProvider\([^)]*\)\s*,\s*ColorFilter", body):
            fail.append(f"{W}: background(ImageProvider, ColorFilter) needs `colorFilter =` named - the 2nd positional arg is contentScale")

print("\n".join(fail) if fail else "preflight: all checks passed")
sys.exit(1 if fail else 0)
