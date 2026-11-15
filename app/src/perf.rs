//! "Measure": `mmt perf` on a running game, then its numbers drawn as a measuring sheet: frame
//! rate, a plotted frame-time chart, time by origin, and a hatched treemap of the hottest classes.

use std::path::PathBuf;

use rune::{Color, Cursor, El, LineCap, Shape, canvas, col, el, oklch, row, text};
use serde_json::Value;

use crate::cli::Outcome;
use crate::theme::{self, Kind, graphite, ink, moss, ochre, paper, pencil, rust};
use crate::{App, Purpose};

const DURATIONS: [(&str, u32); 4] = [("15 s", 15), ("30 s", 30), ("1 min", 60), ("2 min", 120)];

pub struct Process {
    pub pid: String,
    pub main: String,
    pub game: bool,
}

pub struct PerfState {
    pub processes: Option<Vec<Process>>,
    pub list_error: Option<String>,
    pub chosen: Option<String>,
    pub duration: usize,
    pub result: Option<Outcome>,
    /// The capture's report.json, read once when it lands.
    pub report: Option<Value>,
    /// Build tools and other Java programs, not only games.
    pub show_all: bool,
    /// The game picker opened again over a finished measurement.
    pub choosing: bool,
}

impl Default for PerfState {
    fn default() -> Self {
        PerfState { processes: None, list_error: None, chosen: None, duration: 0, result: None, report: None, show_all: false, choosing: false }
    }
}

impl PerfState {
    pub fn list(&mut self, outcome: Outcome) {
        if !outcome.ok() {
            self.list_error = Some(outcome.summary());
            self.processes = Some(Vec::new());
            return;
        }
        self.list_error = None;
        let mut list: Vec<Process> = outcome.result()["processes"]
            .as_array()
            .into_iter()
            .flatten()
            .map(|p| Process {
                pid: p["pid"].as_str().map(str::to_string).unwrap_or_else(|| p["pid"].to_string()),
                main: p["main"].as_str().unwrap_or("").to_string(),
                game: p["gameCandidate"].as_bool() == Some(true),
            })
            .collect();
        // Games first; build tools after.
        list.sort_by_key(|p| !p.game);
        let games: Vec<&Process> = list.iter().filter(|p| p.game).collect();
        if self.chosen.as_ref().is_none_or(|c| !list.iter().any(|p| &p.pid == c)) {
            self.chosen = games.first().map(|p| p.pid.clone());
        }
        self.processes = Some(list);
    }

    pub fn captured(&mut self, outcome: Outcome) {
        self.report = outcome.result()["report"]
            .as_str()
            .and_then(|p| std::fs::read_to_string(p).ok())
            .and_then(|t| serde_json::from_str(&t).ok());
        self.result = Some(outcome);
    }
}

fn find(s: &mut App) {
    s.start("Looking for running games", Purpose::PerfList, vec!["perf".into(), "--list".into()]);
}

fn capture(s: &mut App) {
    let Some(pid) = s.perf.chosen.clone() else { return };
    let seconds = DURATIONS[s.perf.duration.min(DURATIONS.len() - 1)].1;
    s.perf.result = None;
    s.perf.report = None;
    s.perf.choosing = false;
    s.start(format!("Measuring for {seconds}s"), Purpose::PerfCapture, vec!["perf".into(), "--attach".into(), pid, "--duration".into(), seconds.to_string()]);
}

fn html(o: &Outcome) -> Option<PathBuf> {
    o.result()["html"].as_str().map(PathBuf::from).filter(|p| p.is_file())
}

fn short_main(main: &str) -> String {
    let tail = main.rsplit(['.', '/']).next().unwrap_or(main);
    match tail {
        "Main" | "KnotClient" => main.split('.').take(3).collect::<Vec<_>>().join("."),
        _ => tail.to_string(),
    }
}


#[derive(Clone)]
struct Item {
    name: String,
    detail: String,
    weight: f32,
    fill: Fill,
}

