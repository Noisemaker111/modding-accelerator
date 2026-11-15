//! "Modpack": `modaccel pack-check` on an export or instance folder, its mods laid out on the bench
//! as parts with the clashing ones flagged, then its proven repair (`--fix`) and undo.

use std::path::PathBuf;

use rune::{Color, El, FileDialogOptions, LineCap, LoopSpec, Shape, Tx, canvas, col, el, row, text};
use serde_json::Value;

use crate::cli::Outcome;
use crate::shell::Verdict;
use crate::theme::{self, Block, Kind, graphite, ink, ochre, paper, rule, rust};
use crate::{App, Purpose};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PackAction {
    Check,
    Fix,
    Undo,
}

#[derive(Default)]
pub struct PackState {
    pub pack: Option<PathBuf>,
    pub check: Option<Outcome>,
    pub fix: Option<Outcome>,
    pub undo: Option<Outcome>,
}

impl PackState {
    pub fn finish(&mut self, action: PackAction, outcome: Outcome) {
        match action {
            PackAction::Check => self.check = Some(outcome),
            PackAction::Fix => self.fix = Some(outcome),
            PackAction::Undo => self.undo = Some(outcome),
        }
    }

    fn is_folder(&self) -> bool {
        self.pack.as_ref().is_some_and(|p| p.is_dir())
    }
}

pub fn chosen(s: &mut App, path: PathBuf) {
    let path = std::fs::canonicalize(&path).unwrap_or(path);
    s.pack = PackState { pack: Some(crate::cli::dunce(&path)), ..Default::default() };
}

fn choose_zip(_: &mut App, tx: &mut Tx) {
    let options = FileDialogOptions { title: "Choose a modpack export".into(), ..Default::default() }.filter("Modpack", &["zip", "mrpack"]);
    tx.open_file(options, |s: &mut App, answer, _| match answer {
        Ok(Some(path)) => chosen(s, path),
        Ok(None) => {}
        Err(e) => s.notice = Some(format!("The file picker didn't open: {e}")),
    });
}

fn choose_folder(_: &mut App, tx: &mut Tx) {
    let options = FileDialogOptions { title: "Choose the instance folder (the one with mods in it)".into(), ..Default::default() };
    tx.open_folder(options, |s: &mut App, answer, _| match answer {
        Ok(Some(path)) => chosen(s, path),
        Ok(None) => {}
        Err(e) => s.notice = Some(format!("The folder picker didn't open: {e}")),
    });
}

fn run(s: &mut App, action: PackAction) {
    let Some(pack) = s.pack.pack.clone() else { return };
    let mut args = vec!["pack-check".to_string(), "--pack".into(), pack.display().to_string()];
    let title = match action {
        PackAction::Check => "Checking the modpack",
        PackAction::Fix => {
            args.push("--fix".into());
            "Repairing the modpack"
        }
        PackAction::Undo => {
            args.push("--undo".into());
            "Undoing the repair"
        }
    };
    s.start(title, Purpose::Pack(action), args);
}

/// A steady colour per mod id, so the same mod looks the same everywhere.
fn hue(id: &str) -> f32 {
    id.bytes().fold(7u32, |h, b| h.wrapping_mul(31).wrapping_add(b as u32)) as f32 % 360.0
}

/// How bad the worst finding on a jar is, in pack-check's own three words.
#[derive(Clone, Copy, PartialEq, PartialOrd)]
enum Trouble {
    None,
    Note,
    Warning,
    Problem,
}

fn severity(f: &Value) -> Trouble {
    match f["severity"].as_str() {
        Some("problem") => Trouble::Problem,
        Some("warning") => Trouble::Warning,
        _ => Trouble::Note,
    }
}

fn trouble_color(t: Trouble) -> Color {
    match t {
        Trouble::Problem => rust(),
        Trouble::Warning => ochre(),
        Trouble::Note => graphite(),
        Trouble::None => Color::TRANSPARENT,
    }
}

