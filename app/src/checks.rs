//! "Checks": the inspection sheet a mod goes down, station by station, from source to proof. The
//! last station, behaviour, is only signed off when the mod's own assertion passed in the real game.

use std::collections::HashMap;

use rune::{Color, Cursor, El, SurfaceFit, TextEdit, col, el, row, text};

use crate::cli::Outcome;
use crate::shell::Verdict;
use crate::theme::{self, Kind, graphite, ink, moss, ochre, paper_shade, pencil, rule, rust};
use crate::{App, Purpose};

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum Check {
    Build,
    Fast,
    Data,
    Mixin,
    Client,
    /// Not a command of its own: the client check run with the mod's own assertion.
    Behaviour,
    World,
    Multiplayer,
    Plugin,
    Server,
}


impl Check {
    pub const TRACK: [Check; 6] = [Check::Build, Check::Fast, Check::Data, Check::Mixin, Check::Client, Check::Behaviour];
    pub const MORE: [Check; 4] = [Check::World, Check::Multiplayer, Check::Plugin, Check::Server];

    pub fn command(self) -> &'static str {
        match self {
            Check::Build => "compile",
            Check::Fast => "fast",
            Check::Data => "data-check",
            Check::Mixin => "mixin-check",
            Check::Client | Check::Behaviour => "ui-check",
            Check::World => "world-check",
            Check::Multiplayer => "mp-check",
            Check::Plugin => "plugin-check",
            Check::Server => "server-check",
        }
    }

    /// A key-safe id for the node.
    pub fn id(self) -> &'static str {
        if self == Check::Behaviour { "behaviour" } else { self.command() }
    }

    pub fn name(self) -> &'static str {
        match self {
            Check::Build => "build",
            Check::Fast => "logic",
            Check::Data => "data",
            Check::Mixin => "mixins",
            Check::Client => "in game",
            Check::Behaviour => "behaviour",
            Check::World => "world",
            Check::Multiplayer => "multiplayer",
            Check::Plugin => "plugin",
            Check::Server => "real server",
        }
    }

    /// One line on what it does, shown when the node is open.
    pub fn what(self) -> &'static str {
        match self {
            Check::Build => "Compiles the mod and checks the files the game reads at start-up.",
            Check::Fast => "Runs the mod's JUnit tests, without the game.",
            Check::Data => "Loads the game's own code with no window and reads what the mod adds.",
            Check::Mixin => "Lets Mixin apply every mixin against the game.",
            Check::Client => "Starts the mod in a real, hidden Minecraft client and captures what it shows.",
            Check::Behaviour => "Calls a method in your mod inside the running game. Only this proves it works.",
            Check::World => "Opens a saved world with the mod in the hidden client.",
            Check::Multiplayer => "A dedicated server with the mod, joined by a hidden client.",
            Check::Plugin => "Loads a Paper plugin on a fake server and checks its commands and listeners.",
            Check::Server => "A real Paper server with the plugin and a test player following your scenario.",
        }
    }

    fn game(self) -> bool {
        matches!(self, Check::Client | Check::Behaviour | Check::World | Check::Multiplayer)
    }
}

pub struct Checks {
    pub results: HashMap<Check, Outcome>,
    /// `--assert class#method`: the mod's own check, which turns "it loads" into "it works".
    pub assert_edit: TextEdit,
    pub world_edit: TextEdit,
    /// The node open under the track.
    pub open: Option<Check>,
}

impl Default for Checks {
    fn default() -> Self {
        Checks { results: HashMap::new(), assert_edit: TextEdit::new(""), world_edit: TextEdit::new(""), open: None }
    }
}

pub fn run(s: &mut App, check: Check) {
    let Some(root) = s.root_args() else {
        s.notice = Some("Open your mod's folder first.".into());
        return;
    };
    let asserted = s.checks.assert_edit.text.trim().to_string();
    if check == Check::Behaviour && asserted.is_empty() {
        s.checks.open = Some(Check::Behaviour);
        s.notice = Some("Name the behaviour check first: a method in your mod, as Class#method.".into());
        return;
    }
    let mut args = vec![check.command().to_string()];
    args.extend(root);
    if check.game() && !asserted.is_empty() {
        args.push("--assert".into());
        args.push(asserted);
    }
    let world = s.checks.world_edit.text.trim().to_string();
    if check == Check::World && !world.is_empty() {
        args.push("--world".into());
        args.push(world);
    }
    s.checks.open = Some(check);
    s.start(format!("{} check", capital(check.name())), Purpose::Check(check), args);
}

fn capital(s: &str) -> String {
    let mut c = s.chars();
    c.next().map(|f| f.to_uppercase().collect::<String>() + c.as_str()).unwrap_or_default()
}

