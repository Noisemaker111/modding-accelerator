//! The window around every page: the bench itself, a titlebar with the app's name and the pages,
//! the page (each slides into place as it changes), and the notebook strip along the bottom that
//! follows a running command.

use rune::{Color, Cursor, Cx, El, ScrollSpec, Transition, col, el, row, text};

use crate::cli::Outcome;
use crate::theme::{self, Kind, graphite, ink, moss, ochre, paper, pencil, rule, rust};
use crate::{App, Screen};

pub const TITLEBAR: f32 = 56.0;

/// How far a result got. Only a check on the mod's own behaviour in the game says it works.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Verdict {
    Works,
    Passed,
    Loaded,
    Failed,
    Stopped,
}

impl Verdict {
    pub fn of(outcome: &Outcome) -> Verdict {
        if outcome.cancelled {
            return Verdict::Stopped;
        }
        if !outcome.ok() {
            return Verdict::Failed;
        }
        let r = outcome.result();
        // "Loaded" is a game check's word: it ran in the game, but nothing proved the mod's behaviour.
        let in_game = matches!(r["tier"].as_str(), Some("client-render" | "integrated-world" | "dedicated-client")) || !r["capture"].is_null();
        match r["behaviorVerified"].as_bool() {
            Some(true) => Verdict::Works,
            Some(false) if in_game => Verdict::Loaded,
            _ => Verdict::Passed,
        }
    }

    pub fn word(self) -> &'static str {
        match self {
            Verdict::Works => "works",
            Verdict::Passed => "passed",
            Verdict::Loaded => "loaded",
            Verdict::Failed => "failed",
            Verdict::Stopped => "stopped",
        }
    }

    pub fn color(self) -> Color {
        match self {
            Verdict::Works | Verdict::Passed => moss(),
            Verdict::Loaded | Verdict::Stopped => ochre(),
            Verdict::Failed => rust(),
        }
    }
}

const PAGES: [(Screen, &str); 5] = [
    (Screen::Project, "Bench"),
    (Screen::Checks, "Checks"),
    (Screen::Port, "Port"),
    (Screen::Pack, "Modpack"),
    (Screen::Perf, "Measure"),
];

fn tab(s: &App, screen: Screen, name: &str) -> El {
    let on = s.screen == screen;
    col()
        .key(format!("nav-{}", name.to_lowercase()))
        .h(TITLEBAR)
        .px(12.0)
        .justify_center()
        .gap(2.0)
        .role("tab")
        .label(name.to_string())
        .checked(on)
        .focusable(true)
        .cursor(Cursor::Pointer)
        .on_press(move |s: &mut App| s.screen = screen)
        .child(text(name.to_string()).font(theme::sans(16.0, if on { 600.0 } else { 400.0 })).color(if on { ink() } else { graphite() }))
        .child(if on { theme::underline(name.len() as f32 * 8.4, ink()) } else { el().h(4.0) })
}

fn titlebar(s: &App, cx: &mut Cx<App>) -> El {
    cx.caption_color(ink());
    let insets = cx.chrome_insets();
    let mut tabs = row().h_full().gap(4.0);
    for (screen, name) in PAGES {
        tabs = tabs.child(tab(s, screen, name));
    }
    let mut right = row().gap(14.0).center_y();
    if let Some(r) = s.running.as_ref().filter(|r| !r.landed) {
        right = right
            .child(theme::working(el().size(8.0, 8.0).radius(4.0).fill(ochre())))
            .child(text(r.title.clone()).font(theme::italic(15.0)).color(graphite()).truncate().max_w(240.0));
    }
    if let Some(p) = &s.project {
        let name = p.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
        right = right.child(
            text(name)
                .key("project-chip")
                .font(theme::sans(15.0, 600.0))
                .color(ink())
                .truncate()
                .max_w(220.0)
                .cursor(Cursor::Pointer)
                .on_press(|s: &mut App| s.screen = Screen::Project),
        );
    }
    row()
        .key("titlebar")
        .w_full()
        .h(TITLEBAR)
        .pl(insets.leading + 26.0)
        .pr(insets.trailing + 18.0)
        .gap(30.0)
        .center_y()
        .window_drag()
        .child(
            row()
                .gap(10.0)
                .center_y()
                .child(theme::block(20.0, theme::Block::of_loader(""), 3))
                .child(text("Minecraft Modding Tools").font(theme::serif(17.0)).color(ink())),
        )
        .child(tabs)
        .child(el().grow(1.0))
        .child(right)
}