/// Which findings name this jar (by their `jars`, or in their words), with their numbers.
fn flags(file: &str, findings: &[Value]) -> (Trouble, Vec<usize>) {
    let mut t = Trouble::None;
    let mut numbers = Vec::new();
    for (i, f) in findings.iter().enumerate() {
        let named = f["jars"].as_array().is_some_and(|j| j.iter().any(|x| x.as_str() == Some(file))) || f["summary"].as_str().is_some_and(|s| s.contains(file));
        if named {
            let sev = severity(f);
            if sev > t {
                t = sev;
            }
            numbers.push(i + 1);
        }
    }
    (t, numbers)
}

fn finding_color(f: &Value) -> Color {
    trouble_color(severity(f))
}

/// A little flag on a pin, with the finding's number on it.
fn pin_flag(number: usize, c: Color) -> El {
    let pole = Shape::new()
        .line([3.0, 2.0], [3.0, 40.0], 1.6, LineCap::Round, ink())
        .fill((0..12).map(|i| {
            let a = i as f32 / 12.0 * 6.283;
            [3.0 + 3.0 * a.cos(), 40.0 + 3.0 * a.sin()]
        }).collect::<Vec<_>>(), ink());
    el().w(36.0)
        .h(44.0)
        .child(canvas(pole).size(8.0, 44.0).pin(Some(0.0), Some(0.0), None, None))
        .child(
            row()
                .pin(Some(4.0), Some(2.0), None, None)
                .h(20.0)
                .px(7.0)
                .center()
                .fill(c)
                .child(text(number.to_string()).font(theme::sans(13.0, 700.0)).color(paper())),
        )
}

/// One part laid on the bench: a painted block and its paper label, a little out of line.
fn part(file: &str, id: &str, findings: &[Value], index: usize) -> El {
    let (t, numbers) = flags(file, findings);
    let h = hue(id);
    let jitter = |k: u32| ((index as u32).wrapping_mul(2654435761).wrapping_add(k * 97) % 1000) as f32 / 1000.0;
    let label = theme::resting(
        col()
            .w(116.0)
            .px(8.0)
            .py(6.0)
            .gap(1.0)
            .items_center()
            .fill(paper())
            .border(if t == Trouble::None { 0.0 } else { 1.5 }, trouble_color(t))
            .child(text(id.to_string()).font(theme::sans(14.0, 600.0)).color(ink()).truncate().w_full().align(rune::Align::Center))
            .child(text(file.to_string()).font(theme::mono(10.5)).color(graphite()).truncate().w_full().align(rune::Align::Center)),
        0.5,
    )
    .rotate((jitter(1) - 0.5) * 0.08);
    let mut p = col()
        .key(format!("mod-{file}"))
        .w(132.0)
        .pt(4.0 + 16.0 * jitter(2))
        .gap(8.0)
        .items_center()
        .child(el().child(theme::block(48.0, Block::painted(h), h as u32)))
        .child(label);
    if t != Trouble::None {
        let c = trouble_color(t);
        let mut pins = row().pin(Some(70.0), Some(-6.0), None, None).gap(2.0);
        for n in numbers.iter().take(2) {
            pins = pins.child(pin_flag(*n, c));
        }
        p = p.child(pins);
    }
    p
}

fn parts(o: &Outcome) -> El {
    let r = o.result();
    let findings = r["findings"].as_array().cloned().unwrap_or_default();
    let mods = r["modFiles"].as_array().cloned().unwrap_or_default();
    let mut g = row().key("mod-grid").w_full().gap(10.0).flex_wrap().items_start();
    for (i, m) in mods.iter().enumerate() {
        let file = m["file"].as_str().unwrap_or("");
        let id = m["ids"][0].as_str().unwrap_or(file.trim_end_matches(".jar"));
        g = g.child(part(file, id, &findings, i));
    }
    g
}

