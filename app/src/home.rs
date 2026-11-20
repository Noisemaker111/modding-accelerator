//! The bench: your mod as a wooden block with its paper tag, the inspection slip it has earned so
//! far, and the tools lying beside it: a magnifier to test it, a map to move it to another version,
//! a tray of parts for modpacks and a tape measure for performance.

use std::path::PathBuf;

use rune::{Color, Cursor, El, FileDialogOptions, LineCap, Path, Shape, Tx, canvas, col, el, oklch, row, text};

use crate::checks;
use crate::theme::{self, Block, Kind, graphite, ink, moss, ochre, paper, pencil, rust};
use crate::{App, Purpose, Screen};

pub fn choose_folder(_: &mut App, tx: &mut Tx) {
    let options = FileDialogOptions { title: "Open your mod's folder".into(), ..Default::default() };
    tx.open_folder(options, |s: &mut App, answer, _tx| match answer {
        Ok(Some(path)) => s.set_project(path),
        Ok(None) => {}
        Err(e) => s.notice = Some(format!("The folder picker didn't open: {e}")),
    });
}

fn use_typed(s: &mut App) {
    let typed = s.project_edit.text.trim().trim_matches('"').to_string();
    if typed.is_empty() {
        s.notice = Some("Type or paste a folder path first.".into());
    } else {
        s.set_project(PathBuf::from(typed));
    }
}

fn loader_name(id: &str) -> &str {
    match id {
        "fabric" => "Fabric",
        "neoforge" => "NeoForge",
        "forge" => "Forge",
        "quilt" => "Quilt",
        other => other,
    }
}

/// The main build doctor found: its loader and Minecraft version.
fn main_build(s: &App) -> Option<(String, String)> {
    let r = s.doctor.as_ref()?.result();
    let builds = r["builds"].as_array()?;
    let b = builds.iter().find(|b| b["directory"].as_str() == Some("")).or(builds.first())?;
    let loader = b["loaderHints"][0].as_str().unwrap_or("").to_string();
    Some((loader, b["minecraftHint"].as_str().unwrap_or("?").to_string()))
}

/// The mod's paper tag: its name, and what it is built on.
fn label_tag(s: &App) -> El {
    let Some(path) = &s.project else { return col() };
    let name = path.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_else(|| path.display().to_string());
    let mut facts = Vec::new();
    match main_build(s) {
        Some((loader, mc)) => facts.push(format!("{} mod for Minecraft {mc}", loader_name(&loader))),
        None if s.running_purpose() == Some(&Purpose::Doctor) => facts.push("Reading the build…".into()),
        None => {}
    }
    if let Some(r) = s.doctor.as_ref().map(|d| d.result()) {
        let mut tools = Vec::new();
        if let Some(g) = r["gradle"].as_str() {
            tools.push(format!("Gradle {g}"));
        }
        let selected = r["selectedJava"].as_str();
        if let Some(v) = r["jdks"].as_array().into_iter().flatten().find(|j| j["home"].as_str() == selected).and_then(|j| j["version"].as_i64()) {
            tools.push(format!("Java {v}"));
        }
        if !tools.is_empty() {
            facts.push(tools.join(", "));
        }
    }
    let mut tag = col()
        .key("mod-tag")
        .w(300.0)
        .pl(34.0)
        .pr(22.0)
        .py(18.0)
        .gap(6.0)
        .fill(paper())
        .radius(3.0)
        .child(text(name).key("project-name").font(theme::serif(26.0)).color(ink()).truncate().w_full());
    for f in facts {
        tag = tag.child(text(f).font(theme::sans(15.0, 400.0)).color(graphite()).wrap().w_full());
    }
    // The eyelet the string goes through.
    tag = tag.child(el().pin(Some(12.0), Some(24.0), None, None).size(10.0, 10.0).radius(5.0).fill(theme::oak()).border(1.0, pencil()));
    theme::resting(tag, 0.8).rotate(0.03)
}

/// The string from the block to its tag.
fn string() -> El {
    let p = Path::new().move_to([0.0, 0.0]).cubic_to([30.0, 28.0], [60.0, -6.0], [96.0, 34.0]);
    canvas(Shape::new().stroke_path(&p, 1.4, LineCap::Round, oklch(0.55, 0.05, 60.0))).size(100.0, 40.0)
}

/// Where the mod stands, as something attached to it: a stamp when it works or fails, a tag when it
/// only loads.
fn standing_mark(s: &App) -> El {
    let (word, _) = checks::standing(s);
    match word {
        "works" => theme::stamp("Works", moss(), 22.0),
        "failing" => theme::stamp("Failing", rust(), 22.0),
        "loads in game" => theme::tag("Loaded in the game, not proven yet", ochre()).rotate(-0.04),
        "builds" => theme::tag("Builds and tests pass", moss()).rotate(-0.04),
        _ => theme::tag("Not inspected yet", graphite()).rotate(-0.04),
    }
}

