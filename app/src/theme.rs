//! The look: a maker's bench. A light oak surface with drawn grain, paper resting on it with real
//! shadows, ink for text, and three earthy colours for state: moss when something works, ochre
//! while it runs or only half-proves, rust when it fails. Young Serif sets headings, Source Sans 3
//! the text, IBM Plex Mono the slips and output. Everything drawn here is vector shapes.

use rune::tok::{Family, TextStyle};
use rune::{Beat, Color, Cursor, El, LineCap, Path, Sequence, Shadow, Shape, canvas, col, el, oklch, row, text};

pub const FONTS: &[&[u8]] = &[
    include_bytes!("../assets/fonts/SourceSans3.ttf"),
    include_bytes!("../assets/fonts/SourceSans3-Italic.ttf"),
    include_bytes!("../assets/fonts/YoungSerif-Regular.ttf"),
    include_bytes!("../assets/fonts/IBMPlexMono-Regular.ttf"),
    include_bytes!("../assets/fonts/IBMPlexMono-Medium.ttf"),
];
pub const FAMILIES: (&str, &str) = ("Source Sans 3", "IBM Plex Mono");

// Materials.
pub fn oak() -> Color { oklch(0.84, 0.050, 72.0) }
pub fn oak_deep() -> Color { oklch(0.70, 0.065, 62.0) }
pub fn paper() -> Color { oklch(0.975, 0.012, 88.0) }
pub fn paper_shade() -> Color { oklch(0.93, 0.018, 85.0) }
pub fn kraft() -> Color { oklch(0.80, 0.055, 70.0) }
pub fn brass() -> Color { oklch(0.74, 0.11, 85.0) }
pub fn steel() -> Color { oklch(0.72, 0.012, 240.0) }
pub fn leather() -> Color { oklch(0.48, 0.08, 45.0) }
// Inks.
pub fn ink() -> Color { oklch(0.24, 0.018, 60.0) }
pub fn graphite() -> Color { oklch(0.42, 0.012, 60.0) }
pub fn pencil() -> Color { oklch(0.60, 0.014, 65.0) }
pub fn rule() -> Color { oklch(0.86, 0.02, 80.0) }
// State.
pub fn moss() -> Color { oklch(0.50, 0.10, 138.0) }
pub fn ochre() -> Color { oklch(0.66, 0.13, 72.0) }
pub fn rust() -> Color { oklch(0.52, 0.15, 38.0) }

pub fn serif(size: f32) -> TextStyle {
    TextStyle::new(size, 400.0).family(Family::Named("Young Serif"))
}
pub fn sans(size: f32, weight: f32) -> TextStyle {
    TextStyle::new(size, weight)
}
pub fn italic(size: f32) -> TextStyle {
    TextStyle::new(size, 400.0).italic()
}
pub fn mono(size: f32) -> TextStyle {
    TextStyle::new(size, 400.0).mono()
}

pub fn heading(words: impl Into<String>, size: f32) -> El {
    text(words.into()).font(serif(size)).color(ink())
}

pub fn body(words: impl Into<String>, size: f32, color: Color) -> El {
    text(words.into()).font(sans(size, 400.0)).color(color).wrap().w_full()
}

pub fn note(words: impl Into<String>) -> El {
    text(words.into()).font(italic(14.0)).color(graphite()).wrap().w_full()
}

/// The shadow of something lying on the bench: a soft spread and a tight contact line.
pub fn resting(e: El, lift: f32) -> El {
    let warm = oklch(0.30, 0.04, 55.0);
    e.box_shadow(Shadow { y: 2.0 + 4.0 * lift, blur: 6.0 + 18.0 * lift, color: warm.alpha(0.22), ..Shadow::default() })
        .box_shadow(Shadow { y: 1.0, blur: 2.0, color: warm.alpha(0.20), ..Shadow::default() })
}