/// The hot methods grouped by class.
fn hot_classes(rep: &Value) -> Vec<Item> {
    let mut by: Vec<Item> = Vec::new();
    for m in rep["hotMethods"].as_array().into_iter().flatten() {
        let full = m["name"].as_str().unwrap_or("");
        let head = full.split('(').next().unwrap_or(full);
        let (class, method) = head.rsplit_once('.').unwrap_or((head, ""));
        let simple = class.rsplit('.').next().unwrap_or(class).to_string();
        let pct = m["percent"].as_f64().unwrap_or(0.0) as f32;
        match by.iter_mut().find(|i| i.name == simple) {
            Some(i) => i.weight += pct,
            None => by.push(Item { name: simple, detail: method.to_string(), weight: pct, fill: origin(class).0 }),
        }
    }
    by.sort_by(|a, b| b.weight.total_cmp(&a.weight));
    by.truncate(14);
    by
}

/// Squarified treemap: rectangles for `items` inside `(x, y, w, h)`, in order.
fn squarify(items: &[Item], x: f32, y: f32, w: f32, h: f32) -> Vec<[f32; 4]> {
    let total: f32 = items.iter().map(|i| i.weight).sum();
    if items.is_empty() || total <= 0.0 {
        return Vec::new();
    }
    let area = w * h;
    let sizes: Vec<f32> = items.iter().map(|i| i.weight / total * area).collect();
    let mut out = Vec::new();
    let (mut x, mut y, mut w, mut h) = (x, y, w, h);
    let mut start = 0;
    while start < sizes.len() {
        let side = w.min(h);
        let worst = |row: &[f32]| {
            let s: f32 = row.iter().sum();
            let (mx, mn) = row.iter().fold((0.0_f32, f32::MAX), |(a, b), v| (a.max(*v), b.min(*v)));
            ((side * side * mx) / (s * s)).max((s * s) / (side * side * mn))
        };
        let mut end = start + 1;
        while end < sizes.len() && worst(&sizes[start..=end]) <= worst(&sizes[start..end]) {
            end += 1;
        }
        let row = &sizes[start..end];
        let s: f32 = row.iter().sum();
        if w >= h {
            let rw = s / h;
            let mut yy = y;
            for v in row {
                let rh = v / rw;
                out.push([x, yy, rw, rh]);
                yy += rh;
            }
            x += rw;
            w -= rw;
        } else {
            let rh = s / w;
            let mut xx = x;
            for v in row {
                let rw = v / rh;
                out.push([xx, y, rw, rh]);
                xx += rw;
            }
            y += rh;
            h -= rh;
        }
        start = end;
    }
    out
}


fn machines(s: &App) -> El {
    let p = &s.perf;
    let mut list = col().key("machines").w_full().gap(4.0);
    let Some(procs) = &p.processes else { return list };
    if procs.is_empty() {
        return list.child(theme::note("No Java programs are running. Start the game, then look again."));
    }
    let games = procs.iter().filter(|p| p.game).count();
    let others = procs.len() - games;
    for proc in procs.iter().filter(|p| p.game || p.show_all_ok(games, s.perf.show_all)).take(16) {
        let pid = proc.pid.clone();
        let on = p.chosen.as_deref() == Some(proc.pid.as_str());
        let dot = el().size(16.0, 16.0).radius(8.0).border(1.6, ink()).center().child(if on { el().size(8.0, 8.0).radius(4.0).fill(ink()) } else { el() });
        list = list.child(
            row()
                .key(format!("proc-{}", proc.pid))
                .w_full()
                .h(40.0)
                .gap(12.0)
                .center_y()
                .role("radio")
                .label(proc.main.clone())
                .checked(on)
                .focusable(true)
                .cursor(Cursor::Pointer)
                .on_press(move |s: &mut App| s.perf.chosen = Some(pid.clone()))
                .child(dot)
                .child(text(if proc.game { "Minecraft".to_string() } else { short_main(&proc.main) }).font(theme::sans(16.0, if on { 600.0 } else { 400.0 })).color(ink()))
                .child(text(format!("pid {}", proc.pid)).font(theme::mono(12.0)).color(graphite()))
                .child(text(short_main(&proc.main)).font(theme::mono(12.0)).color(pencil()).truncate().grow(1.0).min_w(0.0)),
        );
    }
    if games > 0 && others > 0 {
        let words = if p.show_all { "Only games".to_string() } else { format!("Show {others} other Java programs") };
        list = list.child(theme::option("perf-all", &words, false, |s: &mut App| s.perf.show_all = !s.perf.show_all));
    }
    list
}