/// Every node along the track, stopping at the first that fails.
pub fn run_all(s: &mut App) {
    let last = if s.checks.assert_edit.text.trim().is_empty() { Check::Client } else { Check::Behaviour };
    s.queue = vec![Check::Fast, Check::Data, Check::Mixin, last];
    run(s, Check::Build);
    if !s.busy() {
        s.queue.clear();
    }
}

fn stop_game(s: &mut App) {
    if let Some(root) = s.root_args() {
        let mut args = vec!["game-stop".to_string()];
        args.extend(root);
        s.start("Closing the hidden game", Purpose::GameStop, args);
    }
}

/// The screenshot a game check took (`result.capture`, a path or `{path}`).
pub fn capture_path(outcome: &Outcome) -> Option<String> {
    let c = &outcome.result()["capture"];
    c.as_str().or_else(|| c["path"].as_str()).map(str::to_string)
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Node {
    Idle,
    Queued,
    Running,
    Done(Verdict),
}


fn own_result(s: &App, check: Check) -> Option<&Outcome> {
    s.checks.results.get(&check)
}

pub fn node(s: &App, check: Check) -> Node {
    if s.running_purpose() == Some(&Purpose::Check(check)) {
        return Node::Running;
    }
    if s.queue.contains(&check) {
        return Node::Queued;
    }
    if check == Check::Behaviour {
        // Lit only by proof: an assertion that passed, in this run or with the client check.
        let proof = own_result(s, Check::Behaviour).or_else(|| own_result(s, Check::Client).filter(|o| Verdict::of(o) == Verdict::Works));
        return proof.map(|o| Node::Done(Verdict::of(o))).unwrap_or(Node::Idle);
    }
    own_result(s, check).map(|o| Node::Done(Verdict::of(o))).unwrap_or(Node::Idle)
}

/// Where the mod stands, in one word: home shows it big.
pub fn standing(s: &App) -> (&'static str, Color) {
    let all: Vec<Verdict> = s.checks.results.values().map(Verdict::of).collect();
    if all.contains(&Verdict::Failed) {
        ("failing", rust())
    } else if all.contains(&Verdict::Works) {
        ("works", moss())
    } else if all.contains(&Verdict::Loaded) {
        ("loads in game", ochre())
    } else if all.contains(&Verdict::Passed) {
        ("builds", moss())
    } else {
        ("untested", pencil())
    }
}

/// One node of the track: a block with the check's pixel icon, lit by its state.
/// The station's name in sentence case.
pub fn title(check: Check) -> &'static str {
    match check {
        Check::Build => "Build",
        Check::Fast => "Logic tests",
        Check::Data => "Game data",
        Check::Mixin => "Mixins",
        Check::Client => "In the game",
        Check::Behaviour => "Behaviour",
        Check::World => "In a world",
        Check::Multiplayer => "Multiplayer",
        Check::Plugin => "Plugin, quick",
        Check::Server => "Real server",
    }
}

fn seconds(s: &App, check: Check) -> String {
    if s.running_purpose() == Some(&Purpose::Check(check)) {
        if let Some(r) = &s.running {
            return format!("{}s", r.job.started.elapsed().as_secs());
        }
    }
    match own_result(s, check).and_then(Outcome::seconds) {
        Some(sec) => format!("{sec:.1}s"),
        None => String::new(),
    }
}

fn result_word(n: Node) -> (String, Color) {
    match n {
        Node::Idle => ("not yet".into(), pencil()),
        Node::Queued => ("next".into(), graphite()),
        Node::Running => ("running".into(), ochre()),
        Node::Done(v) => (v.word().into(), v.color()),
    }
}

/// One station on the sheet: number, name, a dotted leader, the result and its time.
fn station(s: &App, check: Check, number: usize) -> El {
    let n = node(s, check);
    let open = s.checks.open == Some(check);
    let (word, c) = result_word(n);
    let marker = match n {
        Node::Running => theme::working(el().w(4.0).h(30.0).fill(ochre())),
        Node::Done(v) => el().w(4.0).h(30.0).fill(v.color()),
        _ => el().w(4.0).h(30.0),
    };
    row()
        .key(format!("node-{}", check.id()))
        .w_full()
        .h(52.0)
        .gap(14.0)
        .pr(16.0)
        .center_y()
        .fill(if open { paper_shade() } else { Color::TRANSPARENT })
        .role("button")
        .label(title(check))
        .focusable(true)
        .cursor(Cursor::Pointer)
        .hover(ink().alpha(0.03))
        .on_press(move |s: &mut App| s.checks.open = Some(check))
        .child(marker)
        .child(text(number.to_string()).font(theme::serif(20.0)).color(pencil()).w(18.0))
        .child(text(title(check)).font(theme::serif(19.0)).color(ink()))
        .child(theme::leader())
        .child(text(word).key(format!("result-{}", check.id())).font(theme::italic(16.0)).color(c))
        .child(text(seconds(s, check)).font(theme::mono(13.0)).color(graphite()).w(56.0).align(rune::Align::End))
}

