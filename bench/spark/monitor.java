// spark's CPU monitor is running: its polling thread exists and the process load it reports is a fraction of the machine.
Class<?> monitor = Class.forName("me.lucko.spark.common.monitor.cpu.CpuMonitor", true, mc.getClass().getClassLoader());
monitor.getMethod("ensureMonitoring").invoke(null);
boolean thread = false;
for (Thread t : Thread.getAllStackTraces().keySet()) if (t.getName().startsWith("spark-monitoring-thread")) thread = true;
Thread.sleep(2500);
double load = (Double) monitor.getMethod("processLoad10SecAvg").invoke(null);
return thread && load >= 0 && load <= 1;
