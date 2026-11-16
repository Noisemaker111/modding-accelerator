// spark's own 10-second process load average (its monitoring thread's once-a-second readings) against the process's CPU
// time over the same 10 s, read here without asking any load counter: they agree within 5 points of the machine.
// (Two load readings taken back to back interfere: on Windows both arms' platform counter measures since its last call.)
Class<?> monitor = Class.forName("me.lucko.spark.common.monitor.cpu.CpuMonitor", true, mc.getClass().getClassLoader());
var average = monitor.getMethod("processLoad10SecAvg");
var os = (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
int processors = Runtime.getRuntime().availableProcessors();
long cpu0 = os.getProcessCpuTime(), wall0 = System.nanoTime();
Thread.sleep(10500);
double truth = (double) (os.getProcessCpuTime() - cpu0) / ((System.nanoTime() - wall0) * (double) processors);
double spark = (Double) average.invoke(null);
return Math.abs(spark - truth) < 0.05;