fn sheet_of_stations(s: &App) -> El {
    let busy = s.busy();
    let mut list = col().w_full();
    for (i, check) in Check::TRACK.iter().enumerate() {
        if *check == Check::Behaviour {
            list = list.child(
                col()
                    .w_full()
                    .pl(22.0)
                    .pt(8.0)
                    .pb(4.0)
                    .gap(3.0)
                    .child(el().w_full().h(1.0).fill(ink().alpha(0.5)))
                    .child(el().w_full().h(1.0).fill(ink().alpha(0.5)))
                    .child(text("Only this one proves it works").font(theme::italic(14.0)).color(graphite()).pt(4.0)),
            );
        }
        list = list.child(station(s, *check, i + 1));
    }
    let mut also = row().gap(18.0).flex_wrap().center_y().child(text("Also:").font(theme::italic(15.0)).color(graphite()));
    for check in Check::MORE {
        let n = node(s, check);
        let open = s.checks.open == Some(check);
        let label = match n {
            Node::Idle => title(check).to_string(),
            _ => format!("{} ({})", title(check), result_word(n).0),
        };
        also = also.child(theme::option(&format!("more-{}", check.command()), &label, open, move |s: &mut App| s.checks.open = Some(check)));
    }
    let action = if s.project.is_none() {
        theme::button("open-first", "Open your mod first", Kind::Primary, true, |s: &mut App, _| s.screen = crate::Screen::Project)
    } else if busy {
        theme::button("stop-top", "Stop", Kind::Danger, true, |s: &mut App, _| s.cancel())
    } else {
        theme::button("run-main", "Run every station", Kind::Primary, true, |s: &mut App, _| run_all(s))
    };
    theme::sheet()
        .key("inspection")
        .w(560.0)
        .pl(10.0)
        .pr(14.0)
        .pt(26.0)
        .pb(24.0)
        .gap(18.0)
        .child(col().pl(22.0).gap(4.0).child(theme::heading("Inspection", 32.0)).child(theme::note("From source code to proof, one station at a time.")))
        .child(list)
        .child(row().pl(22.0).gap(12.0).child(action))
        .child(el().w_full().h(1.0).fill(rule()))
        .child(col().pl(22.0).child(also))
}

/// The verdict as something attached to the sheet: a stamp for works or failed, a tag otherwise.
fn verdict_mark(n: Node, loaded_only: bool) -> El {
    match n {
        Node::Done(Verdict::Works) => theme::stamp("Works", moss(), 24.0),
        Node::Done(Verdict::Failed) => theme::stamp("Failed", rust(), 24.0),
        Node::Done(Verdict::Passed) => theme::stamp("Passed", moss(), 20.0),
        Node::Done(Verdict::Loaded) => theme::tag("Loaded, behaviour not proven", ochre()),
        Node::Done(Verdict::Stopped) => theme::tag("Stopped", ochre()),
        Node::Running => theme::working(theme::tag("Running now", ochre())),
        Node::Queued => theme::tag("Next in line", graphite()),
        Node::Idle if loaded_only => theme::tag("Not proven yet", ochre()),
        Node::Idle => theme::tag("Not inspected", graphite()),
    }
}

/// The game's own framebuffer as a print taped to the sheet.
fn print(image: &rune::Image, path: &str) -> El {
    let open_path = path.to_string();
    let photo = el()
        .key("capture")
        .w_full()
        .aspect_ratio(16.0 / 9.0)
        .fill(Color::BLACK)
        .pixels_fit(image.pixels(), SurfaceFit::Fill)
        .cursor(Cursor::Pointer)
        .on_press(move |s: &mut App| s.notice = crate::cli::open_path(std::path::Path::new(&open_path)).err());
    let framed = theme::resting(col().w(440.0).padding(10.0).pb(34.0).fill(Color::WHITE).child(photo), 1.0)
        .child(text("What the hidden client showed, 1280 by 720").font(theme::italic(13.0)).color(graphite()).pin(Some(12.0), None, None, Some(9.0)));
    theme::taped(framed, 440.0).rotate(0.012)
}

