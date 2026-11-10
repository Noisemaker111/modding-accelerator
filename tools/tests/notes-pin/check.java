// Notes' own HUD layer, registered by NotesEvents.registerOverlay as notes:pinned_note, draws the pinned note. 1.21.6
// collects GUI draws in a GuiRenderState; the layer draws into a fresh one here, exactly as the game calls it each frame,
// and the note's text must be among the text it submitted. A colour with no alpha byte submits nothing.
String expected = String.valueOf(vars.get("noteText")).strip();
java.util.List<?> layers = (java.util.List<?>) field(field(mc.gui, "layerManager"), "layers");
net.neoforged.neoforge.client.gui.GuiLayer layer = null;
for (Object o : layers) {
    var named = (net.neoforged.neoforge.client.gui.GuiLayerManager.NamedLayer) o;
    if (named.name().toString().equals("notes:pinned_note")) layer = named.layer();
}
if (layer == null) throw new AssertionError("Notes' effects are missing: no notes:pinned_note among the " + layers.size() + " GUI layers.");
var state = new net.minecraft.client.gui.render.state.GuiRenderState();
layer.render(new net.minecraft.client.gui.GuiGraphics(mc, state), mc.getDeltaTracker());
// The layer wraps the note to its width, one text per line, so the lines are joined before comparing.
java.util.List<String> drawn = new java.util.ArrayList<>(), lines = new java.util.ArrayList<>();
state.forEachText(t -> {
    StringBuilder s = new StringBuilder();
    t.text.accept((index, style, codePoint) -> { s.appendCodePoint(codePoint); return true; });
    lines.add(s.toString().strip());
    drawn.add(s + String.format(" (colour %08x)", t.color));
});
String seen = "pinned " + (com.chaosthedude.notes.Notes.pinnedNote == null ? "none" : com.chaosthedude.notes.Notes.pinnedNote.getTitle()) + ", layer drew " + drawn;
if (!String.join(" ", lines).contains(expected)) throw new AssertionError("Notes' effects are missing: its pinned note layer did not draw \"" + expected + "\". Saw " + seen + ".");
return "PASS " + seen;