/// Colours a line of output by what it says: errors in rust, warnings in ochre, the rest in ink.
fn line_color(l: &str) -> Color {
    let lower = l.to_ascii_lowercase();
    if lower.contains("error") || lower.contains("failure") || lower.contains("exception") || lower.contains("failed") {
        rust()
    } else if lower.starts_with("warning") || lower.contains("[warn") || lower.contains("/warn]") {
        ochre()
    } else if l.starts_with("> ") {
        ink()
    } else {
        graphite()
    }
}

fn clock(seconds: u64) -> String {
    format!("{}:{:02}", seconds / 60, seconds % 60)
}

/// The CLI's step names as words.
pub fn plain_step(step: &str) -> String {
    let named = [
        ("download Java", "downloading Java"),
        ("java-setup", "setting up Java"),
        ("metadata", "reading the build"),
        ("pure-tests", "running the tests"),
        ("test-compile", "compiling the tests"),
        ("data-check", "loading the game's data"),
        ("mixin-check", "applying mixins"),
        ("plugin-check", "checking the plugin"),
        ("server-check", "running the server"),
        ("forge-install", "installing Forge"),
        ("paper", "preparing Paper"),
        ("port", "porting"),
        ("game", "running the game"),
        ("server", "starting a server"),
        ("stop-daemons", "tidying up"),
        ("compile", "compiling"),
        ("own-", "compiling"),
        ("agent-compile", "preparing"),
        ("tools-compile", "preparing"),
        ("runner-compile", "preparing"),
    ];
    named.iter().find(|(p, _)| step.starts_with(p)).map(|(_, w)| w.to_string()).unwrap_or_else(|| step.replace(['-', '_'], " "))
}