impl Process {
    /// A program that isn't a game shows when asked for, or when no game is running.
    fn show_all_ok(&self, games: usize, show_all: bool) -> bool {
        show_all || games == 0
    }
}

fn durations(s: &App) -> El {
    let mut r = row().gap(18.0).center_y().child(text("Measure for").font(theme::sans(15.0, 400.0)).color(graphite()));
    for (i, (name, _)) in DURATIONS.iter().enumerate() {
        r = r.child(theme::option(&format!("dur-{name}"), name, s.perf.duration == i, move |s: &mut App| s.perf.duration = i));
    }
    r
}

/// Graph paper: fine lines every 10 points, heavier every 50, inside the drawing only.
fn graph_paper(w: f32, h: f32) -> Shape {
    let mut s = Shape::new();
    let mut x = 0.0;
    let mut i = 0;
    while x <= w {
        let c = if i % 5 == 0 { oklch(0.62, 0.05, 230.0).alpha(0.28) } else { oklch(0.62, 0.05, 230.0).alpha(0.12) };
        s = s.line([x, 0.0], [x, h], 0.8, LineCap::Butt, c);
        x += 10.0;
        i += 1;
    }
    let mut y = 0.0;
    i = 0;
    while y <= h {
        let c = if i % 5 == 0 { oklch(0.62, 0.05, 230.0).alpha(0.28) } else { oklch(0.62, 0.05, 230.0).alpha(0.12) };
        s = s.line([0.0, y], [w, y], 0.8, LineCap::Butt, c);
        y += 10.0;
        i += 1;
    }
    s
}

/// The worst frame in each second of the capture, plotted with axes and two guide lines.
fn frame_chart(rep: &Value) -> Option<El> {
    let points: Vec<f32> = rep["timeline"].as_array()?.iter().map(|p| p["frameMaxMs"].as_f64().unwrap_or(0.0) as f32).collect();
    if points.is_empty() {
        return None;
    }
    let (w, h) = (560.0_f32, 200.0_f32);
    let top = (points.iter().cloned().fold(50.0_f32, f32::max) / 10.0).ceil() * 10.0;
    let y_of = |ms: f32| h - ms / top * h;
    let mut s = graph_paper(w, h);
    let bw = w / points.len() as f32;
    for (i, v) in points.iter().enumerate() {
        let x = i as f32 * bw + bw * 0.2;
        let c = if *v > 50.0 { rust() } else if *v > 34.0 { ochre() } else { graphite() };
        let box_ = vec![[x, y_of(*v)], [x + bw * 0.6, y_of(*v)], [x + bw * 0.6, h], [x, h]];
        s = s.fill(box_.clone(), c.alpha(0.22));
        let mut outline = box_;
        outline.push(outline[0]);
        s = s.stroke(outline, 1.1, c);
    }
    for ms in [16.7_f32, 33.3] {
        let y = y_of(ms);
        let mut x = 0.0;
        while x < w {
            s = s.line([x, y], [x + 6.0, y], 1.0, LineCap::Butt, ink());
            x += 10.0;
        }
    }
    s = s.line([0.0, 0.0], [0.0, h], 1.4, LineCap::Butt, ink()).line([0.0, h], [w, h], 1.4, LineCap::Butt, ink());
    let mut chart = el().w(w + 60.0).h(h + 30.0).child(canvas(s).size(w, h).pin(Some(40.0), Some(0.0), None, None));
    for ms in [0.0_f32, top / 2.0, top] {
        chart = chart.child(text(format!("{ms:.0}")).font(theme::mono(11.0)).color(graphite()).w(34.0).align(rune::Align::End).pin(Some(0.0), Some(y_of(ms) - 8.0), None, None));
    }
    for (ms, name) in [(16.7_f32, "60 fps"), (33.3, "30 fps")] {
        chart = chart.child(text(name).font(theme::italic(13.0)).color(ink()).pin(Some(40.0 + w - 52.0), Some(y_of(ms) - 19.0), None, None));
    }
    chart = chart.child(text("seconds into the capture").font(theme::italic(13.0)).color(graphite()).pin(Some(40.0), Some(h + 8.0), None, None));
    Some(col().gap(10.0).child(theme::heading("Worst frame each second", 19.0)).child(text("milliseconds").font(theme::italic(13.0)).color(graphite())).child(chart))
}

