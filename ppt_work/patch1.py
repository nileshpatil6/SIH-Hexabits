import pathlib

import numpy as np
from PIL import Image

p = pathlib.Path(__file__).with_name("build_deck.py")
s = p.read_text(encoding="utf-8")


def sub(old, new, n=1):
    global s
    c = s.count(old)
    assert c == n, (c, old[:70])
    s = s.replace(old, new)


# slide 1: highlight badges
sub('''    # Brand strip along the bottom.''', '''    badges = [("phone", GREEN, "Working Android prototype"), ("lang", SAFF, "10 languages verified offline"),
              ("github", NAVY, "Open-source code + model packs")]
    for i, (ic, col, lab) in enumerate(badges):
        bx = 0.5 + i * 2.35
        box(s, bx, 5.45, 2.22, 0.5, fill=WHITE, line=col, radius=0.5, lw=1.25)
        icon(s, ic, {GREEN: "g", SAFF: "o", NAVY: "n"}[col], bx + 0.13, 5.55, 0.3)
        text(s, bx + 0.5, 5.45, 1.7, 0.5, lab, size=10.5, bold=True, color=col, anchor=MSO_ANCHOR.MIDDLE)

    # Brand strip along the bottom.''')

# slide 2
sub('''         "Detailed explanation: an Android walkie-talkie that recognises speech on the phone, sends it as a few hundred bytes "
         "of signed text over a phone-to-phone mesh, and speaks it again on the other phone.",''',
    '''         "An Android walkie-talkie that turns speech into a few hundred bytes of signed text, relays it phone to phone, "
         "and speaks it again on the other side.",''')
sub('"~234-byte packet", "Text + language + Ed25519 signature, TTL 7"', '"234 B packet", "Text + language + Ed25519 signature, TTL 7"')
sub('"Phone-to-phone mesh", "BLE + Wi-Fi Direct relays, up to 7 hops, no tower"', '"Phone mesh", "BLE + Wi-Fi Direct relays, up to 7 hops, no tower"')
sub('''    s.shapes.add_picture(os.path.join(IMG, "hero.png"), Inches(0.45), Inches(3.2), Inches(5.75), Inches(3.21))''',
    '''    s.shapes.add_picture(os.path.join(IMG, "hero_w.png"), Inches(0.5), Inches(3.18), Inches(5.6), Inches(3.13))''')
sub('''        box(s, x, 6.43, cw, 0.44, fill=NAVY, radius=0.5)
        text(s, x, 6.43, cw, 0.44, [[(big + "  ", {"bold": True, "color": WHITE, "size": 12.5}),
                                     (small, {"color": RGBColor(0xDC, 0xE6, 0xF5), "size": 10})]],''',
    '''        box(s, x, 6.33, cw, 0.54, fill=NAVY, radius=0.3)
        text(s, x, 6.33, cw, 0.54, [[(big, {"bold": True, "color": WHITE, "size": 12.5})],
                                     [(small, {"color": RGBColor(0xDC, 0xE6, 0xF5), "size": 9})]],''')
sub('"Floods every hop, max volume, repeats till ACK"', '"Floods all hops; loud until acknowledged"')
sub('"Every packet signed; private messages encrypted"', '"Signed packets; encrypted private messages"')

# slide 3
sub('"IndicConformer 120M int8 (STT)"', '"IndicConformer int8 (STT)"')
sub('"X25519 + ChaCha20-Poly1305"]', '"X25519 + ChaCha20"]')
sub('''            cw = 0.075 * len(c) + 0.36''', '''            cw = 0.066 * len(c) + 0.32''')
sub('''            text(s, x, y, cw, 0.36, c, size=10.5, color=INK''', '''            text(s, x, y, cw, 0.36, c, size=10, color=INK''')
sub('''    arrow(s, lx + bw / 2 - 0.1, my + 0.5, 0.2, 0.2''', '''    arrow(s, lx + bw - 0.4, my + 0.5, 0.2, 0.2''')
sub('''"Real app screen and timings, OnePlus CPH2717 (Android 16). Code and model packs are open source."''',
    '''"Talk screen of the running app (redrawn); timings measured on a OnePlus CPH2717, Android 16."''')

# slide 5: number column sized to the number
sub('''        text(s, x + 0.15, 1.3, 1.25, 1.04, big, size=30, bold=True, color=col, anchor=MSO_ANCHOR.MIDDLE)
        text(s, x + 1.35, 1.3, sw - 1.45, 1.04, lab, size=10.5, color=INK, anchor=MSO_ANCHOR.MIDDLE)''',
    '''        nw = 0.26 * len(big) + 0.35
        text(s, x + 0.18, 1.3, nw, 1.04, big, size=30, bold=True, color=col, anchor=MSO_ANCHOR.MIDDLE)
        text(s, x + 0.2 + nw, 1.3, sw - nw - 0.32, 1.04, lab, size=10.5, color=INK, anchor=MSO_ANCHOR.MIDDLE)''')
