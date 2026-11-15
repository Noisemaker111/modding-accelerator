//! "Change version": the road between two Minecraft versions drawn on map paper (family hubs as
//! rings, the updates between them as dots), inked as `modaccel port` previews and applies it. Preview
//! runs without `--fix` (nothing is edited), Apply with `--fix`, Undo hands back its transaction.

use rune::{Color, El, LineCap, LoopSpec, Path, Shape, TextEdit, canvas, col, el, oklch, row, text};
use serde_json::Value;

use crate::cli::Outcome;
use crate::theme::{self, Kind, graphite, ink, moss, ochre, paper, pencil, rule, rust};
use crate::{App, Purpose};

/// Versions people move between most; any other can be typed.
const COMMON: [&str; 9] = ["1.20.1", "1.21.1", "1.21.4", "1.21.5", "1.21.6", "1.21.11", "26.1", "26.2", "26.3"];

pub struct PortWizard {
    pub from: TextEdit,
    pub to: TextEdit,
    pub preview: Option<Outcome>,
    pub applied: Option<Outcome>,
    pub undo: Option<String>,
    pub undone: Option<Outcome>,
    /// `convert`: the move to the other loader on the same Minecraft version, and its undo.
    pub converted: Option<Outcome>,
    pub convert_undone: Option<Outcome>,
}

impl Default for PortWizard {
    fn default() -> Self {
        PortWizard { from: TextEdit::new(""), to: TextEdit::new(""), preview: None, applied: None, undo: None, undone: None, converted: None, convert_undone: None }
    }
}

impl PortWizard {
    /// The project's own Minecraft version, from doctor, as the starting point.
    pub fn prefill(&mut self, doctor: &Outcome) {
        if !self.from.text.is_empty() {
            return;
        }
        let builds = doctor.result()["builds"].as_array().cloned().unwrap_or_default();
        let main = builds.iter().find(|b| b["directory"].as_str() == Some("")).or(builds.first());
        if let Some(mc) = main.and_then(|b| b["minecraftHint"].as_str()) {
            self.from = TextEdit::new(mc);
        }
    }

    fn versions(&self) -> Option<(String, String)> {
        let (from, to) = (self.from.text.trim().to_string(), self.to.text.trim().to_string());
        (!from.is_empty() && !to.is_empty() && from != to).then_some((from, to))
    }
}

fn port_args(s: &App, fix: bool) -> Option<Vec<String>> {
    let (from, to) = s.port.versions()?;
    let mut args = vec!["port".to_string()];
    args.extend(s.root_args()?);
    args.extend(["--from".into(), from, "--to".into(), to]);
    if fix {
        args.push("--fix".into());
    }
    Some(args)
}

fn preview(s: &mut App) {
    if let Some(args) = port_args(s, false) {
        s.port.preview = None;
        s.port.applied = None;
        s.port.undone = None;
        s.start("Previewing the port", Purpose::PortPreview, args);
    }
}

fn apply(s: &mut App) {
    if let Some(args) = port_args(s, true) {
        s.port.applied = None;
        s.port.undone = None;
        s.start("Porting", Purpose::PortApply, args);
    }
}

fn undo(s: &mut App) {
    let (Some(id), Some(root)) = (s.port.undo.clone(), s.root_args()) else { return };
    let mut args = vec!["port".to_string()];
    args.extend(root);
    args.extend(["--undo".into(), id]);
    s.start("Undoing the port", Purpose::PortUndo, args);
}

fn changed_lines(before: &str, after: &str) -> (Vec<String>, Vec<String>) {
    let b: Vec<&str> = before.lines().collect();
    let a: Vec<&str> = after.lines().collect();
    let removed = b.iter().filter(|l| !a.contains(l)).map(|l| l.to_string()).collect();
    let added = a.iter().filter(|l| !b.contains(l)).map(|l| l.to_string()).collect();
    (removed, added)
}


/// How the route is inked: pencil until previewed, ink once it is known, then the result's colour.
#[derive(Clone, Copy, PartialEq)]
enum Ink {
    Pencil,
    Travelling,
    Known(Color),
}