fn stage(s: &App) -> El {
    let mut stage = el().w(480.0).h(350.0);
    match (&s.project, main_build(s)) {
        (None, _) => {
            stage = stage
                .child(el().pin(Some(80.0), Some(20.0), None, None).child(theme::block_outline(200.0)))
                .child(
                    col()
                        .pin(Some(30.0), Some(300.0), None, None)
                        .w(420.0)
                        .child(theme::note("Its place on the bench.")),
                );
        }
        (Some(_), build) => {
            let loader = build.map(|b| b.0).unwrap_or_default();
            stage = stage
                .child(el().pin(Some(20.0), Some(226.0), None, None).child(theme::floor_shadow(230.0)))
                .child(el().key("mod-block").pin(Some(40.0), Some(14.0), None, None).child(theme::block(204.0, Block::of_loader(&loader), 11)))
                .child(el().pin(Some(206.0), Some(150.0), None, None).child(string()))
                .child(el().pin(Some(150.0), Some(178.0), None, None).child(label_tag(s)))
                .child(el().pin(Some(180.0), Some(300.0), None, None).child(standing_mark(s)));
        }
    }
    stage
}

/// The slip of everything inspected so far, as on a workshop job ticket.
fn slip(s: &App) -> El {
    let mut lines = col().w_full().gap(9.0);
    for check in checks::Check::TRACK {
        let n = checks::node(s, check);
        let (word, c) = match n {
            checks::Node::Idle => ("—".to_string(), pencil()),
            checks::Node::Queued => ("next".into(), graphite()),
            checks::Node::Running => ("running".into(), ochre()),
            checks::Node::Done(v) => (v.word().into(), v.color()),
        };
        lines = lines.child(
            row()
                .w_full()
                .gap(8.0)
                .items_end()
                .child(text(checks::title(check)).font(theme::sans(15.0, 500.0)).color(ink()))
                .child(theme::leader())
                .child(text(word).font(theme::italic(15.0)).color(c)),
        );
    }
    let mut sheet = col()
        .key("job-ticket")
        .w(330.0)
        .padding(24.0)
        .gap(16.0)
        .fill(paper())
        .child(text("Inspection").font(theme::serif(22.0)).color(ink()))
        .child(lines);
    let busy = s.busy();
    sheet = sheet.child(
        row()
            .gap(10.0)
            .pt(6.0)
            .child(theme::button("run-main", "Inspect it", Kind::Primary, !busy && s.project.is_some(), |s: &mut App, _| {
                s.screen = Screen::Checks;
                checks::run_all(s);
            }))
            .child(theme::button("open-checks", "Details", Kind::Plain, s.project.is_some(), |s: &mut App, _| s.screen = Screen::Checks)),
    );
    theme::resting(sheet, 1.0).rotate(-0.012)
}

fn opener(s: &App) -> El {
    let busy = s.busy();
    theme::resting(
        col()
            .key("opener")
            .w(440.0)
            .padding(30.0)
            .gap(14.0)
            .fill(paper())
            .child(theme::heading("Put a mod on the bench", 26.0))
            .child(theme::body("Open a mod's source folder (the one with gradlew), or pick a compiled .jar to port to another Minecraft version. You can drop a folder, .jar, or modpack anywhere on the bench.", 16.0, graphite()))
            .child(
                row()
                    .gap(10.0)
                    .pt(4.0)
                    .child(theme::button("choose-folder", "Open a folder…", Kind::Primary, !busy, choose_folder))
                    .child(theme::button("choose-jar", "Port a .jar…", Kind::Plain, !busy, crate::port::choose_jar)),
            )
            .child(
                row()
                    .w_full()
                    .gap(10.0)
                    .center_y()
                    .child(field_line(s).grow(1.0))
                    .child(theme::button("use-folder", "Use", Kind::Plain, !busy, |s: &mut App, _| use_typed(s))),
            ),
        1.0,
    )
}

/// A typed path on a ruled line.
fn field_line(s: &App) -> El {
    col()
        .gap(2.0)
        .child(
            rune::text_input(&s.project_edit, theme::mono(14.0))
                .key("project-path")
                .single_line()
                .py(6.0)
                .color(ink())
                .placeholder("or paste a path here", pencil())
                .label("Project folder")
                .on_edit(|s: &mut App| &mut s.project_edit)
                .on_submit(|s: &mut App, _| use_typed(s)),
        )
        .child(el().w_full().h(1.0).fill(graphite()))
}

