import java.nio.file.*;
import java.util.*;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.*;
import org.junit.platform.launcher.core.*;
import org.junit.platform.launcher.listeners.*;

/** A fresh test JVM prevents the previous run's classes and static state leaking in. */
public final class PureTests {
    public static void main(String[] args) throws Exception {
        Set<Path> roots = new LinkedHashSet<>();
        for (int i = 1; i < args.length; i++) roots.add(Paths.get(args[i]));
        // modaccel.allTests: no game on the test classpath, so every test is game-free and none needs @Tag("pure").
        var request = LauncherDiscoveryRequestBuilder.request().selectors(DiscoverySelectors.selectClasspathRoots(roots))
                .filters(EngineFilter.includeEngines("junit-jupiter"));
        if (!Boolean.getBoolean("modaccel.allTests")) request.filters(TagFilter.includeTags("pure"));
        var listener = new SummaryGeneratingListener();
        LauncherFactory.create().execute(request.build(), listener);
        var summary = listener.getSummary();
        List<Object> failures = new ArrayList<>();
        summary.getFailures().forEach(f -> failures.add(Json.map("test", f.getTestIdentifier().getDisplayName(), "error", f.getException().toString())));
        long executed = summary.getTestsSucceededCount() + summary.getTestsFailedCount();
        boolean ok = executed > 0 && summary.getTestsFailedCount() == 0 && summary.getTotalFailureCount() == 0;
        Json.save(Paths.get(args[0]), Json.map("ok", ok, "executed", executed, "failed", summary.getTestsFailedCount(),
                "skipped", summary.getTestsSkippedCount(), "failures", failures));
        System.exit(ok ? 0 : 1);
    }
}