/// A sheet of paper on the bench.
pub fn sheet() -> El {
    resting(col().fill(paper()).radius(2.0), 1.0)
}

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Kind {
    Primary,
    Plain,
    Danger,
}

/// Buttons: ink on paper, sentence case. The primary one is solid ink.
pub fn button<S: 'static>(key: &str, words: &str, kind: Kind, enabled: bool, f: impl Fn(&mut S, &mut rune::Tx) + 'static) -> El {
    let (fill, fg, border) = match kind {
        Kind::Primary => (ink(), paper(), ink()),
        Kind::Plain => (paper(), ink(), graphite().alpha(0.55)),
        Kind::Danger => (paper(), rust(), rust().alpha(0.7)),
    };
    let b = row()
        .key(key.to_string())
        .h(38.0)
        .px(18.0)
        .center()
        .radius(3.0)
        .fill(fill)
        .border(1.0, border)
        .role("button")
        .label(words.to_string())
        .focusable(enabled)
        .child(text(words.to_string()).font(sans(15.0, 600.0)).color(fg));
    if enabled {
        let b = if kind == Kind::Primary { resting(b, 0.3) } else { b };
        b.hover(if kind == Kind::Primary { Color::WHITE.alpha(0.10) } else { ink().alpha(0.05) })
            .press(Color::BLACK.alpha(0.12))
            .cursor(Cursor::Pointer)
            .on_press_tx(f)
    } else {
        b.opacity(0.4)
    }
}

/// A word you can pick from a list: plain text, underlined in ink when chosen.
pub fn option<S: 'static>(key: &str, words: &str, chosen: bool, f: impl Fn(&mut S) + 'static) -> El {
    col()
        .key(key.to_string())
        .gap(3.0)
        .py(4.0)
        .role("radio")
        .label(words.to_string())
        .checked(chosen)
        .focusable(true)
        .cursor(Cursor::Pointer)
        .on_press(f)
        .child(text(words.to_string()).font(sans(15.0, if chosen { 600.0 } else { 400.0 })).color(if chosen { ink() } else { graphite() }))
        .child(el().w_full().h(2.0).fill(if chosen { ink() } else { Color::TRANSPARENT }))
}

/// A rubber stamp: a double-ruled box with the word in it, pressed at a slant.
const PRESS: &[Beat] = &[Beat::new(0.0).opacity(0.0).scale(1.35), Beat::new(0.16).scale(0.97), Beat::new(0.26)];

pub fn stamp(word: &str, c: Color, size: f32) -> El {
    let inner = row()
        .px(size * 0.45)
        .py(size * 0.18)
        .border(1.5, c)
        .child(text(word.to_uppercase()).font(serif(size).tracking(size * 0.08)).color(c));
    el().key(format!("stamp-{}", word.to_lowercase().replace(' ', "-")))
        .padding(3.0)
        .border(2.5, c)
        .radius(3.0)
        .opacity(0.88)
        .rotate(-0.10)
        .child(inner)
        .sequence(Sequence::new(PRESS))
}

/// A paper tag on a string: a label clipped to a result.
pub fn tag(words: &str, c: Color) -> El {
    let hole = el().size(7.0, 7.0).radius(4.0).fill(oak()).border(1.0, c.alpha(0.6));
    row()
        .h(30.0)
        .pl(10.0)
        .pr(14.0)
        .gap(9.0)
        .center_y()
        .radius(3.0)
        .fill(paper())
        .border(1.0, c.alpha(0.55))
        .child(hole)
        .child(text(words.to_string()).font(sans(14.0, 600.0)).color(c))
}

/// Two strips of translucent tape holding a print at its top corners.
pub fn taped(print: El, w: f32) -> El {
    let strip = |x: f32, turn: f32| {
        el().pin(Some(x), Some(-10.0), None, None)
            .size(70.0, 22.0)
            .fill(oklch(0.95, 0.03, 90.0).alpha(0.65))
            .border(1.0, Color::WHITE.alpha(0.5))
            .rotate(turn)
    };
    el().w(w).child(print).child(strip(14.0, -0.22)).child(strip(w - 84.0, 0.18))
}