sub('os.path.join(IMG, "impact.png")', 'os.path.join(IMG, "impact_w.png")')

# slide 6: fifth reference per column, tighter rows, QR link line breaks
sub('''            ("Silero VAD", "github.com/snakers4/silero-vad"),
        ]),''', '''            ("Silero VAD", "github.com/snakers4/silero-vad"),
            ("IndicConformer sherpa-onnx int8 exports", "huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx"),
        ]),''')
sub('''            ("BitChat mesh protocol whitepaper", "github.com/permissionlesstech/bitchat"),
        ]),''', '''            ("BitChat mesh protocol whitepaper", "github.com/permissionlesstech/bitchat"),
            ("SYSPIN: open CC-BY Indian TTS dataset (IISc)", "vaani.iisc.ac.in/dataset/syspindataset"),
        ]),''')
sub('''            ("NDMA: disaster management in India", "ndma.gov.in"),
        ]),''', '''            ("NDMA: disaster management in India", "ndma.gov.in"),
            ("Bridgefy offline mesh adds E2E encryption (2020)", "techcrunch.com (Nov 2, 2020)"),
        ]),''')
sub('''        "forbes.com (Sep 11, 2025)": ''', '''        "techcrunch.com (Nov 2, 2020)": "https://techcrunch.com/2020/11/02/bridgefy-launches-end-to-end-encrypted-messaging-for-the-app-used-during-protests-and-disasters/",
        "forbes.com (Sep 11, 2025)": ''')
sub('''            y = 2.5 + j * 0.72
            href = links.get(url, "https://" + url)
            text(s, x + 0.22, y, cw - 0.4, 0.66,
                 [[(name, {"bold": True, "color": INK, "size": 11})],
                  [(url, {"color": BLUE, "size": 9.5, "link": href})]])''',
    '''            y = 2.45 + j * 0.6
            href = links.get(url, "https://" + url)
            shown = url if len(url) < 48 else url.split("/")[0] + "/.../" + url.split("/")[-1]
            text(s, x + 0.22, y, cw - 0.4, 0.58,
                 [[(name, {"bold": True, "color": INK, "size": 10.5})],
                  [(shown, {"color": BLUE, "size": 9, "link": href})]])''')
sub('''    qrs = [("App source code", "github.com/nileshpatil6/SIH-Hexabits"),
           ("Offline model packs", "huggingface.co/datasets/Mr66/itantra-packs")]
    for k, (lab, url) in enumerate(qrs):
        qx = 7.05 + k * 2.95
        s.shapes.add_picture(qr_png("https://" + url), Inches(qx), Inches(y0 + 0.1), Inches(1.05), Inches(1.05))
        text(s, qx + 1.15, y0 + 0.18, 1.75, 0.95,
             [[(lab, {"bold": True, "color": NAVY, "size": 11})],
              [(url, {"color": BLUE, "size": 8.5, "link": "https://" + url})]])''',
    '''    qrs = [("App source code", "github.com/nileshpatil6/SIH-Hexabits", ["github.com/nileshpatil6/", "SIH-Hexabits"]),
           ("Offline model packs", "huggingface.co/datasets/Mr66/itantra-packs", ["huggingface.co/datasets/", "Mr66/itantra-packs"])]
    for k, (lab, url, lines) in enumerate(qrs):
        qx = 6.75 + k * 3.05
        s.shapes.add_picture(qr_png("https://" + url), Inches(qx), Inches(y0 + 0.1), Inches(1.05), Inches(1.05))
        text(s, qx + 1.15, y0 + 0.16, 1.85, 1.0,
             [[(lab, {"bold": True, "color": NAVY, "size": 11})]] +
             [[(ln, {"color": BLUE, "size": 9, "link": "https://" + url})] for ln in lines])''')
p.write_text(s, encoding="utf-8")
print("patched")

# Push near-white background noise in generated images to pure white.
img = pathlib.Path(__file__).with_name("img")
for n in ["hero", "impact", "mesh_map"]:
    a = np.asarray(Image.open(img / f"{n}.png").convert("RGB")).astype(np.int16)
    m = (a.min(axis=2) > 236) & ((a.max(axis=2) - a.min(axis=2)) < 14)
    a[m] = 255
    Image.fromarray(a.astype(np.uint8)).save(img / f"{n}_w.png")
    print(n, "whitened", round(float(m.mean()), 3))