/// The route's versions and which are hubs: from the preview or apply answer, else just the ends.
fn route(s: &App) -> (Vec<String>, Vec<String>) {
    let answer = s.port.applied.as_ref().or(s.port.preview.as_ref()).filter(|o| o.result()["route"]["versions"].is_array());
    let strings = |v: &Value| v.as_array().map(|a| a.iter().filter_map(Value::as_str).map(str::to_string).collect::<Vec<_>>()).unwrap_or_default();
    match answer {
        Some(o) => (strings(&o.result()["route"]["versions"]), strings(&o.result()["route"]["waypoints"])),
        None => {
            let (f, t) = (s.port.from.text.trim().to_string(), s.port.to.text.trim().to_string());
            let ends: Vec<String> = [f, t].into_iter().filter(|v| !v.is_empty()).collect();
            (ends.clone(), ends)
        }
    }
}

/// What happened to the port, as a word and a colour (None before it was applied).
fn outcome_word(w: &PortWizard) -> Option<(&'static str, Color)> {
    let a = w.applied.as_ref()?;
    Some(if w.undone.as_ref().is_some_and(Outcome::ok) {
        ("Undone", graphite())
    } else if a.ok() {
        ("Ported", moss())
    } else if w.undo.is_some() {
        ("Needs a hand", ochre())
    } else {
        ("Failed", rust())
    })
}

const MAP_W: f32 = 620.0;
const MAP_H: f32 = 210.0;

/// Where each version sits on the map: spread across, rising and falling a little like a road.
fn places(n: usize) -> Vec<[f32; 2]> {
    let margin = 40.0;
    (0..n)
        .map(|i| {
            let t = if n > 1 { i as f32 / (n - 1) as f32 } else { 0.5 };
            let x = margin + t * (MAP_W - 2.0 * margin);
            let y = MAP_H * 0.52 + 34.0 * (i as f32 * 1.7 + 0.6).sin() * if n > 2 { 1.0 } else { 0.3 };
            [x, y]
        })
        .collect()
}

/// A smooth road through the places.
fn road(p: &[[f32; 2]]) -> Path {
    let mut path = Path::new().move_to(p[0]);
    for w in p.windows(2) {
        let (a, b) = (w[0], w[1]);
        let dx = (b[0] - a[0]) * 0.45;
        path = path.cubic_to([a[0] + dx, a[1]], [b[0] - dx, b[1]], b);
    }
    path
}

/// Faint contour lines, as on a survey map.
fn contours() -> Shape {
    let mut s = Shape::new();
    for k in 0..6 {
        let base = 22.0 + k as f32 * 34.0;
        let pts: Vec<[f32; 2]> = (0..=40)
            .map(|i| {
                let x = i as f32 * MAP_W / 40.0;
                [x, base + 9.0 * (x * 0.011 + k as f32).sin() + 5.0 * (x * 0.027 + k as f32 * 2.0).sin()]
            })
            .collect();
        s = s.stroke(pts, 1.0, oklch(0.70, 0.04, 90.0).alpha(0.22));
    }
    s
}

fn circle(c: [f32; 2], r: f32) -> Vec<[f32; 2]> {
    (0..24).map(|i| {
        let a = i as f32 / 24.0 * 6.283;
        [c[0] + r * a.cos(), c[1] + r * a.sin()]
    }).collect()
}