/// A dotted leader between a label and its value, as on a printed form.
pub fn leader() -> El {
    let mut s = Shape::new();
    let mut x = 0.0;
    while x < 1200.0 {
        s = s.fill(vec![[x, 0.0], [x + 1.5, 0.0], [x + 1.5, 1.5], [x, 1.5]], pencil().alpha(0.7));
        x += 5.0;
    }
    el().grow(1.0).min_w(12.0).h(10.0).clip_rounded(0.0).child(canvas(s).size(1200.0, 2.0).pin(Some(0.0), Some(6.0), None, None))
}

/// A short hand-ruled line under a heading.
pub fn underline(w: f32, c: Color) -> El {
    let p = Path::new().move_to([0.0, 2.0]).cubic_to([w * 0.3, 0.5], [w * 0.7, 3.5], [w, 1.5]);
    canvas(Shape::new().stroke_path(&p, 1.6, LineCap::Round, c)).size(w, 4.0)
}

fn noise(a: u32, b: u32) -> f32 {
    let mut h = a.wrapping_mul(0x9E37_79B1) ^ b.wrapping_mul(0x85EB_CA77);
    h ^= h >> 15;
    h = h.wrapping_mul(0x2C1B_3C6D);
    h ^= h >> 12;
    (h & 0xFFFF) as f32 / 65535.0
}

/// The bench: oak planks running across the window, their grain and seams drawn once and cached.
pub fn bench() -> El {
    let (w, h) = (2600.0_f32, 1700.0_f32);
    let plank = 230.0_f32;
    let mut s = Shape::new();
    let mut y0 = -40.0;
    let mut p = 0u32;
    while y0 < h {
        // Each plank its own tone.
        let tone = 0.97 + 0.06 * noise(p, 1);
        let c = Color { r: oak().r * tone, g: oak().g * tone, b: oak().b * tone, a: 1.0 };
        s = s.fill(vec![[0.0, y0], [w, y0], [w, y0 + plank], [0.0, y0 + plank]], c);
        // Grain: long gentle waves, darker and lighter.
        for g in 0..34u32 {
            let base = y0 + 6.0 + (plank - 12.0) * noise(p, g * 7 + 3);
            let amp = 1.5 + 5.0 * noise(p, g * 7 + 4);
            let freq = 0.002 + 0.004 * noise(p, g * 7 + 5);
            let phase = 6.28 * noise(p, g * 7 + 6);
            let dark = noise(p, g * 7 + 8) > 0.3;
            let alpha = 0.07 + 0.13 * noise(p, g * 7 + 9);
            let col = if dark { oak_deep().alpha(alpha) } else { Color::WHITE.alpha(alpha * 0.8) };
            let mut pts = Vec::new();
            let mut x = -10.0;
            while x <= w + 10.0 {
                pts.push([x, base + amp * (x * freq + phase).sin() + 1.2 * (x * freq * 3.1).sin()]);
                x += 40.0;
            }
            s = s.stroke(pts, 0.7 + 1.4 * noise(p, g * 7 + 10), col);
        }
        // A knot now and then.
        if noise(p, 99) > 0.45 {
            let (kx, ky) = (200.0 + (w - 400.0) * noise(p, 100), y0 + plank * (0.3 + 0.4 * noise(p, 101)));
            for r in 0..5 {
                let rr = 6.0 + r as f32 * 5.0;
                let pts: Vec<[f32; 2]> = (0..=24).map(|i| {
                    let a = i as f32 / 24.0 * 6.283;
                    [kx + rr * 2.2 * a.cos(), ky + rr * 0.8 * a.sin()]
                }).collect();
                s = s.stroke(pts, 1.0, oak_deep().alpha(0.18 - r as f32 * 0.025));
            }
        }
        // The seam: a dark gap with light catching the next plank's edge.
        let seam = y0 + plank;
        s = s.line([0.0, seam], [w, seam], 2.0, LineCap::Butt, oak_deep().alpha(0.55));
        s = s.line([0.0, seam + 2.0], [w, seam + 2.0], 1.0, LineCap::Butt, Color::WHITE.alpha(0.25));
        y0 += plank;
        p += 1;
    }
    el().pin(Some(0.0), Some(0.0), Some(0.0), Some(0.0))
        .fill(oak())
        .clip_rounded(0.0)
        .child(canvas(s).size(w, h).pin(Some(0.0), Some(0.0), None, None).cache())
}

