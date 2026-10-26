/**
 * The packaged jar's entry point, compiled for Java 8 so that an old Java on PATH gets a sentence
 * instead of an UnsupportedClassVersionError stack trace. Everything else needs Java 17.
 */
public final class ModaccelLauncher {
    public static void main(String[] args) throws Throwable {
        String spec = System.getProperty("java.specification.version", "");
        if (feature(spec) < 17) {
            System.err.println("modaccel needs Java 17 or newer, but this is Java " + feature(spec) + " (" + System.getProperty("java.home") + ").");
            System.err.println("Install Java 17 or newer, or set JAVA_HOME to one, then rerun. The modaccel.cmd and ./modaccel launchers pick JAVA_HOME first.");
            System.exit(2);
        }
        try { Class.forName("Modaccel").getMethod("main", String[].class).invoke(null, (Object) args); }
        catch (java.lang.reflect.InvocationTargetException failure) { throw failure.getCause(); }
    }
    /** "1.8" is 8; "17" and "17.0.2" are 17; anything unreadable is 0. */
    static int feature(String spec) {
        String text = spec.startsWith("1.") ? spec.substring(2) : spec;
        int end = 0;
        while (end < text.length() && Character.isDigit(text.charAt(end))) end++;
        try { return Integer.parseInt(text.substring(0, end)); } catch (NumberFormatException unreadable) { return 0; }
    }
}
