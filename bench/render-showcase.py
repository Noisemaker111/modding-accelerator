"""Render bench/showcase.png: the beta summary card used at the top of the README.

Numbers are the ones in docs/index.html and the receipts they cite. Pillow only; no browser.
"""
from PIL import Image, ImageDraw, ImageFont

W, H = 1200, 1400
BG = (255, 255, 255)
INK = (11, 11, 11)
INK2 = (82, 81, 78)
INK3 = (138, 137, 131)
RULE = (236, 235, 231)
TILE = (246, 245, 242)
OLD = (185, 183, 176)
NEW = (42, 120, 214)
YES = (31, 122, 58)

F = "C:/Windows/Fonts/"
def font(name, size): return ImageFont.truetype(F + name, size)
regular = lambda s: font("segoeui.ttf", s)
bold = lambda s: font("segoeuib.ttf", s)
mono = lambda s: font("consola.ttf", s)

img = Image.new("RGB", (W, H), BG)
d = ImageDraw.Draw(img)
M = 56

def text(x, y, s, f, fill=INK):
    d.text((x, y), s, font=f, fill=fill)

def wrap(s, f, width):
    words, lines, cur = s.split(" "), [], ""
    for w in words:
        probe = (cur + " " + w).strip()
        if d.textlength(probe, font=f) <= width: cur = probe
        else: lines.append(cur); cur = w
    if cur: lines.append(cur)
    return lines

# Title
text(M, 40, "Move a Minecraft mod to a new version,", bold(33))
text(M, 80, "and see what actually works.", bold(33), NEW)

y = 132
text(M, y, "Goal: any Minecraft version to any other, and any loader to any other.", regular(15), INK2)
y += 30
for line in wrap("Use it to test logic fast, check a screen faster, compile and prove the result, and auto-port a mod to some versions. One command; every run ends in a checked result or a named list of what is left.", regular(16), W - 2 * M):
    text(M, y, line, regular(16), INK2); y += 24

# Tiles
tiles = [
    ("75 s", "first NeoForge port in a fresh repo, 19 API errors repaired (14 s repeat)"),
    ("2.3 s", "answer after a one-file edit in a 2,102-source Forge repo (137 s warm build)"),
    ("1.5 s", "screen edit swapped into a running client, with a capture (22-28 s restart)"),
    ("3 OS", "portable checks green in hosted CI; game checks on Windows"),
]
y += 26
gap, tw = 14, (W - 2 * M - 3 * 14) // 4
for i, (big, sub) in enumerate(tiles):
    x = M + i * (tw + gap)
    d.rounded_rectangle([x, y, x + tw, y + 118], radius=10, fill=TILE)
    text(x + 16, y + 12, big, bold(30))
    ty = y + 50
    for line in wrap(sub, regular(13), tw - 32)[:3]:
        text(x + 16, ty, line, regular(13), INK2); ty += 17
y += 118 + 26

# Section: their build vs this
d.line([M, y, W - M, y], fill=RULE); y += 20
text(M, y, "Their own build, next to this", bold(21)); y += 30
text(M, y, "Open-source mods, unchanged. Grey: the mod's own Gradle build with the daemon warm. Blue: the whole modaccel check.", regular(14), INK2); y += 28
bars = [
    ("MineColonies", "Forge 1.20.1, 2,102 sources", 328.3, 23.9, "first check; edits 2.3 s"),
    ("Traveler's Backpack", "Fabric 1.21.5", 335.1, 6.4, "one-file edit"),
    ("Artifacts", "Fabric 1.21.4", 567.5, 4.2, "one-file edit"),
    ("Polymer", "Fabric 1.21.6", 474.4, 3.8, "one-file edit"),
    ("Diorama", "Fabric 26.3, Stonecutter", 204.5, 2.1, "fast lane"),
]
CAP, labelW, valW = 350, 250, 240
barX = M + labelW + 18
barW = W - M - valW - barX
for name, what, old, now, note in bars:
    text(M, y + 2, name, bold(15))
    text(M, y + 20, what, regular(12), INK3)
    d.rounded_rectangle([barX, y + 2, barX + max(4, min(old, CAP) / CAP * barW), y + 18], radius=4, fill=OLD)
    text(barX + min(old, CAP) / CAP * barW + 8, y + 2, f"{old:.0f} s", regular(12), INK2)
    d.rounded_rectangle([barX, y + 22, barX + max(4, now / CAP * barW), y + 38], radius=4, fill=NEW)
    text(barX + max(4, now / CAP * barW) + 8, y + 22, f"{now:g} s - {note}", bold(12), INK)
    text(W - M - 40, y + 4, f"{round(old / now)}x", bold(26), NEW)
    y += 46
