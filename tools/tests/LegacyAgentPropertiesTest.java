public class LegacyAgentPropertiesTest {
    public static void main(String[] args) {
        System.getProperties().forEach((key, value) -> {
            if (!(key instanceof String) || !(value instanceof String)) {
                throw new AssertionError("Non-string system property: " + key);
            }
        });
        System.out.println("Legacy agent leaves JVM system properties as strings");
    }
}
