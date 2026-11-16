// Setup for `mmt perf-toggle` on the fog mask (run with --thread game, the map screen open: open-map.java).
// The work rebuilds the mask the screen last built, from the same inputs, and returns its texels and the
// remembered-map layer, so a switch that changes the fog fails the screen:
//   mmt perf-toggle --root DIORAMA --project :1.21.1 --file bench/diorama/play/mask-setup.java \
//     --thread game --switch dev.diorama.render.MapViewport.PEEK_ONCE
if (!(mc.screen instanceof dev.diorama.MapScreen)) throw new IllegalStateException("open the map screen first (open-map.java)");
Object viewport = field(mc.screen, "viewport");
Object view = field(mc.screen, "lastView");
Object inputs = field(viewport, "maskInputs");
Object level = ((java.lang.ref.WeakReference<?>) field(viewport, "maskLevel")).get();
if (view == null || inputs == null || level == null) throw new IllegalStateException("the map screen hasn't drawn its fog yet");
java.lang.reflect.Field reset = viewport.getClass().getDeclaredField("maskInputs");
reset.setAccessible(true);
Object[] args = {level, view, field(inputs, "originU"), field(inputs, "originV"), field(inputs, "texel"),
    field(inputs, "scaleSteps"), field(inputs, "fraction"), field(inputs, "fast")};
vars.put("perf.work", (java.util.concurrent.Callable<Object>) () -> {
    // Forget the last build's inputs, so the same view is built again.
    reset.set(viewport, null);
    call(viewport, "buildMask", args);
    Object finishing = field(viewport, "finishing");
    if (finishing != null) ((java.util.concurrent.CompletableFuture<?>) finishing).join();
    return List.of(((int[]) field(viewport, "maskPixels")).clone(), ((float[]) field(viewport, "remembered")).clone());
});
return "mask " + field(inputs, "originU") + "," + field(inputs, "originV") + " texel " + field(inputs, "texel");
