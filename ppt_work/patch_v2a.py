import pathlib
p = pathlib.Path("build_deck_v2.py")
s = p.read_text(encoding="utf-8")
def sub(old, new):
    global s
    assert s.count(old) == 1, old[:60]
    s = s.replace(old, new)

# slide1 pills fit
sub("    px = 7.35\n", "    px = 7.0\n")
sub("        w = 0.07 * len(lab) + 0.62\n", "        w = 0.064 * len(lab) + 0.6\n")
sub("        px += w + 0.12\n", "        px += w + 0.1\n")

# slide2: smaller hero, floating stage cards, compact bottom
sub("    iw = 10.4\n", "    iw = 9.6\n")
sub("    ly = iy + ih + 0.02\n", "    ly = iy + ih - 0.45\n")
sub("        card(s, x, ly, w, 0.66, radius=0.2)\n", "        card(s, x, ly, w, 0.62, radius=0.2)\n")
sub("    by = 4.9\n", "    by = 5.14\n")
sub("    card(s, lx, by, lw, 1.95, radius=0.06)\n", "    card(s, lx, by, lw, 1.74, radius=0.06)\n")
sub("        y = by + 0.5 + i * 0.36\n", "        y = by + 0.45 + i * 0.32\n")
sub("lx + 3.35, by + 1.16, 1.55, 0.3, grad", "lx + 3.35, by + 1.09, 1.55, 0.3, grad")
sub("text(s, lx + 3.35, by + 1.16, 1.55, 0.3, \"120", "text(s, lx + 3.35, by + 1.09, 1.55, 0.3, \"120")
sub("tx, by + 1.55, w, 0.3, fill=T_EMER", "tx, by + 1.4, w, 0.28, fill=T_EMER")
sub("icon(s, ic, \"g\", tx + 0.08, by + 1.59, 0.22)", "icon(s, ic, \"g\", tx + 0.08, by + 1.43, 0.22)")
sub("text(s, tx + 0.33, by + 1.55, w - 0.36, 0.3, lab", "text(s, tx + 0.33, by + 1.4, w - 0.36, 0.28, lab")
sub("    cw, ch = (rw - 0.14) / 2, 0.77\n", "    cw, ch = (rw - 0.14) / 2, 0.68\n")
sub("        cy = by + 0.33 + (i // 2) * (ch + 0.08)\n", "        cy = by + 0.3 + (i // 2) * (ch + 0.06)\n")
sub("        badge(s, ic, g, cx + 0.12, cy + 0.14, 0.5, glow=True)\n", "        badge(s, ic, g, cx + 0.12, cy + 0.1, 0.48, glow=True)\n")
sub("cx + 0.74, cy + 0.08, cw - 0.8, 0.32, t,", "cx + 0.74, cy + 0.05, cw - 0.8, 0.3, t,")
sub("cx + 0.74, cy + 0.39, cw - 0.8, 0.32, sub,", "cx + 0.74, cy + 0.34, cw - 0.8, 0.3, sub,")

# slide3: node spacing, vertical side labels, no stray arrows
sub("        text(s, cx - 0.85, cy + 0.57, 1.7, 0.24, sub,", "        text(s, cx - 0.85, cy + 0.54, 1.7, 0.24, sub,")
sub("    xs = [lx + 0.95 + i * 1.88 for i in range(4)]\n    text(s, lx + 0.2, 4.22, 2.0, 0.22, \"PHONE A  •  SENDER\", size=8, bold=True, color=NAVY2)\n",
    "    xs = [lx + 1.2 + i * 1.8 for i in range(4)]\n\n"
    "    def vlabel(x, y, w, h, label, fill, color):\n"
    "        cx, cy = x + w / 2, y + h / 2\n"
    "        b = shape(s, MSO_SHAPE.ROUNDED_RECTANGLE, cx - h / 2, cy - w / 2, h, w, fill=fill, radius=0.5)\n"
    "        b.rotation = 270\n"
    "        t = text(s, cx - h / 2, cy - w / 2, h, w, label, size=7.5, bold=True, color=color, align=PP_ALIGN.CENTER,\n"
    "                 anchor=MSO_ANCHOR.MIDDLE)\n"
    "        t.rotation = 270\n\n"
    "    vlabel(lx + 0.16, 4.3, 0.28, 1.1, \"PHONE A  •  SENDER\", T_SKY, NAVY2)\n")
