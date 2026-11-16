// Setup for `mmt perf-toggle` on the fog mask (run with --thread game, the map screen open: open-map.java).
// The work rebuilds the mask the screen last built as flying over new land does: same place and scale, the
// map knowing more. The render thread then reads the tiles into the mask and a worker finishes it, so the
// work's CPU time is the render thread's part; it waits for the worker (blocked, not counted) and returns the
// finished mask's texels and the remembered-map layer, so a switch that changes the fog fails the screen.
// With vars "mask.whole" true it rebuilds from nothing instead: all of it on the render thread, as when the
// view moves to a new place or zoom.
//   mmt perf-toggle --root DIORAMA --project :1.21.1 --file bench/diorama/play/mask-setup.java \
//     --thread game --switch dev.diorama.render.MapViewport.PEEK_ONCE,dev.diorama.render.MapViewport.HOIST
if (!(mc.screen instanceof dev.diorama.MapScreen)) throw new IllegalStateException("open the map screen first (open-map.java)");
Object viewport = field(mc.screen, "viewport");
Object view = field(mc.screen, "lastView");
Object inputs = field(viewport, "maskInputs");
Object level = ((java.lang.ref.WeakReference<?>) field(viewport, "maskLevel")).get();
if (view == null || inputs == null || level == null) throw new IllegalStateException("the map screen hasn't drawn its fog yet");
java.lang.reflect.Field last = viewport.getClass().getDeclaredField("maskInputs");
last.setAccessible(true);
// The same inputs but an older coverage version: same geometry, so the build hands its finish to the worker.
java.lang.reflect.Constructor<?> make = inputs.getClass().getDeclaredConstructors()[0];
make.setAccessible(true);
Object[] parts = new Object[make.getParameterCount()];
java.lang.reflect.RecordComponent[] components = inputs.getClass().getRecordComponents();
for (int i = 0; i < parts.length; i++) {
    java.lang.reflect.Method read = components[i].getAccessor();
    read.setAccessible(true);
    parts[i] = read.invoke(inputs);
}
parts[0] = (Long) parts[0] - 1;
Object older = make.newInstance(parts);
boolean whole = Boolean.TRUE.equals(vars.get("mask.whole"));
Object[] args = {level, view, field(inputs, "originU"), field(inputs, "originV"), field(inputs, "texel"),
    field(inputs, "scaleSteps"), field(inputs, "fraction"), field(inputs, "fast")};
vars.put("perf.work", (java.util.concurrent.Callable<Object>) () -> {
    last.set(viewport, whole ? null : older);
    call(viewport, "buildMask", args);
    Object finishing = field(viewport, "finishing");
    if (finishing != null) ((java.util.concurrent.CompletableFuture<?>) finishing).join();
    return List.of(((int[]) field(viewport, "maskPixels")).clone(), ((float[]) field(viewport, "remembered")).clone());
});
return (whole ? "whole mask " : "render-thread part of the mask ") + field(inputs, "originU") + "," + field(inputs, "originV") + " texel " + field(inputs, "texel");