/// The colours a wooden block is made of: its top, and the side it darkens from.
#[derive(Clone, Copy)]
pub struct Block {
    pub top: Color,
    pub side: Color,
}

impl Block {
    pub fn of_loader(loader: &str) -> Block {
        match loader {
            "fabric" => Block { top: oklch(0.88, 0.05, 82.0), side: oklch(0.74, 0.07, 70.0) },
            "neoforge" => Block { top: oklch(0.74, 0.10, 50.0), side: oklch(0.60, 0.11, 45.0) },
            "forge" => Block { top: oklch(0.70, 0.05, 240.0), side: oklch(0.56, 0.05, 245.0) },
            "paper" | "bukkit" => Block { top: oklch(0.92, 0.02, 90.0), side: oklch(0.78, 0.03, 85.0) },
            _ => Block { top: oklch(0.86, 0.05, 78.0), side: oklch(0.72, 0.07, 68.0) },
        }
    }

    /// A painted toy-block colour from a name: muted, never neon.
    pub fn painted(hue: f32) -> Block {
        Block { top: oklch(0.80, 0.06, hue), side: oklch(0.66, 0.07, hue) }
    }
}

fn shade(c: Color, k: f32) -> Color {
    Color { r: (c.r * k).min(1.0), g: (c.g * k).min(1.0), b: (c.b * k).min(1.0), a: c.a }
}

fn lerp(a: [f32; 2], b: [f32; 2], t: f32) -> [f32; 2] {
    [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t]
}