sub("    my = 5.47\n", "    my = 5.52\n")
sub("lx + 0.2, my, lw - 0.4, 0.34, grad=(NAVY2, NAVY)", "lx + 0.16, my, lw - 0.32, 0.3, grad=(NAVY2, NAVY)")
sub("icon(s, \"bt\", \"w\", lx + 0.34, my + 0.05, 0.24)", "icon(s, \"bt\", \"w\", lx + 0.34, my + 0.04, 0.22)")
sub("icon(s, \"wifi\", \"w\", lx + 0.6, my + 0.05, 0.24)", "icon(s, \"wifi\", \"w\", lx + 0.6, my + 0.04, 0.22)")
sub("    text(s, lx + 0.95, my, lw - 1.2, 0.34,\n", "    text(s, lx + 0.95, my, lw - 1.2, 0.3,\n")
sub("    connector(s, xs[3], 5.34, xs[3], my - 0.01)\n    connector(s, xs[0], my + 0.35, xs[0], 5.92)\n    text(s, lx + 0.2, 5.84, 2.2, 0.22, \"PHONE B  •  RECEIVER\", size=8, bold=True, color=EMER2)\n",
    "    vlabel(lx + 0.16, 5.9, 0.28, 0.92, \"PHONE B  •  RECEIVER\", T_EMER, EMER2)\n")
sub("        node(xs[i], 6.08 + 0.0, ic, g, t, sub)\n", "        node(xs[i], 6.14, ic, g, t, sub)\n")
sub("            connector(s, xs[i] + 0.4, 6.08, xs[i + 1] - 0.4, 6.08)\n", "            connector(s, xs[i] + 0.4, 6.14, xs[i + 1] - 0.4, 6.14)\n")
sub("    card(s, lx, 4.16, lw, 2.68, radius=0.05)\n", "    card(s, lx, 4.16, lw, 2.72, radius=0.05)\n")

# phone: cutout with a white screen behind it, callouts spaced out
sub("    pic(s, os.path.join(IMG, \"v2_phone.png\"), phx, phy, ph_w, ph_h)\n    sx0, sy0 = phx + ph_w * 0.196, phy + ph_h * 0.125\n    sw, sh = ph_w * (0.795 - 0.196), ph_h * (0.876 - 0.125)\n",
    "    sx0, sy0 = phx + ph_w * 0.196, phy + ph_h * 0.125\n    sw, sh = ph_w * (0.795 - 0.196), ph_h * (0.876 - 0.125)\n"
    "    shape(s, MSO_SHAPE.ROUNDED_RECTANGLE, sx0 - 0.04, sy0 - 0.06, sw + 0.08, sh + 0.12, fill=WHITE, radius=0.08)\n"
    "    pic(s, os.path.join(IMG, \"v2_phone_t.png\"), phx, phy, ph_w, ph_h)\n")
sub("""    callouts = [("236 ms", "Hindi sentence decode", SAFF, sy0 + 1.15),
                ("644 ms", "STT model load", NAVY, 2.95),
                ("654 ms", "TTS voice load", NAVY, 4.0),
                ("10 / 10", "languages verified", EMER2, 5.05)]""",
"""    callouts = [("644 ms", "STT model load", NAVY, 2.35),
                ("236 ms", "Hindi sentence decode", SAFF, 3.4),
                ("654 ms", "TTS voice load", NAVY, 4.45),
                ("10 / 10", "languages verified", EMER2, 5.5)]""")
p.write_text(s, encoding="utf-8")
print("patched")