fn map(s: &App) -> El {
    let (versions, hubs) = route(s);
    let running = matches!(s.running_purpose(), Some(Purpose::PortPreview | Purpose::PortApply | Purpose::PortUndo));
    let ink_state = if running {
        Ink::Travelling
    } else if let Some((_, c)) = outcome_word(&s.port) {
        Ink::Known(c)
    } else if s.port.preview.as_ref().is_some_and(Outcome::ok) {
        Ink::Known(ink())
    } else {
        Ink::Pencil
    };
    let mut paper_map = el().key("route-map").w(MAP_W).h(MAP_H).child(canvas(contours()).size(MAP_W, MAP_H).pin(Some(0.0), Some(0.0), None, None));
    if versions.len() >= 2 {
        let p = places(versions.len());
        let path = road(&p);
        let line_c = match ink_state {
            Ink::Pencil => pencil(),
            Ink::Travelling => graphite(),
            Ink::Known(c) => c,
        };
        let mut shape = Shape::new();
        if ink_state == Ink::Pencil {
            // Not planned yet: a dashed pencil guess between the two ends.
            let (a, b) = (p[0], p[p.len() - 1]);
            for k in (0..40).step_by(2) {
                let t0 = k as f32 / 40.0;
                let t1 = (k + 1) as f32 / 40.0;
                shape = shape.line([a[0] + (b[0] - a[0]) * t0, a[1] + (b[1] - a[1]) * t0], [a[0] + (b[0] - a[0]) * t1, a[1] + (b[1] - a[1]) * t1], 1.6, LineCap::Round, line_c);
            }
        } else {
            shape = shape.stroke_path(&path, 2.6, LineCap::Round, line_c);
        }
        let last = p.len() - 1;
        for (i, at) in p.iter().enumerate() {
            let hub = hubs.contains(&versions[i]) || i == 0 || i == last;
            if i == 0 {
                shape = shape.fill(circle(*at, 8.0), ink());
            } else if i == last {
                // A flag on a pole at the destination.
                let flag_c = match ink_state {
                    Ink::Known(c) if c != ink() => c,
                    _ => rust(),
                };
                shape = shape
                    .line([at[0], at[1]], [at[0], at[1] - 34.0], 1.8, LineCap::Round, ink())
                    .fill(vec![[at[0], at[1] - 34.0], [at[0] + 22.0, at[1] - 28.0], [at[0], at[1] - 21.0]], flag_c)
                    .fill(circle(*at, 4.0), ink());
            } else if hub && ink_state != Ink::Pencil {
                shape = shape.fill(circle(*at, 7.0), paper()).stroke({ let mut c = circle(*at, 7.0); c.push(c[0]); c }, 2.0, ink());
            } else if ink_state != Ink::Pencil {
                shape = shape.fill(circle(*at, 3.2), ink());
            }
        }
        paper_map = paper_map.child(canvas(shape).size(MAP_W, MAP_H).pin(Some(0.0), Some(0.0), None, None));
        // Labels beside each place, above or below so they don't collide.
        for (i, at) in p.iter().enumerate() {
            let hub = hubs.contains(&versions[i]) || i == 0 || i == last;
            if !hub && ink_state == Ink::Pencil {
                continue;
            }
            let below = i == 0 || i == last || i % 2 == 0;
            let note_word = if i == 0 { "now" } else if i == last { "goal" } else if hub { "hub" } else { "" };
            let label = col()
                .items_center()
                .w(90.0)
                .gap(1.0)
                .child(text(versions[i].clone()).font(if hub { theme::sans(16.0, 700.0) } else { theme::sans(13.0, 400.0) }).color(if hub { ink() } else { graphite() }))
                .child(text(note_word).font(theme::italic(13.0)).color(graphite()));
            let y = if below { at[1] + 14.0 } else { at[1] - 56.0 };
            paper_map = paper_map.child(label.pin(Some(at[0] - 45.0), Some(y), None, None));
        }
        // While it runs: dots along the road, lit one after another.
        if ink_state == Ink::Travelling {
            let samples = 18;
            for k in 0..samples {
                let t = k as f32 / (samples - 1) as f32 * last as f32;
                let i = (t.floor() as usize).min(last - 1);
                let f = t - i as f32;
                let (a, b) = (p[i], p[i + 1]);
                let at = [a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f];
                paper_map = paper_map.child(
                    el().pin(Some(at[0] - 3.5), Some(at[1] - 3.5), None, None)
                        .size(7.0, 7.0)
                        .radius(4.0)
                        .fill(ochre())
                        .looping(LoopSpec::opacity(1.6, k as f64 * 1.6 / samples as f64, 0.0, 1.0)),
                );
            }
        }
    }
    let mut sheet = theme::sheet()
        .key("map-sheet")
        .w_full()
        .padding(28.0)
        .gap(10.0)
        .child(theme::heading("The way there", 26.0))
        .child(route_caption(s, &versions, &hubs))
        .child(row().w_full().justify_center().child(paper_map));
    if let Some((word, c)) = outcome_word(&s.port) {
        sheet = sheet.child(el().pin(None, Some(22.0), Some(26.0), None).child(theme::stamp(word, c, 20.0)));
    }
    sheet
}