/// The notebook strip: what the running command prints, lined like a page.
fn console(s: &App) -> Option<El> {
    let r = s.running.as_ref()?;
    let snap = r.job.snapshot(if s.show_output { 160 } else { 0 });
    let running = snap.done.is_none();
    let elapsed = match snap.done.as_ref().and_then(Outcome::seconds) {
        Some(sec) => sec as u64,
        None => r.job.started.elapsed().as_secs(),
    };
    let (state, c) = match &snap.done {
        None => {
            let step = snap.step.as_deref().map(plain_step).unwrap_or_else(|| "starting".into());
            (step, ochre())
        }
        // A pack check that found problems did its job: say what it found, not that it failed.
        Some(o) if matches!(r.purpose, crate::Purpose::Pack(_)) && o.result()["findings"].is_array() && !o.ok() => ("found problems".to_string(), rust()),
        Some(o) => {
            let v = Verdict::of(o);
            (v.word().to_string(), v.color())
        }
    };
    let mark = el().size(9.0, 9.0).radius(5.0).fill(c);
    let mut head = row()
        .w_full()
        .h(52.0)
        .px(26.0)
        .gap(14.0)
        .center_y()
        .child(if running { theme::working(mark) } else { mark })
        .child(text(r.title.clone()).font(theme::serif(17.0)).color(ink()).truncate().max_w(340.0))
        .child(text(state).key("console-state").font(theme::italic(15.0)).color(if running { graphite() } else { c }))
        .child(el().grow(1.0))
        .child(text(clock(elapsed)).key("console-clock").font(theme::mono(15.0)).color(graphite()))
        .child(theme::button("toggle-output", if s.show_output { "Hide output" } else { "Show output" }, Kind::Plain, true, |s: &mut App, _| s.show_output = !s.show_output));
    if running {
        head = head.child(theme::button("stop", "Stop", Kind::Danger, true, |s: &mut App, _| s.cancel()));
    } else {
        head = head.child(theme::button("dismiss", "Put away", Kind::Plain, true, |s: &mut App, _| s.running = None));
    }
    let mut strip = col().key("console").w_full().fill(paper()).child(el().w_full().h(1.0).fill(rule())).child(head);
    strip = theme::resting(strip, 1.2);
    if s.show_output {
        let first = r.job.total_lines().saturating_sub(snap.lines.len());
        let mut lines = col().key("console-lines").w_full().px(26.0).pb(12.0);
        if snap.lines.is_empty() {
            let words = if running { "Nothing printed yet." } else { "It printed nothing." };
            lines = lines.child(text(words).font(theme::italic(14.0)).color(pencil()));
        }
        let count = snap.lines.len();
        for (i, l) in snap.lines.iter().enumerate() {
            let mut line = row()
                .w_full()
                .h(21.0)
                .gap(14.0)
                .center_y()
                .child(text(format!("{:>4}", first + i + 1)).font(theme::mono(11.0)).color(pencil()).w(32.0))
                .child(text(l.clone()).font(theme::mono(12.5)).color(line_color(l)).truncate().grow(1.0).min_w(0.0));
            if running && i + 1 == count {
                line = line.child(el().size(7.0, 14.0).fill(ink()).looping(rune::LoopSpec::opacity(1.0, 0.0, 0.0, 1.0)));
            }
            lines = lines.child(line);
        }
        strip = strip.child(el().w_full().h(1.0).fill(rule())).child(col().w_full().h(200.0).child(lines.scroll(ScrollSpec { stick_bottom: true }).h_full().min_h(0.0)));
    }
    Some(strip)
}

fn notice(words: &str, c: Color) -> El {
    theme::sheet()
        .w_full()
        .child(
            row()
                .w_full()
                .gap(16.0)
                .pl(0.0)
                .pr(14.0)
                .py(10.0)
                .center_y()
                .child(el().w(4.0).h(28.0).fill(c))
                .child(text(words.to_string()).font(theme::sans(15.0, 500.0)).color(ink()).wrap().grow(1.0))
                .child(theme::button("notice-close", "OK", Kind::Plain, true, |s: &mut App, _| s.notice = None)),
        )
}

pub fn frame(s: &App, cx: &mut Cx<App>, page: El) -> El {
    let mut top = col().w_full().px(40.0).gap(10.0);
    if let Err(e) = &s.launcher {
        top = top.child(notice(e, rust()));
    }
    if let Some(n) = &s.notice {
        top = top.child(notice(n, ochre()));
    }
    // Each page is its own keyed layer: changing pages slides the new one down into place.
    let screen_key = format!("page-{:?}", s.screen).to_lowercase();
    let page_layer = el()
        .key(screen_key)
        .pin(Some(0.0), Some(0.0), Some(0.0), Some(0.0))
        .transition(Transition::offset(0.0, -12.0).combined(Transition::opacity()))
        .child(col().w_full().h_full().child(col().w_full().px(40.0).pt(20.0).pb(48.0).child(page)).scroll(ScrollSpec { stick_bottom: false }));
    let mut main = col()
        .w_full()
        .h_full()
        .child(titlebar(s, cx))
        .child(el().w_full().h(1.0).fill(theme::oak_deep().alpha(0.35)))
        .child(top)
        .child(el().w_full().grow(1.0).min_h(0.0).child(page_layer));
    if let Some(c) = console(s) {
        main = main.child(c);
    }
    el().key("root")
        .w_full()
        .h_full()
        .fill(theme::oak())
        .on_drop_files(|s: &mut App, files, _tx| crate::dropped(s, files))
        .child(theme::bench())
        .child(main)
}