fn detail(s: &App, check: Check) -> El {
    let n = node(s, check);
    let result = own_result(s, check).or_else(|| (check == Check::Behaviour).then(|| own_result(s, Check::Client)).flatten());
    let busy = s.busy();
    let loaded_only = check == Check::Behaviour && n == Node::Idle && result.is_some_and(|o| Verdict::of(o) == Verdict::Loaded);
    let run_words = if own_result(s, check).is_some() { "Run again" } else { "Run this station" };
    let mut p = theme::sheet()
        .key("check-detail")
        .w_full()
        .padding(28.0)
        .gap(18.0)
        .child(col().gap(6.0).w_full().pr(150.0).child(text(title(check)).key("verdict").font(theme::serif(30.0)).color(ink())).child(theme::body(check.what(), 16.0, graphite())))
        // The verdict hangs off the sheet's corner, as a stamp or a clipped-on tag would.
        .child(el().pin(None, Some(-12.0), Some(-14.0), None).child(verdict_mark(n, loaded_only)));
    if check.game() {
        p = p.child(
            col()
                .w_full()
                .gap(6.0)
                .child(text("Behaviour check").font(theme::sans(14.0, 600.0)).color(if check == Check::Behaviour { ink() } else { graphite() }))
                .child(
                    rune::text_input(&s.checks.assert_edit, theme::mono(14.0))
                        .key("assert")
                        .single_line()
                        .py(6.0)
                        .color(ink())
                        .placeholder("com.example.mod.Checks#works", pencil())
                        .label("Behaviour check")
                        .on_edit(|s: &mut App| &mut s.checks.assert_edit),
                )
                .child(el().w_full().h(1.0).fill(graphite())),
        );
    }
    if check == Check::World {
        p = p.child(
            col()
                .w(260.0)
                .gap(6.0)
                .child(text("World").font(theme::sans(14.0, 600.0)).color(graphite()))
                .child(rune::text_input(&s.checks.world_edit, theme::mono(14.0)).key("world").single_line().py(6.0).color(ink()).placeholder("lab", pencil()).on_edit(|s: &mut App| &mut s.checks.world_edit))
                .child(el().w_full().h(1.0).fill(graphite())),
        );
    }
    let mut actions = row()
        .gap(10.0)
        .child(theme::button(&format!("run-{}", check.id()), run_words, Kind::Primary, !busy && s.project.is_some(), move |s: &mut App, _| run(s, check)));
    if check.game() {
        actions = actions.child(theme::button("game-stop", "Close the hidden game", Kind::Plain, !busy && s.project.is_some(), |s: &mut App, _| stop_game(s)));
    }
    p = p.child(actions);
    let Some(o) = result else { return p };
    let r = o.result();
    if let Some((image, path)) = capture_path(o).and_then(|path| s.captures.get(&path).map(|i| (i, path))) {
        p = p.child(col().pt(14.0).child(print(image, &path)));
    }
    // What it found, said as sentences.
    let mut said = Vec::new();
    if let Some(sec) = o.seconds() {
        said.push(format!("Took {sec:.1} seconds."));
    }
    if let Some(tests) = r["tests"].as_object() {
        let n = |k: &str| tests.get(k).and_then(|v| v.as_i64()).unwrap_or(0);
        said.push(format!("{} tests ran, {} failed.", n("executed"), n("failed")));
    }
    if let Some(n) = r["modsLoaded"]["loaderModCount"].as_i64() {
        let own = r["modsLoaded"]["mods"].as_array().map(Vec::len).unwrap_or(0);
        said.push(format!("The game loaded {n} mods, {own} of them yours."));
    }
    match r["behaviorVerified"].as_bool() {
        Some(false) if Verdict::of(o) == Verdict::Loaded => said.push("Nothing has checked what the mod does yet.".into()),
        Some(true) => said.push("The mod's own check passed inside the game.".into()),
        _ => {}
    }
    if !said.is_empty() {
        p = p.child(theme::body(said.join(" "), 16.0, ink()));
    }
    let summary = o.summary();
    let failed = Verdict::of(o) == Verdict::Failed;
    let shown: String = summary.lines().take(if failed { 14 } else { 5 }).collect::<Vec<_>>().join("\n");
    p.child(
        row()
            .w_full()
            .gap(14.0)
            .child(el().w(2.0).h_full().fill(if failed { rust() } else { rule() }))
            .child(text(shown).font(theme::mono(13.0)).color(if failed { ink() } else { graphite() }).wrap_anywhere().grow(1.0).min_w(0.0).selectable(true)),
    )
}

pub fn view(s: &App) -> El {
    let right = match s.checks.open {
        Some(open) => detail(s, open),
        None => col().pt(40.0).w_full().child(theme::note("Pick a station on the sheet to see it here.")),
    };
    row()
        .w_full()
        .gap(32.0)
        .items_start()
        .child(sheet_of_stations(s))
        .child(col().grow(1.0).min_w(0.0).pt(36.0).child(right))
}