// The tools, drawn as objects lying on the bench.

fn magnifier() -> El {
    let ring: Vec<[f32; 2]> = (0..=40).map(|i| {
        let a = i as f32 / 40.0 * 6.283;
        [48.0 + 34.0 * a.cos(), 46.0 + 34.0 * a.sin()]
    }).collect();
    let lens: Vec<[f32; 2]> = (0..40).map(|i| {
        let a = i as f32 / 40.0 * 6.283;
        [48.0 + 30.0 * a.cos(), 46.0 + 30.0 * a.sin()]
    }).collect();
    let glint = Path::new().move_to([30.0, 34.0]).quad_to([36.0, 24.0], [48.0, 22.0]);
    let handle = vec![[70.0, 72.0], [78.0, 64.0], [132.0, 112.0], [124.0, 120.0]];
    let s = Shape::new()
        .fill(handle.clone(), theme::leather())
        .stroke(vec![[70.0, 72.0], [78.0, 64.0], [132.0, 112.0], [124.0, 120.0], [70.0, 72.0]], 1.6, ink())
        .fill(lens, oklch(0.90, 0.02, 220.0).alpha(0.7))
        .stroke_path(&glint, 2.4, LineCap::Round, Color::WHITE.alpha(0.9))
        .stroke(ring, 6.0, theme::brass())
        .stroke(
            (0..=40).map(|i| {
                let a = i as f32 / 40.0 * 6.283;
                [48.0 + 37.0 * a.cos(), 46.0 + 37.0 * a.sin()]
            }).collect::<Vec<_>>(),
            1.4,
            ink(),
        );
    canvas(s).size(136.0, 124.0)
}

fn folded_map() -> El {
    let panels = [
        (vec![[0.0, 14.0], [44.0, 0.0], [44.0, 92.0], [0.0, 106.0]], paper()),
        (vec![[44.0, 0.0], [88.0, 14.0], [88.0, 106.0], [44.0, 92.0]], theme::paper_shade()),
        (vec![[88.0, 14.0], [132.0, 0.0], [132.0, 92.0], [88.0, 106.0]], paper()),
    ];
    let mut s = Shape::new();
    for (p, c) in panels.iter() {
        s = s.fill(p.clone(), *c);
    }
    let route = Path::new().move_to([14.0, 76.0]).cubic_to([40.0, 30.0], [70.0, 90.0], [96.0, 46.0]).quad_to([108.0, 26.0], [118.0, 30.0]);
    s = s.stroke_path(&route, 2.0, LineCap::Round, rust());
    for (x, y) in [(14.0, 76.0), (60.0, 64.0)] {
        let dot: Vec<[f32; 2]> = (0..12).map(|i| {
            let a = i as f32 / 12.0 * 6.283;
            [x + 4.0 * a.cos(), y + 4.0 * a.sin()]
        }).collect();
        s = s.fill(dot, ink());
    }
    s = s
        .line([113.0, 25.0], [123.0, 35.0], 2.2, LineCap::Round, ink())
        .line([123.0, 25.0], [113.0, 35.0], 2.2, LineCap::Round, ink())
        .stroke(vec![[0.0, 14.0], [44.0, 0.0], [88.0, 14.0], [132.0, 0.0], [132.0, 92.0], [88.0, 106.0], [44.0, 92.0], [0.0, 106.0], [0.0, 14.0]], 1.4, ink())
        .line([44.0, 0.0], [44.0, 92.0], 1.0, LineCap::Butt, graphite().alpha(0.6))
        .line([88.0, 14.0], [88.0, 106.0], 1.0, LineCap::Butt, graphite().alpha(0.6));
    canvas(s).size(134.0, 108.0)
}

fn parts_tray() -> El {
    let mut s = Shape::new()
        .fill(vec![[0.0, 34.0], [140.0, 34.0], [130.0, 92.0], [10.0, 92.0]], theme::kraft())
        .fill(vec![[0.0, 34.0], [140.0, 34.0], [136.0, 44.0], [4.0, 44.0]], theme::oak_deep());
    let hues = [30.0, 140.0, 230.0, 85.0, 300.0];
    for (i, h) in hues.iter().enumerate() {
        let x = 14.0 + i as f32 * 24.0;
        let y = 18.0 + (i % 2) as f32 * 8.0;
        s = s
            .fill(vec![[x, y], [x + 18.0, y], [x + 18.0, y + 18.0], [x, y + 18.0]], oklch(0.70, 0.07, *h))
            .fill(vec![[x, y], [x + 18.0, y], [x + 22.0, y - 4.0], [x + 4.0, y - 4.0]], oklch(0.80, 0.06, *h))
            .stroke(vec![[x, y], [x + 18.0, y], [x + 18.0, y + 18.0], [x, y + 18.0], [x, y]], 1.0, ink().alpha(0.8));
    }
    s = s
        .fill(vec![[0.0, 48.0], [140.0, 48.0], [130.0, 92.0], [10.0, 92.0]], theme::kraft())
        .stroke(vec![[0.0, 34.0], [140.0, 34.0], [130.0, 92.0], [10.0, 92.0], [0.0, 34.0]], 1.4, ink())
        .line([3.0, 48.0], [137.0, 48.0], 1.0, LineCap::Butt, ink().alpha(0.6));
    canvas(s).size(142.0, 94.0)
}