/// The pack in a sentence or two.
fn summary(o: &Outcome) -> String {
    let r = o.result();
    let list = r["findings"].as_array().cloned().unwrap_or_default();
    let of = |t: Trouble| list.iter().filter(|f| severity(f) == t).count();
    let (problems, warnings, notes) = (of(Trouble::Problem), of(Trouble::Warning), of(Trouble::Note));
    let count = |n: usize, one: &str, many: &str| match n {
        0 => format!("no {many}"),
        1 => format!("one {one}"),
        2 => format!("two {many}"),
        3 => format!("three {many}"),
        n => format!("{n} {many}"),
    };
    let mut s = format!("{} mods", r["mods"]);
    if let (Some(mc), Some(loader)) = (r["minecraft"].as_str(), r["loader"].as_str()) {
        let loader = loader.replacen("neoforge", "NeoForge", 1).replacen("forge", "Forge", 1).replacen("fabric", "Fabric", 1);
        s.push_str(&format!(" for Minecraft {mc} on {loader}"));
    }
    s.push_str(&format!(
        ": {}, {} and {}.",
        count(problems, "problem", "problems"),
        count(warnings, "warning", "warnings"),
        count(notes, "note", "notes")
    ));
    // What its own server did, which is what turns warnings into problems.
    let game = &r["game"];
    if game["ran"].as_bool() == Some(true) {
        let first = game["newWorld"]["opened"].as_bool() == Some(true);
        let again = game["reopen"]["opened"].as_bool() == Some(true);
        s.push_str(match (first, again) {
            (true, true) => " On its own server the world opened, and opened again.",
            (true, false) => " On its own server the world opened once but not a second time.",
            _ => " Its own server did not finish starting a world.",
        });
    } else if let Some(b) = game["blocker"].as_str() {
        s.push_str(&format!(" {b}"));
    }
    s
}

fn findings_sheet(s: &App, o: &Outcome) -> El {
    let busy = s.busy();
    let list = o.result()["findings"].as_array().cloned().unwrap_or_default();
    let mut sheet = col().key("findings").w(360.0).padding(26.0).gap(18.0).fill(paper()).child(theme::heading("What clashes", 24.0));
    if list.is_empty() {
        sheet = sheet.child(theme::note("Nothing. The parts fit together."));
    }
    for (i, f) in list.iter().enumerate() {
        let c = finding_color(f);
        let kind = f["kind"].as_str().unwrap_or("").replace('-', " ");
        let kind = kind[..1].to_uppercase() + &kind[1..];
        sheet = sheet.child(
            row()
                .w_full()
                .gap(12.0)
                .items_start()
                .child(row().size(22.0, 22.0).center().fill(c).child(text((i + 1).to_string()).font(theme::sans(13.0, 700.0)).color(paper())))
                .child(
                    col()
                        .grow(1.0)
                        .min_w(0.0)
                        .gap(3.0)
                        .child(text(kind).font(theme::sans(15.0, 700.0)).color(ink()))
                        .child(text(f["summary"].as_str().unwrap_or("").to_string()).font(theme::sans(15.0, 400.0)).color(graphite()).wrap().w_full()),
                ),
        );
    }
    let p = &s.pack;
    sheet = sheet.child(el().w_full().h(1.0).fill(rule())).child(theme::heading("Repair", 20.0)).child(theme::body(
        if p.is_folder() {
            "Adds only repairs proven on this kind of pack, from checked downloads, then runs the pack twice to show they work. Undo takes them out."
        } else {
            "Makes a repaired copy of the export with proven fixes, and runs it twice to show they work. Your .zip stays as it is."
        },
        15.0,
        graphite(),
    ));
    let mut buttons = row().gap(10.0).child(theme::button("pack-fix-button", "Apply proven fixes", Kind::Primary, !busy, |s: &mut App, _| run(s, PackAction::Fix)));
    if p.is_folder() && p.fix.is_some() {
        buttons = buttons.child(theme::button("pack-undo", "Undo the repair", Kind::Danger, !busy, |s: &mut App, _| run(s, PackAction::Undo)));
    }
    sheet = sheet.child(buttons);
    if let Some(f) = &p.fix {
        let v = Verdict::of(f);
        sheet = sheet.child(row().child(theme::stamp(if v == Verdict::Failed { "Not fixed" } else { "Repaired" }, v.color(), 18.0))).child(theme::body(f.summary(), 14.0, graphite()));
    }
    if let Some(u) = &p.undo {
        sheet = sheet.child(theme::note(u.summary()));
    }
    theme::resting(sheet, 1.0).rotate(-0.008)
}