/// Origins as the patterns a technical drawing uses, not colours.
#[derive(Clone, Copy, PartialEq)]
enum Fill {
    Solid,
    Hatch,
    Cross,
    Dots,
}

fn origin(name: &str) -> (Fill, &'static str) {
    if name.starts_with("net.minecraft") || name.starts_with("com.mojang") {
        (Fill::Hatch, "Minecraft")
    } else if name.starts_with("java.") || name.starts_with("jdk.") || name.starts_with("sun.") {
        (Fill::Dots, "Java")
    } else if name.starts_with("org.lwjgl") || name.starts_with("io.netty") || name.starts_with("com.google") || name.starts_with("it.unimi") {
        (Fill::Cross, "Libraries")
    } else {
        (Fill::Solid, "Mods")
    }
}

/// Lines at 45° (or -45°) across a rectangle, clipped to it.
fn hatch(mut s: Shape, r: [f32; 4], rising: bool, gap: f32, c: Color) -> Shape {
    let [x, y, w, h] = r;
    let mut k = -h;
    while k < w {
        let t0 = (-k / h).max(0.0);
        let t1 = ((w - k) / h).min(1.0);
        if t0 < t1 {
            let at = |t: f32| [x + k + h * t, if rising { y + h - h * t } else { y + h * t }];
            s = s.line(at(t0), at(t1), 0.8, LineCap::Butt, c);
        }
        k += gap;
    }
    s
}

fn pattern(s: Shape, fill: Fill, r: [f32; 4]) -> Shape {
    let c = ink().alpha(0.45);
    match fill {
        Fill::Solid => s.fill(vec![[r[0], r[1]], [r[0] + r[2], r[1]], [r[0] + r[2], r[1] + r[3]], [r[0], r[1] + r[3]]], moss().alpha(0.30)),
        Fill::Hatch => hatch(s, r, false, 7.0, c),
        Fill::Cross => hatch(hatch(s, r, false, 9.0, c), r, true, 9.0, c),
        Fill::Dots => {
            let mut s = s;
            let mut y = r[1] + 4.0;
            while y < r[1] + r[3] {
                let mut x = r[0] + 4.0;
                while x < r[0] + r[2] {
                    s = s.fill(vec![[x, y], [x + 1.6, y], [x + 1.6, y + 1.6], [x, y + 1.6]], c);
                    x += 7.0;
                }
                y += 7.0;
            }
            s
        }
    }
}