fn tape_measure() -> El {
    let case: Vec<[f32; 2]> = (0..40).map(|i| {
        let a = i as f32 / 40.0 * 6.283;
        [44.0 + 40.0 * a.cos(), 50.0 + 40.0 * a.sin()]
    }).collect();
    let hub: Vec<[f32; 2]> = (0..24).map(|i| {
        let a = i as f32 / 24.0 * 6.283;
        [44.0 + 12.0 * a.cos(), 50.0 + 12.0 * a.sin()]
    }).collect();
    let mut s = Shape::new()
        .fill(vec![[60.0, 78.0], [150.0, 78.0], [150.0, 92.0], [60.0, 92.0]], oklch(0.86, 0.12, 92.0))
        .fill(case.clone(), theme::brass())
        .fill(hub.clone(), oklch(0.62, 0.09, 80.0));
    let mut closed = case.clone();
    closed.push(case[0]);
    let mut hub_closed = hub.clone();
    hub_closed.push(hub[0]);
    s = s.stroke(closed, 1.6, ink()).stroke(hub_closed, 1.2, ink());
    let mut x = 88.0;
    let mut i = 0;
    while x < 150.0 {
        let len = if i % 2 == 0 { 7.0 } else { 4.0 };
        s = s.line([x, 78.0], [x, 78.0 + len], 1.0, LineCap::Butt, ink());
        x += 6.0;
        i += 1;
    }
    s = s
        .stroke(vec![[84.0, 78.0], [150.0, 78.0], [150.0, 92.0], [84.0, 92.0]], 1.4, ink())
        .fill(vec![[150.0, 74.0], [156.0, 74.0], [156.0, 96.0], [150.0, 96.0]], theme::steel())
        .stroke(vec![[150.0, 74.0], [156.0, 74.0], [156.0, 96.0], [150.0, 96.0], [150.0, 74.0]], 1.2, ink());
    canvas(s).size(158.0, 100.0)
}

fn tool(key: &str, drawing: El, name: &str, what: &str, go: Screen, enabled: bool, drop: f32) -> El {
    let t = col()
        .key(key.to_string())
        .w(210.0)
        .pt(drop)
        .gap(12.0)
        .role("button")
        .label(name.to_string())
        .focusable(enabled)
        .child(el().h(126.0).items_end().child(drawing))
        .child(text(name.to_string()).font(theme::serif(20.0)).color(ink()))
        .child(text(what.to_string()).font(theme::italic(15.0)).color(graphite()).wrap().w_full());
    if enabled {
        t.cursor(Cursor::Pointer).on_press(move |s: &mut App| s.screen = go)
    } else {
        t.opacity(0.45)
    }
}

fn tools(s: &App) -> El {
    let has = s.project.is_some();
    row()
        .key("tools")
        .w_full()
        .gap(36.0)
        .items_start()
        .child(tool("go-checks", magnifier(), "Inspect", "Build, tests, then the real game", Screen::Checks, has, 0.0))
        .child(tool("go-port", folded_map(), "Change version", "Port a .jar or source folder", Screen::Port, true, 22.0))
        .child(tool("go-pack", parts_tray(), "Modpack", "Lay out a pack, find what clashes", Screen::Pack, true, 6.0))
        .child(tool("go-perf", tape_measure(), "Measure", "Frame time and where it goes", Screen::Perf, true, 30.0))
}

pub fn view(s: &App) -> El {
    let top = if s.project.is_some() {
        row()
            .w_full()
            .gap(30.0)
            .items_start()
            .child(stage(s))
            .child(el().grow(1.0))
            .child(col().pt(24.0).child(slip(s)))
    } else {
        row().w_full().gap(30.0).items_center().child(stage(s)).child(el().grow(1.0)).child(opener(s))
    };
    col()
        .w_full()
        .gap(24.0)
        .child(top)
        .child(el().w_full().h(1.0).fill(theme::oak_deep().alpha(0.25)))
        .child(tools(s))
}