fn route_caption(s: &App, versions: &[String], hubs: &[String]) -> El {
    let answer = s.port.applied.as_ref().or(s.port.preview.as_ref());
    let linked: Vec<String> = answer
        .and_then(|o| o.result()["route"]["hops"].as_array())
        .map(|hops| hops.iter().flat_map(|h| h["steps"].as_array().cloned().unwrap_or_default()).filter_map(|st| st["linked"].as_str().map(str::to_string)).collect())
        .unwrap_or_default();
    let words = if versions.len() < 2 {
        "Pick where it is going.".to_string()
    } else if linked.is_empty() {
        format!("From {} to {}. Preview it to draw the road, through the versions most mods use.", versions[0], versions[versions.len() - 1])
    } else {
        let hubs_between = hubs.len().saturating_sub(2);
        let by = if linked.iter().all(|l| l == "exact") { "Names carry over by their shared ids." } else { "Past 26.1 names are matched by name, not by id." };
        let updates = match versions.len() - 1 {
            1 => "One update, straight there.".to_string(),
            n if hubs_between == 0 => format!("{n} updates, no hubs between."),
            n if hubs_between == 1 => format!("{n} updates, through one family hub."),
            n => format!("{n} updates, through {hubs_between} family hubs."),
        };
        format!("{updates} {by}")
    };
    theme::note(words)
}

/// The slip where you say where it's going, and the steps it takes.
fn planner(s: &App) -> El {
    let busy = s.busy();
    let w = &s.port;
    let previewed = w.preview.as_ref().is_some_and(Outcome::ok);
    let field = |key: &str, edit: &TextEdit, hint: &str, access: fn(&mut App) -> &mut TextEdit, name: &str| {
        col()
            .w(104.0)
            .gap(4.0)
            .child(text(name.to_string()).font(theme::sans(14.0, 600.0)).color(graphite()))
            .child(rune::text_input(edit, theme::mono(16.0)).key(key.to_string()).single_line().py(4.0).color(ink()).placeholder(hint.to_string(), pencil()).label(name.to_string()).on_edit(access))
            .child(el().w_full().h(1.0).fill(graphite()))
    };
    let mut common = row().gap(14.0).flex_wrap();
    for v in COMMON {
        if w.from.text.trim() == v {
            continue;
        }
        common = common.child(theme::option(&format!("to-{v}"), v, w.to.text.trim() == v, move |s: &mut App| s.port.to = TextEdit::new(v)));
    }
    let steps = [
        ("Pick the versions", w.versions().is_some()),
        ("Preview what changes", previewed),
        ("Apply it", w.applied.is_some()),
        ("Check it still works", false),
    ];
    let mut list = col().gap(8.0);
    for (i, (name, done)) in steps.iter().enumerate() {
        list = list.child(
            row()
                .gap(12.0)
                .center_y()
                .child(text((i + 1).to_string()).font(theme::serif(18.0)).color(if *done { moss() } else { pencil() }).w(14.0))
                .child(text(name.to_string()).font(theme::sans(15.0, if *done { 400.0 } else { 500.0 })).color(if *done { graphite() } else { ink() }))
                .child(if *done { text("done").font(theme::italic(14.0)).color(moss()) } else { el() }),
        );
    }
    let mut buttons = row().gap(10.0).flex_wrap();
    buttons = buttons
        .child(theme::button("port-preview", "Preview", if previewed { Kind::Plain } else { Kind::Primary }, !busy && w.versions().is_some(), |s: &mut App, _| preview(s)))
        .child(theme::button("port-apply", "Apply", if previewed { Kind::Primary } else { Kind::Plain }, !busy && previewed, |s: &mut App, _| apply(s)));
    theme::resting(
        col()
            .key("planner")
            .w(330.0)
            .padding(24.0)
            .gap(18.0)
            .fill(paper())
            .child(theme::heading("Change version", 26.0))
            .child(row().gap(14.0).items_end().child(field("port-from", &w.from, "1.21.5", |s| &mut s.port.from, "From")).child(text("to").font(theme::italic(16.0)).color(graphite()).pb(6.0)).child(field("port-to", &w.to, "1.21.6", |s| &mut s.port.to, "To")))
            .child(col().gap(6.0).child(text("Often moved to").font(theme::sans(14.0, 600.0)).color(graphite())).child(common))
            .child(buttons)
            .child(el().w_full().h(1.0).fill(rule()))
            .child(list),
        1.0,
    )
    .rotate(0.008)
}