fn treemap(rep: &Value) -> Option<El> {
    let items = hot_classes(rep);
    if items.is_empty() {
        return None;
    }
    let (w, h) = (900.0_f32, 300.0_f32);
    let rects = squarify(&items, 0.0, 0.0, w, h);
    let mut shape = Shape::new();
    for (item, r) in items.iter().zip(&rects) {
        let inner = [r[0] + 1.0, r[1] + 1.0, (r[2] - 2.0).max(0.0), (r[3] - 2.0).max(0.0)];
        shape = pattern(shape, item.fill, inner);
        shape = shape.stroke(vec![[r[0], r[1]], [r[0] + r[2], r[1]], [r[0] + r[2], r[1] + r[3]], [r[0], r[1] + r[3]], [r[0], r[1]]], 1.3, ink());
    }
    let mut map = el().key("treemap").w(w).h(h).child(canvas(shape).size(w, h).pin(Some(0.0), Some(0.0), None, None));
    for (item, r) in items.iter().zip(rects) {
        if r[2] > 90.0 && r[3] > 52.0 {
            map = map.child(
                col()
                    .pin(Some(r[0] + 8.0), Some(r[1] + 8.0), None, None)
                    .w(r[2] - 16.0)
                    .gap(1.0)
                    .child(row().gap(6.0).child(text(format!("{:.0}%", item.weight)).font(theme::serif(18.0)).color(ink())))
                    .child(text(item.name.clone()).font(theme::sans(14.0, 700.0)).color(ink()).truncate().w_full())
                    .child(text(item.detail.clone()).font(theme::mono(11.0)).color(graphite()).truncate().w_full()),
            );
        } else if r[2] > 34.0 && r[3] > 22.0 {
            map = map.child(text(format!("{:.0}%", item.weight)).font(theme::sans(12.0, 600.0)).color(ink()).pin(Some(r[0] + 5.0), Some(r[1] + 4.0), None, None));
        }
    }
    let mut legend = row().gap(22.0);
    for (fill, name) in [(Fill::Solid, "Mods"), (Fill::Hatch, "Minecraft"), (Fill::Cross, "Libraries"), (Fill::Dots, "Java")] {
        let swatch = pattern(Shape::new(), fill, [0.0, 0.0, 18.0, 14.0]).stroke(vec![[0.0, 0.0], [18.0, 0.0], [18.0, 14.0], [0.0, 14.0], [0.0, 0.0]], 1.0, ink());
        legend = legend.child(row().gap(7.0).center_y().child(canvas(swatch).size(18.0, 14.0)).child(text(name).font(theme::sans(14.0, 400.0)).color(ink())));
    }
    Some(
        col()
            .gap(12.0)
            .child(row().w_full().items_end().child(theme::heading("Where the time went, by class", 19.0)).child(el().grow(1.0)).child(legend))
            .child(map),
    )
}

fn origins(rep: &Value) -> Option<El> {
    let list = rep["selfSamplesByOrigin"].as_array()?;
    let mut c = col().w_full().gap(8.0).child(theme::heading("Own time by origin", 19.0));
    for o in list {
        let pct = o["percent"].as_f64().unwrap_or(0.0);
        c = c.child(
            row()
                .w_full()
                .gap(8.0)
                .items_end()
                .child(text(o["name"].as_str().unwrap_or("").to_string()).font(theme::sans(15.0, 400.0)).color(ink()).truncate().max_w(240.0))
                .child(theme::leader())
                .child(text(format!("{pct:.0}%")).font(theme::mono(14.0)).color(ink())),
        );
    }
    Some(c)
}

fn numbers(rep: &Value) -> El {
    let f = |v: &Value| v.as_f64().unwrap_or(0.0);
    let fps = f(&rep["fps"]);
    let fps_c = if fps >= 55.0 { moss() } else if fps >= 28.0 { ochre() } else { rust() };
    let line = |name: &str, value: String| {
        row()
            .w_full()
            .gap(8.0)
            .items_end()
            .child(text(name.to_string()).font(theme::sans(15.0, 400.0)).color(graphite()))
            .child(theme::leader())
            .child(text(value).font(theme::mono(14.0)).color(ink()))
    };
    row()
        .w_full()
        .gap(40.0)
        .items_start()
        .child(
            col()
                .gap(2.0)
                .child(row().gap(8.0).items_end().child(text(format!("{fps:.0}")).key("fps").font(theme::serif(72.0)).color(fps_c)).child(text("frames a second").font(theme::italic(17.0)).color(graphite()).pb(14.0)))
                .child(theme::note("averaged over the capture")),
        )
        .child(
            col()
                .grow(1.0)
                .min_w(0.0)
                .gap(8.0)
                .pt(10.0)
                .child(line("Slowest 1% of frames", format!("{:.0} fps", f(&rep["onePercentLowFps"]))))
                .child(line("Frame time, 95th percentile", format!("{:.1} ms", f(&rep["metrics"]["frame_ms"]["p95"]))))
                .child(line("Memory in use, at most", format!("{:.0} MiB", f(&rep["metrics"]["heap_used_bytes"]["max"]) / 1_048_576.0)))
                .child(line("Allocation", format!("{:.0} MiB/s", f(&rep["estimatedAllocationBytesPerSecond"]) / 1_048_576.0)))
                .child(line("Garbage collection pauses", format!("{:.1} ms", f(&rep["gcPauseTotalMs"])))),
        )
}