y += 12

# Section: ports
d.line([M, y, W - M, y], fill=RULE); y += 20
text(M, y, "Ports that were actually checked", bold(21)); y += 30
text(M, y, "A port counts only when the build passes, mixin targets verify, the packaged metadata declares the new game version, and a real client loads it.", regular(14), INK2); y += 26
cols = [M, M + 250, M + 400, W - M - 90]
text(cols[0], y, "MOD", bold(12), INK3)
text(cols[1], y, "JUMP", bold(12), INK3)
text(cols[2], y, "WHAT THE TOOL DID", bold(12), INK3)
text(cols[3] + 40, y, "LEFT", bold(12), INK3)
y += 20
d.line([M, y, W - M, y], fill=RULE); y += 10
ports = [
    ("F3Nope", "Fabric", "1.21.5 <-> 1.21.6", "Port and backport, build, mixin audit, real client; a visual gate caught an invisible overlay.", "0", YES),
    ("AppleSkin", "Fabric", "1.21.5 <-> 1.21.6", "Both directions plus a save roundtrip that reloads the world at the same position.", "0", YES),
    ("ZombieApocalypseAddon", "NeoForge, 71 files", "1.21.1 -> 1.21.4", "Loader and game ranges rewritten, 19 API errors repaired, built and audited; the jar loads and /za runs.", "0", YES),
    ("ZombieApocalypseAddon", "NeoForge, five minors", "1.21.1 -> 1.21.6", "38 reported errors reduced to 9 by rules (NBT, UUID codecs, day/night, snapping, sound holders).", "9", (163, 90, 0)),
    ("Jade", "NeoForge", "1.21.1 -> 1.21.6", "Reaches the target and names the 1.21.6 GUI rewrite instead of shipping a build of the old game.", "98", (163, 90, 0)),
]
for name, kind, jump, what, left, colour in ports:
    text(cols[0], y, name, bold(15))
    text(cols[0], y + 18, kind, regular(12), INK3)
    text(cols[1], y + 1, jump, mono(12), INK2)
    ty = y
    for line in wrap(what, regular(13), cols[3] - cols[2] - 20)[:3]:
        text(cols[2], ty, line, regular(13), INK2); ty += 17
    text(cols[3] + 40, y + 1, left, bold(15), colour)
    y = max(y + 44, ty + 10)
    d.line([M, y, W - M, y], fill=RULE); y += 10
y += 6

# Limits
d.line([M, y, W - M, y], fill=RULE); y += 20
text(M, y, "What this does not claim", bold(21)); y += 28
limits = [
    ("Loader changes are not supported.", "Fabric stays Fabric; a port moves the Minecraft version inside one loader."),
    ("Source checkouts only.", "A compiled mod JAR cannot be ported."),
    ("No pixel goldens.", "Frames are captured for a person or agent; nothing compares them to last week."),
    ("Big jumps stop honestly.", "1.21.6 storage and GUI work still needs a human, and the tool names it."),
]
colW = (W - 2 * M - 30) // 2
for i, (head, body) in enumerate(limits):
    x = M + (i % 2) * (colW + 30); yy = y + (i // 2) * 46
    text(x, yy, head, bold(14))
    text(x, yy + 19, body, regular(13), INK2)

y += 104
footY = y + 18
d.line([M, footY, W - M, footY], fill=RULE)
text(M, footY + 14, "Beta 0.1.0, published 24 October 2026  -  github.com/Noisemaker111/modding-accelerator/releases  -  one page: noisemaker111.github.io/modding-accelerator", regular(12), INK3)
text(M, footY + 34, "Measured on a Ryzen 9 3900X, Windows 11. Numbers come from this repo's per-run receipts; comparisons are in bench/alternatives.md.", regular(12), INK3)

img = img.crop((0, 0, W, footY + 58))
img.save("bench/showcase.png")
print("wrote bench/showcase.png", img.size)