/// Pencil outlines where the parts will go, breathing while the pack is read.
fn outlines(count: usize, breathing: bool) -> El {
    let mut g = row().w_full().gap(10.0).flex_wrap();
    for i in 0..count {
        let spot = col().w(132.0).h(150.0).items_center().pt(10.0).child(theme::block_outline(70.0));
        g = g.child(if breathing { spot.looping(LoopSpec::opacity(1.6, i as f64 * 0.11, 0.25, 0.9)) } else { spot.opacity(0.6) });
    }
    g
}

pub fn view(s: &App) -> El {
    let busy = s.busy();
    let p = &s.pack;
    let opener = theme::resting(
        col()
            .key("pack-opener")
            .w(360.0)
            .padding(26.0)
            .gap(14.0)
            .fill(paper())
            .child(theme::heading("Lay out a modpack", 26.0))
            .child(theme::body("A CurseForge export, a Modrinth .mrpack, or the instance folder your launcher keeps (the one with a mods folder in it). You can drop any of them on the bench.", 15.0, graphite()))
            .child(row().gap(10.0).child(theme::button("pack-zip", "Open a pack file…", Kind::Plain, !busy, choose_zip)).child(theme::button("pack-folder", "Open a folder…", Kind::Plain, !busy, choose_folder))),
        1.0,
    );
    let Some(path) = &p.pack else {
        return row().w_full().gap(32.0).items_start().child(col().grow(1.0).min_w(0.0).gap(18.0).pt(10.0).child(theme::note("The parts will be laid out here.")).child(outlines(10, false))).child(opener);
    };
    let folder = path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
    // The pack's own name once it has been read, else its folder's.
    let name = p.check.as_ref().and_then(|o| o.result()["pack"].as_str()).map(|n| n.trim().to_string()).filter(|n| !n.is_empty()).unwrap_or(folder);
    let running = matches!(s.running_purpose(), Some(Purpose::Pack(_)));
    let mut left = col()
        .grow(1.0)
        .min_w(0.0)
        .gap(20.0)
        .child(
            row()
                .w_full()
                .gap(14.0)
                .items_end()
                .child(col().gap(4.0).grow(1.0).min_w(0.0).child(theme::heading(name, 30.0)).child(text(path.display().to_string()).key("pack-path").font(theme::mono(12.0)).color(graphite()).truncate().w_full()))
                .child(theme::button("pack-run", if p.check.is_some() { "Check again" } else { "Check the pack" }, Kind::Primary, !busy, |s: &mut App, _| run(s, PackAction::Check))),
        );
    let mut right = col().w(360.0).gap(20.0);
    match &p.check {
        None if running => left = left.child(theme::note("Reading the pack…")).child(outlines(14, true)),
        None => {
            left = left.child(outlines(10, false));
            right = right.child(opener);
        }
        Some(o) if o.result()["modFiles"].is_null() => {
            left = left.child(theme::sheet().w_full().padding(24.0).gap(8.0).child(theme::heading("Couldn't read it", 22.0)).child(theme::body(o.summary(), 15.0, ink())));
            right = right.child(opener);
        }
        Some(o) => {
            left = left.child(theme::body(summary(o), 17.0, ink())).child(parts(o));
            right = right.child(findings_sheet(s, o));
        }
    }
    row().w_full().gap(32.0).items_start().child(left).child(right)
}