pub fn view(s: &App) -> El {
    let busy = s.busy();
    let p = &s.perf;
    let mut setup = col()
        .key("perf-setup")
        .w(420.0)
        .padding(26.0)
        .gap(16.0)
        .fill(paper())
        .child(theme::heading("Measure a game", 26.0))
        .child(theme::body("Start Minecraft the way you play it, with your mods, then find it here. What you do while it measures is what gets measured.", 15.0, graphite()))
        .child(row().gap(10.0).child(theme::button("perf-find", "Find running games", if p.processes.is_some() { Kind::Plain } else { Kind::Primary }, !busy, |s: &mut App, _| find(s))));
    if let Some(e) = &p.list_error {
        setup = setup.child(theme::body(e.clone(), 15.0, rust()));
    }
    setup = setup.child(machines(s));
    if p.processes.as_ref().is_some_and(|l| !l.is_empty()) {
        setup = setup
            .child(durations(s))
            .child(row().child(theme::button("perf-start", "Start measuring", Kind::Primary, !busy && p.chosen.is_some(), |s: &mut App, _| capture(s))));
    }
    let setup = theme::resting(setup, 1.0).rotate(0.006);
    // After a measurement the picker folds into one line; the sheet is what matters now.
    let compact = |o: &Outcome| {
        let pid = o.result()["pid"].as_str().map(str::to_string).or_else(|| p.chosen.clone()).unwrap_or_default();
        let seconds = DURATIONS[p.duration.min(DURATIONS.len() - 1)].0;
        theme::resting(
            row()
                .key("perf-setup")
                .w_full()
                .px(24.0)
                .py(14.0)
                .gap(14.0)
                .center_y()
                .fill(paper())
                .child(text(format!("Measured Minecraft, pid {pid}, for {seconds}.")).font(theme::sans(16.0, 500.0)).color(ink()).grow(1.0))
                .child(theme::button("perf-again", "Measure again", Kind::Plain, !busy && p.chosen.is_some(), |s: &mut App, _| capture(s)))
                .child(theme::button("perf-choose", "Choose another game", Kind::Plain, !busy, |s: &mut App, _| s.perf.choosing = true)),
            0.6,
        )
    };
    let setup = match &p.result {
        Some(o) if o.ok() && !p.choosing => compact(o),
        _ => setup,
    };
    let Some(o) = &p.result else {
        return row().w_full().gap(32.0).items_start().child(setup).child(col().grow(1.0).pt(30.0).child(theme::note("The measuring sheet goes here once a capture is done.")));
    };
    if !o.ok() {
        let failed = theme::sheet().grow(1.0).min_w(0.0).padding(26.0).gap(10.0).child(row().w_full().items_start().child(theme::heading("The measurement stopped", 22.0)).child(el().grow(1.0)).child(theme::stamp("Failed", rust(), 18.0))).child(theme::body(o.summary(), 15.0, ink()));
        return row().w_full().gap(32.0).items_start().child(setup).child(failed);
    }
    let mut sheet = theme::sheet().key("perf-result").w_full().padding(32.0).gap(30.0);
    if let Some(rep) = &p.report {
        sheet = sheet.child(numbers(rep));
        let mut mid = row().w_full().gap(36.0).items_start();
        if let Some(chart) = frame_chart(rep) {
            mid = mid.child(chart);
        }
        if let Some(org) = origins(rep) {
            mid = mid.child(col().grow(1.0).min_w(0.0).child(org));
        }
        sheet = sheet.child(mid);
        if let Some(t) = cost_by_mod(rep) {
            sheet = sheet.child(t);
        }
        if let Some(t) = treemap(rep) {
            sheet = sheet.child(t);
        }
    }
    if let Some(path) = html(o) {
        sheet = sheet.child(
            row()
                .gap(14.0)
                .center_y()
                .child(theme::button("perf-open", "Open the full report", Kind::Primary, true, move |s: &mut App, _| s.notice = crate::cli::open_path(&path).err()))
                .child(text(o.result()["html"].as_str().unwrap_or("").to_string()).font(theme::mono(12.0)).color(graphite()).truncate().grow(1.0)),
        );
    }
    col().w_full().gap(28.0).child(setup).child(sheet)
}