/// A wooden block seen from above at an angle, `w` points wide, with grain on its faces and an
/// ink outline, as drawn in a maker's sketchbook.
pub fn block(w: f32, b: Block, seed: u32) -> El {
    let h1 = w * 0.2887;
    let s = w * 0.5774;
    let cx = w / 2.0;
    let top = [[cx, 0.0], [w, h1], [cx, 2.0 * h1], [0.0, h1]];
    let left = [[0.0, h1], [cx, 2.0 * h1], [cx, 2.0 * h1 + s], [0.0, h1 + s]];
    let right = [[cx, 2.0 * h1], [w, h1], [w, h1 + s], [cx, 2.0 * h1 + s]];
    let mut shape = Shape::new()
        .fill(top.to_vec(), b.top)
        .fill(left.to_vec(), shade(b.side, 1.02))
        .fill(right.to_vec(), shade(b.side, 0.84));
    let on_quad = |q: [[f32; 2]; 4], u: f32, v: f32| lerp(lerp(q[0], q[1], u), lerp(q[3], q[2], u), v);
    // The left face is end grain: growth rings around a pith off its lower corner.
    let rings = (w / 14.0).clamp(3.0, 12.0) as u32;
    let (pu, pv) = (0.18 + 0.2 * noise(seed, 1), 0.85 + 0.25 * noise(seed, 2));
    for r in 1..=rings + 6 {
        let rad = r as f32 / rings as f32 * 0.55 + 0.02 * noise(seed, r + 3);
        let mut pts = Vec::new();
        for k in 0..=48 {
            let a = k as f32 / 48.0 * 6.283;
            let wob = 1.0 + 0.04 * (a * 3.0 + r as f32).sin();
            let (u, v) = (pu + rad * wob * a.cos(), pv + rad * wob * a.sin() * 1.2);
            if (0.0..=1.0).contains(&u) && (0.0..=1.0).contains(&v) {
                pts.push(on_quad(left, u, v));
            } else if pts.len() > 1 {
                shape = shape.stroke(std::mem::take(&mut pts), 0.8, oak_deep().alpha(0.30));
            } else {
                pts.clear();
            }
        }
        if pts.len() > 1 {
            shape = shape.stroke(pts, 0.8, oak_deep().alpha(0.30));
        }
    }
    // The other faces show long grain: gently waving lines along their length.
    let lines = (w / 8.0).clamp(4.0, 18.0) as u32;
    for (fi, q, alpha) in [(1u32, right, 0.26), (2u32, top, 0.16)] {
        for i in 1..lines {
            let v = i as f32 / lines as f32 + (noise(seed + fi, i) - 0.5) * 0.05;
            let pts: Vec<[f32; 2]> = (0..=16)
                .map(|k| {
                    let u = k as f32 / 16.0;
                    on_quad(q, u, (v + 0.012 * (u * 9.0 + i as f32).sin()).clamp(0.0, 1.0))
                })
                .collect();
            shape = shape.stroke(pts, 0.8, oak_deep().alpha(alpha + 0.1 * noise(seed + fi, i + 50)));
        }
    }
    let edge = ink().alpha(0.75);
    let line_w = (w / 110.0).clamp(1.0, 1.8);
    shape = shape
        .stroke(vec![[cx, 0.0], [w, h1], [w, h1 + s], [cx, 2.0 * h1 + s], [0.0, h1 + s], [0.0, h1], [cx, 0.0]], line_w, edge)
        .stroke(vec![[0.0, h1], [cx, 2.0 * h1], [w, h1]], line_w, edge)
        .stroke(vec![[cx, 2.0 * h1], [cx, 2.0 * h1 + s]], line_w, edge);
    canvas(shape).size(w, 2.0 * h1 + s)
}

/// The shadow a block casts on the bench, as its own soft shape.
pub fn floor_shadow(w: f32) -> El {
    el().size(w * 1.1, w * 0.32).radial_gradient([0.5, 0.5], [w * 0.55, w * 0.16], [(0.0, oklch(0.30, 0.04, 55.0).alpha(0.32)), (1.0, oklch(0.30, 0.04, 55.0).alpha(0.0))])
}

/// A pencil outline of a block: the empty place a project goes.
pub fn block_outline(w: f32) -> El {
    let h1 = w * 0.2887;
    let s = w * 0.5774;
    let cx = w / 2.0;
    let c = graphite().alpha(0.7);
    let mut shape = Shape::new();
    let edges: [Vec<[f32; 2]>; 3] = [
        vec![[cx, 0.0], [w, h1], [cx, 2.0 * h1], [0.0, h1], [cx, 0.0]],
        vec![[0.0, h1], [0.0, h1 + s], [cx, 2.0 * h1 + s], [w, h1 + s], [w, h1]],
        vec![[cx, 2.0 * h1], [cx, 2.0 * h1 + s]],
    ];
    // Dashed, as a pencil sketch of where it will sit.
    for e in &edges {
        for pair in e.windows(2) {
            let (a, b) = (pair[0], pair[1]);
            let len = ((b[0] - a[0]).powi(2) + (b[1] - a[1]).powi(2)).sqrt();
            let n = (len / 9.0) as i32;
            for k in (0..n).step_by(2) {
                let t0 = k as f32 / n as f32;
                let t1 = ((k + 1) as f32 / n as f32).min(1.0);
                shape = shape.line(lerp(a, b, t0), lerp(a, b, t1), 1.4, LineCap::Round, c);
            }
        }
    }
    canvas(shape).size(w, 2.0 * h1 + s)
}

/// A slow breath of opacity: something on the bench is working.
pub fn working(e: El) -> El {
    e.looping(rune::LoopSpec::opacity(1.4, 0.0, 0.35, 1.0))
}