fn changes(s: &App, o: &Outcome) -> Option<El> {
    let list = o.result()["edits"].as_array()?;
    if list.is_empty() {
        return None;
    }
    let root = s.project.as_ref().map(|p| p.display().to_string()).unwrap_or_default();
    let mut c = theme::sheet().key("changes").w_full().padding(28.0).gap(18.0).child(theme::heading(format!("What would change, in {} files", list.len()), 22.0));
    for e in list.iter().take(12) {
        let file = e["file"].as_str().unwrap_or("");
        let file = file.strip_prefix(&root).unwrap_or(file).trim_start_matches(['\\', '/']);
        let (removed, added) = changed_lines(e["before"].as_str().unwrap_or(""), e["after"].as_str().unwrap_or(""));
        let mut lines = col().w_full().gap(3.0);
        for l in removed.iter().take(8) {
            lines = lines.child(text(format!("−  {}", l.trim())).font(theme::mono(13.0)).color(rust()).truncate().w_full());
        }
        for l in added.iter().take(8) {
            lines = lines.child(text(format!("+  {}", l.trim())).font(theme::mono(13.0)).color(moss()).truncate().w_full());
        }
        c = c.child(col().w_full().gap(8.0).child(text(file.to_string()).font(theme::sans(15.0, 600.0)).color(ink())).child(lines));
    }
    Some(c)
}

pub fn view(s: &App) -> El {
    let busy = s.busy();
    let w = &s.port;
    if s.project.is_none() {
        return col().gap(16.0).child(theme::heading("Change version", 30.0)).child(theme::note("Put your mod on the bench first.")).child(theme::button("open-first", "Go to the bench", Kind::Primary, true, |s: &mut App, _| s.screen = crate::Screen::Project));
    }
    let mut left = col().grow(1.0).min_w(0.0).gap(24.0).child(map(s));
    if let Some(p) = &w.preview {
        if !p.ok() {
            left = left.child(
                theme::sheet()
                    .w_full()
                    .padding(26.0)
                    .gap(10.0)
                    .child(row().w_full().items_start().child(theme::heading("The preview stopped", 22.0)).child(el().grow(1.0)).child(theme::stamp("Failed", rust(), 18.0)))
                    .child(text(p.summary()).font(theme::mono(13.0)).color(ink()).wrap_anywhere().w_full().selectable(true)),
            );
        } else if let Some(c) = changes(s, p) {
            left = left.child(c);
        }
    }
    let mut right = col().w(330.0).gap(24.0).child(planner(s)).child(loader_slip(s));
    if let Some(a) = &w.applied {
        let r = a.result();
        let mut facts = Vec::new();
        for (key, yes, no) in [("compiled", "It compiled", "It did not compile"), ("built", "it built", "it did not build")] {
            if let Some(b) = r[key].as_bool() {
                facts.push(if b { yes } else { no });
            }
        }
        let mut slip = col()
            .key("port-result")
            .w_full()
            .padding(24.0)
            .gap(14.0)
            .fill(paper())
            .child(theme::heading(outcome_word(w).map(|o| o.0).unwrap_or("Port"), 24.0));
        if !facts.is_empty() {
            slip = slip.child(theme::body(format!("{}.", facts.join(", and ")), 15.0, ink()));
        }
        slip = slip.child(theme::body(a.summary(), 15.0, graphite()));
        if let Some(u) = &w.undone {
            slip = slip.child(theme::note(u.summary()));
        }
        slip = slip.child(
            row()
                .gap(10.0)
                .child(theme::button("port-undo", "Undo", Kind::Danger, !busy && w.undo.is_some(), |s: &mut App, _| undo(s)))
                .child(theme::button("port-check", "Check it", Kind::Primary, !busy, |s: &mut App, _| s.screen = crate::Screen::Checks)),
        );
        right = right.child(theme::resting(slip, 1.0));
    }
    row().w_full().gap(32.0).items_start().child(left).child(right)
}

/// The loader the mod could move to with `convert`, from what doctor read: Fabric and NeoForge swap.
fn convert_target(s: &App) -> Option<(&'static str, &'static str, &'static str)> {
    let r = s.doctor.as_ref()?.result();
    let builds = r["builds"].as_array()?;
    let main = builds.iter().find(|b| b["directory"].as_str() == Some("")).or(builds.first())?;
    match main["loaderHints"][0].as_str()? {
        "fabric" => Some(("Fabric", "NeoForge", "neoforge")),
        "neoforge" => Some(("NeoForge", "Fabric", "fabric")),
        _ => None,
    }
}

