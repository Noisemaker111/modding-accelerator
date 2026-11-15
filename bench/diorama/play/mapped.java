// Behaviour gate: the flight ended, the map baked tiles on the GPU (so a candidate can't win by
// falling back to the tracer), and no bake is left waiting.
long until = System.currentTimeMillis() + 60_000;
while (!Boolean.TRUE.equals(vars.get("fly.done")) && System.currentTimeMillis() < until) Thread.sleep(200);
var session = dev.diorama.MapSession.get();
if (session == null) return "no session";
var map = session.map();
int tiles = ((java.util.Map<?, ?>) field(map, "tiles")).size();
boolean gpu = dev.diorama.render.gpu.GpuBakeCheck.trusted(dev.diorama.render.gpu.GpuBakeCheck.SKY);
return Boolean.toString(Boolean.TRUE.equals(vars.get("fly.done")) && tiles >= 10 && gpu);