/// What each mod costs, from the report's attribution: its share of samples, its time per frame
/// with the interval the sampling allows, and its allocation, with the features it spends it in.
fn cost_by_mod(rep: &Value) -> Option<El> {
    let a = &rep["attribution"];
    let mods = a["mods"].as_array()?;
    let f = |v: &Value| v.as_f64().unwrap_or(0.0);
    let cell = |words: String, w: f32, end: bool| {
        let t = text(words).font(theme::mono(13.0)).color(ink()).w(w);
        if end { t.align(rune::Align::End) } else { t }
    };
    let head = |words: &str, w: f32| text(words.to_string()).font(theme::sans(13.0, 600.0)).color(graphite()).w(w).align(rune::Align::End);
    let mut table = col()
        .key("cost-by-mod")
        .w_full()
        .gap(0.0)
        .child(
            row()
                .w_full()
                .pb(6.0)
                .gap(12.0)
                .child(text("Mod").font(theme::sans(13.0, 600.0)).color(graphite()).grow(1.0))
                .child(head("Share", 70.0))
                .child(head("Per frame", 150.0))
                .child(head("Allocation", 110.0)),
        )
        .child(el().w_full().h(1.2).fill(ink()));
    if mods.is_empty() {
        table = table.child(theme::note("No sampled time could be tied to a particular mod in this capture."));
    }
    for m in mods.iter().take(10) {
        let yours = m["yourMod"].as_bool() == Some(true);
        let name = m["name"].as_str().or(m["id"].as_str()).unwrap_or("").to_string();
        let per_frame = format!("{:.1} ms ({:.1}–{:.1})", f(&m["perFrameMs"]), f(&m["perFrameMsLow"]), f(&m["perFrameMsHigh"]));
        let mut who = row().gap(8.0).center_y().grow(1.0).min_w(0.0).child(text(name).font(theme::sans(15.0, if yours { 700.0 } else { 400.0 })).color(ink()).truncate());
        if yours {
            who = who.child(text("yours").font(theme::italic(14.0)).color(moss()));
        }
        let mut entry = col().w_full().py(8.0).gap(4.0).child(
            row()
                .w_full()
                .gap(12.0)
                .center_y()
                .child(who)
                .child(cell(format!("{:.1}%", f(&m["percentOfSamples"])), 70.0, true))
                .child(cell(per_frame, 150.0, true))
                .child(cell(format!("{:.0} MiB/s", f(&m["allocationBytesPerSecond"]) / 1_048_576.0), 110.0, true)),
        );
        for feat in m["features"].as_array().into_iter().flatten().take(3) {
            entry = entry.child(
                text(format!("{}  {:.1}%", feat["name"].as_str().unwrap_or(""), f(&feat["percentOfSamples"])))
                    .font(theme::italic(14.0))
                    .color(graphite())
                    .pl(16.0)
                    .truncate()
                    .w_full(),
            );
        }
        table = table.child(entry).child(el().w_full().h(1.0).fill(theme::rule()));
    }
    let tied = format!(
        "{} of {} samples could be tied to a mod; the rest is the game, Java and libraries.",
        a["attributedSamples"].as_i64().unwrap_or(0),
        a["executionSamples"].as_i64().unwrap_or(0)
    );
    Some(col().w_full().gap(10.0).child(theme::heading("Cost by mod", 19.0)).child(theme::note(tied)).child(table))
}