fn convert(s: &mut App) {
    let (Some((_, _, to)), Some(root)) = (convert_target(s), s.root_args()) else { return };
    let mut args = vec!["convert".to_string()];
    args.extend(root);
    args.extend(["--to".into(), to.into()]);
    s.start("Changing the loader", Purpose::Convert, args);
}

fn convert_undo(s: &mut App) {
    let Some(root) = s.root_args() else { return };
    let id = s.port.converted.as_ref().and_then(|o| o.result()["undoConvert"].as_str().map(str::to_string));
    let mut args = vec!["convert".to_string()];
    args.extend(root);
    args.push("--undo".into());
    args.extend(id);
    s.start("Undoing the loader change", Purpose::ConvertUndo, args);
}

/// The slip for moving the mod to the other loader on the same Minecraft version.
fn loader_slip(s: &App) -> El {
    let busy = s.busy();
    let w = &s.port;
    let mut slip = col().key("convert").w_full().padding(24.0).gap(12.0).fill(paper()).child(theme::heading("Change loader", 22.0));
    let Some((from, to, _)) = convert_target(s) else {
        return theme::resting(slip.child(theme::note("Moving between loaders works for Fabric and NeoForge mods. Forge and Quilt are not converted yet.")), 1.0);
    };
    let undone = w.convert_undone.as_ref().is_some_and(Outcome::ok);
    match w.converted.as_ref().filter(|_| !undone) {
        None => {
            slip = slip
                .child(theme::body(format!("{from} to {to}, on the same Minecraft version. Anything without a rule is named and left as it was, and every change can be undone."), 15.0, graphite()))
                .child(row().child(theme::button("convert-run", &format!("Convert to {to}"), Kind::Plain, !busy, |s: &mut App, _| convert(s))));
            if let Some(u) = &w.convert_undone {
                slip = slip.child(theme::note(u.summary()));
            }
        }
        Some(o) => {
            let r = o.result();
            let left = r["left"].as_array().cloned().unwrap_or_default();
            let changed = r["changed"].as_array().map(Vec::len).unwrap_or(0);
            let removed = r["removed"].as_array().map(Vec::len).unwrap_or(0);
            let (word, c) = match (r["compiled"].as_bool(), left.is_empty(), r["error"].is_string()) {
                (_, _, true) => ("Failed", rust()),
                (Some(true), true, _) => ("Converted", moss()),
                (Some(false), _, _) => ("Doesn't compile yet", rust()),
                _ => ("Needs a hand", ochre()),
            };
            slip = slip.child(row().child(theme::stamp(word, c, 16.0)));
            if r["error"].is_string() {
                slip = slip.child(theme::body(o.summary(), 14.0, ink()));
            } else {
                let mut said = format!("{changed} files changed");
                if removed > 0 {
                    said.push_str(&format!(", {removed} removed"));
                }
                said.push('.');
                if !left.is_empty() {
                    said.push_str(&format!(" {} places have no rule yet and were left as they were:", left.len()));
                }
                slip = slip.child(theme::body(said, 15.0, ink()));
                for l in left.iter().take(6) {
                    let file = l["file"].as_str().unwrap_or("");
                    let short = file.rsplit('/').next().unwrap_or(file);
                    slip = slip.child(
                        col()
                            .w_full()
                            .gap(1.0)
                            .child(text(format!("{short}:{}", l["line"])).font(theme::mono(12.0)).color(ink()).truncate().w_full())
                            .child(text(l["what"].as_str().unwrap_or("").to_string()).font(theme::mono(11.0)).color(graphite()).truncate().w_full()),
                    );
                }
                if left.len() > 6 {
                    slip = slip.child(theme::note(format!("and {} more in the console.", left.len() - 6)));
                }
            }
            slip = slip.child(
                row()
                    .gap(10.0)
                    .child(theme::button("convert-undo", "Undo", Kind::Danger, !busy && r["undoConvert"].is_string(), |s: &mut App, _| convert_undo(s)))
                    .child(theme::button("convert-check", "Check it", Kind::Plain, !busy, |s: &mut App, _| s.screen = crate::Screen::Checks)),
            );
        }
    }
    theme::resting(slip, 1.0)
}
