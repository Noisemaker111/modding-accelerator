// Setup for `mmt perf-toggle` on the map screen's spot tooltip (run with --thread game, the map screen open:
// open-map.java). The work is one frame of a mouse resting on the map's middle: the tooltip the screen would
// show, as text, so a switch that changes what it says fails the screen:
//   mmt perf-toggle --root DIORAMA --project :1.21.1 --file bench/diorama/play/tooltip-setup.java \
//     --thread game --switch dev.diorama.MapScreen.KEEP_SPOT_LINES
if (!(mc.screen instanceof dev.diorama.MapScreen)) throw new IllegalStateException("open the map screen first (open-map.java)");
Object screen = mc.screen;
int x = mc.screen.width / 2;
int y = mc.screen.height / 2;
vars.put("perf.work", (java.util.concurrent.Callable<Object>) () -> {
    List<?> lines = (List<?>) call(screen, "spotTooltip", x, y);
    List<String> text = new ArrayList<>();
    for (Object line : lines == null ? List.of() : lines) {
        StringBuilder out = new StringBuilder();
        ((net.minecraft.util.FormattedCharSequence) line).accept((index, style, codePoint) -> {
            out.appendCodePoint(codePoint);
            return true;
        });
        text.add(out.toString());
    }
    return text;
});
return "tooltip at " + x + "," + y + ": " + call(screen, "spotTooltip", x, y);
