import re, sys, pathlib
src = pathlib.Path("/Users/d/tmp/herdr-os/design-reference/sheep/sheep-plain.svg").read_text()
out = pathlib.Path("/Users/d/tmp/herdr-os/design-reference/sheep/companion")
EYE = re.search(r'<path d="M7\.6,28\.6[^>]*/>', src).group(0)
EAR = re.search(r'<path d="M20\.5,28\.6[^>]*/>', src).group(0)
W = 'stroke="#FFFFFF" stroke-width="1.1" stroke-linecap="round" fill="none"'
eyes = {
  "open": EYE,
  "sleep": f'<path d="M6.3,28.2 L9,29.6" {W}/>',
  "happy": f'<path d="M6.2,29.2 Q7.4,26.9 9,29.9" {W}/>',
  "wide": '<path d="M7.6,28.6 m-1.6,0 a1.6,1.6 0,1 1,3.2 0 a1.6,1.6 0,1 1,-3.2 0" fill="#FFFFFF"/>',
  "worried": '<path d="M7.6,29 m-1,0 a1,1 0,1 1,2 0 a1,1 0,1 1,-2 0" fill="#FFFFFF"/>'
             f'<path d="M6,26.6 L9.2,26.4" {W}/>',
  "proud": '<path d="M7.6,26.6 L8.1,28.1 L9.6,28.6 L8.1,29.1 L7.6,30.6 L7.1,29.1 L5.6,28.6 L7.1,28.1 Z" fill="#FFFFFF"/>',
}
EAR_UP = '<path d="M20.5,28.6 L24.5,30.6 L24.5,27.2 L20.5,25.2 Z" fill="#1A1A1A" stroke="#1A1A1A" stroke-width="1.2" stroke-linejoin="round"/>'

def tuft(cx, cy, w=3.2, h=2.6):
    cy -= h
    top = f"M{cx},{cy-w/2} L{cx+w},{cy} L{cx},{cy+w/2} L{cx-w},{cy} Z"
    left = f"M{cx-w},{cy} L{cx},{cy+w/2} L{cx},{cy+w/2+h} L{cx-w},{cy+h} Z"
    right = f"M{cx},{cy+w/2} L{cx+w},{cy} L{cx+w},{cy+h} L{cx},{cy+w/2+h} Z"
    s = 'stroke-width="1" stroke-linejoin="round"'
    return (f'<path d="{left}" fill="#E7E3DC" stroke="#E7E3DC" {s}/>'
            f'<path d="{right}" fill="#D3CEC5" stroke="#D3CEC5" {s}/>'
            f'<path d="{top}" fill="#FFFFFF" stroke="#FFFFFF" {s}/>')
TUFTS = [(24, 11.5), (19, 13.2), (30, 15.6), (25.5, 16), (21, 17)]

def envelope(color):
    return (f'<path d="M0.8,33.2 L12.6,39.1 L12.6,45.4 L0.8,39.5 Z" fill="{color}" stroke="{color}" stroke-width="0.8" stroke-linejoin="round"/>'
            '<path d="M0.8,33.2 L6.7,39 L12.6,39.1" stroke="#000000" stroke-opacity="0.45" stroke-width="0.8" fill="none" stroke-linejoin="round"/>'
            '<path d="M6.7,39.4 m-1.3,0 a1.3,1.3 0,1 1,2.6 0 a1.3,1.3 0,1 1,-2.6 0" fill="#FF4F12"/>')

def make(name, eye="open", ear_up=False, tufts=0, seal=None):
    s = src.replace(EYE, eyes[eye])
    if ear_up: s = s.replace(EAR, EAR_UP)
    extra = "".join(tuft(*p) for p in sorted(TUFTS[:tufts], key=lambda p: p[1]))
    if seal: extra += envelope(seal)
    s = s.replace("</svg>", extra + "</svg>")
    (out / f"herdr-{name}.svg").write_text(s)

make("napping", "sleep")
make("curious", "wide", ear_up=True)
make("happy", "happy", ear_up=True)
make("worried", "worried")
make("proud", "proud", ear_up=True)
make("fluffy", "wide", tufts=5)
make("carry-claude", "wide", ear_up=True, seal="#D97757")
make("carry-codex", "happy", ear_up=True, seal="#F3F3F1")
make("trot", "happy", ear_up=True, seal="#F3F3F1")
make("fluffy-3", "open", tufts=3)
make("fluffy-1", "open", ear_up=True, tufts=1)
