import com.graphhopper.GraphHopper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import org.imunav.core.geo.GeoPoint;
import org.imunav.core.search.*;
import org.imunav.routing.*;

/** Desktop-only public-data benchmark using the production builders and complete Kotlin/JNI search path.
 * Compile against routing/build/install/routing/lib/*; see the Ukraine benchmark report for commands.
 */
public final class RegionalSearchBenchmark {
    private static final Function0<Unit> CHECK = () -> Unit.INSTANCE;
    private static final GeoPoint NEAR = new GeoPoint(50.4501, 30.5234);
    private static final int WARMUPS = Integer.getInteger("search.benchmark.warmups", 30);
    private static final int SAMPLES = Integer.getInteger("search.benchmark.samples", 100);

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        if (WARMUPS < 1 || SAMPLES < 20) throw new IllegalArgumentException("Need warmups and at least 20 samples");
        switch (args[0]) {
            case "build" -> build(Path.of(args[1]), Path.of(args[2]));
            case "native" -> buildNative(Path.of(args[1]));
            case "measure" -> measure(Path.of(args[1]), Path.of(args[2]), false);
            case "matrix" -> measure(Path.of(args[1]), Path.of(args[2]), true);
            case "open" -> open(Path.of(args[1]), args[2], args[3]);
            default -> throw new IllegalArgumentException("build OSM OUTPUT | native OUTPUT | measure OUTPUT QUERIES | matrix OUTPUT TSV | open OUTPUT sqlite|tantivy QUERY");
        }
    }

    private static void build(Path osm, Path output) throws Exception {
        Path graph = output.resolve("base-graph");
        Path database = output.resolve("search.db");
        if (Files.exists(graph) || Files.exists(database)) throw new IllegalArgumentException("Build output already exists");
        Files.createDirectories(output);
        // Same base graph, profiles, turn costs, and network cleanup as BuildGraph; CH shortcuts do not feed search.
        var config = GraphSpec.INSTANCE.config(graph.toAbsolutePath().toString(), GraphSpec.INSTANCE.getALL_PROFILES(), true)
            .setCHProfiles(List.of()).putObject("datareader.file", osm.toAbsolutePath().toString())
            .putObject("prepare.min_network_size", 200);
        long start = System.nanoTime();
        var hopper = new GraphHopper();
        try {
            hopper.init(config);
            hopper.importOrLoad();
            reportTime("base_graph", start);
            start = System.nanoTime();
            SearchIndexBuilder.INSTANCE.build(osm.toFile(), hopper, database.toFile(), true,
                message -> { System.out.println(message); return Unit.INSTANCE; });
            reportTime("sqlite_staging", start);
        } finally {
            hopper.close();
        }
        Files.writeString(output.resolve("sqlite-complete"), "complete\n");
        buildNative(output);
    }

    private static void buildNative(Path output) throws Exception {
        if (!Files.exists(output.resolve("sqlite-complete"))) throw new IllegalStateException("SQLite build incomplete");
        long start = System.nanoTime();
        TantivyIndexBuilder.INSTANCE.build(output.resolve("search.db").toFile(), output.resolve("search.tantivy").toFile());
        reportTime("tantivy", start);
        System.out.printf("size sqlite_bytes=%d tantivy_bytes=%d%n", Files.size(output.resolve("search.db")), size(output.resolve("search.tantivy")));
    }

    private static void reportTime(String stage, long start) {
        System.out.printf("build stage=%s seconds=%.3f%n", stage, (System.nanoTime() - start) / 1e9);
    }

    private static long size(Path directory) throws Exception {
        try (var paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile).mapToLong(path -> path.toFile().length()).sum();
        }
    }

    private static List<SearchResult> sqlite(SearchDb db, String query, GeoPoint near) {
        return AddressSearch.INSTANCE.search(db, query, near, 20, CHECK);
    }

    private static List<SearchResult> nativeSearch(TantivySearch index, String query, GeoPoint near) {
        try (var request = new TantivyRequest()) {
            return index.search(query, near, request, CHECK);
        }
    }

    private static void open(Path output, String engine, String query) throws Exception {
        long start = System.nanoTime();
        List<SearchResult> results;
        if (engine.equals("sqlite")) {
            try (var db = new JdbcSearchDb(output.resolve("search.db").toFile())) { results = sqlite(db, query, NEAR); }
        } else if (engine.equals("tantivy")) {
            try (var index = new TantivySearch(output.resolve("search.tantivy").toFile())) { results = nativeSearch(index, query, NEAR); }
        } else throw new IllegalArgumentException("Unknown engine");
        System.out.printf("first_open engine=%s ms=%.3f results=%d%n", engine, (System.nanoTime() - start) / 1e6, results.size());
    }

    /** TSV cases retain their own public reference location; '-' means no reference. */
    private record QueryCase(String category, String query, GeoPoint near) {}

    private static List<QueryCase> readCases(Path corpus, boolean matrix) throws Exception {
        return Files.readAllLines(corpus).stream().filter(line -> !line.isBlank() && !line.startsWith("#"))
            .map(line -> {
                if (!matrix) return new QueryCase("original", line, NEAR);
                String[] fields = line.split("\t", -1);
                if (fields.length != 4) throw new IllegalArgumentException("Expected category, query, latitude, longitude");
                return new QueryCase(fields[0], fields[1], fields[2].equals("-") ? null :
                    new GeoPoint(Double.parseDouble(fields[2]), Double.parseDouble(fields[3])));
            }).toList();
    }

    private static void measure(Path output, Path corpus, boolean matrix) throws Exception {
        List<QueryCase> queries = readCases(corpus, matrix);
        try (var db = new JdbcSearchDb(output.resolve("search.db").toFile());
             var index = new TantivySearch(output.resolve("search.tantivy").toFile())) {
            int comparisons = 0;
            for (QueryCase entry : queries) {
                var references = matrix ? new LinkedHashSet<>(Arrays.asList(entry.near(), NEAR,
                    new GeoPoint(49.8419, 24.0315), new GeoPoint(49.9923, 36.2310), null, new GeoPoint(0, 179.999))) :
                    new LinkedHashSet<>(Arrays.asList(NEAR, null, new GeoPoint(0, 179.999)));
                for (var reference : references) {
                    var expected = sqlite(db, entry.query(), reference);
                    var actual = nativeSearch(index, entry.query(), reference);
                    if (!expected.equals(actual)) throw new AssertionError("Result mismatch for public query " + entry.query() + " reference=" + reference + "\nSQLite=" + expected + "\nTantivy=" + actual);
                    comparisons++;
                }
            }
            System.out.printf("parity queries=%d comparisons=%d passed=true warmups=%d samples=%d%n", queries.size(), comparisons, WARMUPS, SAMPLES);
            for (int queryIndex = 0; queryIndex < queries.size(); queryIndex++) {
                QueryCase entry = queries.get(queryIndex);
                String query = entry.query();
                GeoPoint near = entry.near();
                for (int i = 0; i < WARMUPS; i++) { sqlite(db, query, near); nativeSearch(index, query, near); }
                double[] sqlTimes = new double[SAMPLES];
                double[] nativeTimes = new double[SAMPLES];
                for (int i = 0; i < SAMPLES; i++) {
                    // Alternate engine order; include normalization, planning, JNI/JSON, and final ranking.
                    if (i % 2 == 0) {
                        sqlTimes[i] = time(() -> sqlite(db, query, near));
                        nativeTimes[i] = time(() -> nativeSearch(index, query, near));
                    } else {
                        nativeTimes[i] = time(() -> nativeSearch(index, query, near));
                        sqlTimes[i] = time(() -> sqlite(db, query, near));
                    }
                }
                Arrays.sort(sqlTimes);
                Arrays.sort(nativeTimes);
                var counted = new CountingDb(db);
                var results = sqlite(counted, query, near);
                int p50 = (int) Math.ceil(SAMPLES * 0.50) - 1;
                int p95 = (int) Math.ceil(SAMPLES * 0.95) - 1;
                long addressResults = results.stream().filter(result -> result.getKind() == ResultKind.ADDRESS).count();
                int retrievalCalls = AddressSearch.INSTANCE.prepare(query).getWords().isEmpty() ? 0 : 1;
                System.out.printf("warm case=%d category=%s results=%d address_results=%d sqlite_calls=%d house_calls=%d retrieval_jni_calls=%d sqlite_p50=%.6f sqlite_p95=%.6f tantivy_p50=%.6f tantivy_p95=%.6f%n",
                    queryIndex, entry.category(), results.size(), addressResults, counted.calls, counted.houses, retrievalCalls, sqlTimes[p50], sqlTimes[p95], nativeTimes[p50], nativeTimes[p95]);
            }
        }
    }

    private static double time(Runnable work) {
        long start = System.nanoTime();
        work.run();
        return (System.nanoTime() - start) / 1e6;
    }

    /** Count logical database calls outside the measured samples. */
    private static final class CountingDb implements SearchDb {
        private final SearchDb delegate;
        int calls;
        int houses;
        CountingDb(SearchDb delegate) { this.delegate = delegate; }
        public List<PlaceRow> places(String match, int limit, GeoPoint near) { calls++; return delegate.places(match, limit, near); }
        public List<StreetRow> streets(String match, int limit, Collection<Long> ids, GeoPoint near) { calls++; return delegate.streets(match, limit, ids, near); }
        public List<AddressRow> addresses(long id, String number) { calls++; houses++; return delegate.addresses(id, number); }
        public List<StreetAddressRow> batchAddresses(Collection<Long> ids, String number) { calls++; houses++; return delegate.batchAddresses(ids, number); }
    }
}
