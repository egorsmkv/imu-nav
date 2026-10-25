import com.graphhopper.GHRequest;
import com.graphhopper.GraphHopper;
import com.graphhopper.GraphHopperConfig;
import com.graphhopper.config.LMProfile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.imunav.routing.GraphSpec;
import org.imunav.routing.PhoneGraphHopper;

/** Host-only comparison of routing acceleration on disposable copies of a production base graph.
 * Compile against routing/build/install/routing/lib/*. Never point prepare at an installed pack.
 */
public final class RoutingModesBenchmark {
    private record Journey(String name, String profile, double fromLat, double fromLon, double toLat, double toLon) {}
    private static final List<Journey> JOURNEYS = List.of(
        new Journey("kyiv_local", "car", 50.4501, 30.5234, 50.469, 30.515),
        new Journey("lviv_local", "car", 49.8419, 24.0315, 49.817, 24.022),
        new Journey("odesa_local", "car", 46.4825, 30.7233, 46.431, 30.746),
        new Journey("kyiv_boryspil", "car", 50.4501, 30.5234, 50.353, 30.956),
        new Journey("kyiv_zhytomyr", "car", 50.4501, 30.5234, 50.2547, 28.6587),
        new Journey("kyiv_lviv", "car", 50.4501, 30.5234, 49.8419, 24.0315),
        new Journey("lviv_odesa", "car", 49.8419, 24.0315, 46.4825, 30.7233),
        new Journey("kyiv_kharkiv", "car", 50.4501, 30.5234, 49.9935, 36.2304),
        new Journey("kyiv_walk", "foot", 50.4501, 30.5234, 50.469, 30.515),
        new Journey("lviv_walk", "foot", 49.8419, 24.0315, 49.817, 24.022),
        new Journey("odesa_walk", "foot", 46.4825, 30.7233, 46.431, 30.746)
    );

    /** Change acceleration only, preserving the production profiles and turn costs. */
    private static GraphHopperConfig config(String dir, String mode) {
        var config = GraphSpec.INSTANCE.config(dir, GraphSpec.INSTANCE.getALL_PROFILES(), true);
        if (!mode.equals("ch")) config.setCHProfiles(List.of());
        if (mode.equals("lm")) config.setLMProfiles(List.of(new LMProfile("car"), new LMProfile("foot")));
        if (!List.of("ch", "lm", "flex").contains(mode)) throw new IllegalArgumentException("mode must be ch, lm or flex");
        return config;
    }

    /** Prepare a disposable graph or measure read-only queries without changing production configuration. */
    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        if (args.length == 2 && args[0].equals("verify-turns")) { verifyTurns(Path.of(args[1])); return; }
        if (args.length != 4) throw new IllegalArgumentException("prepare|prepare-phone|desktop|phone GRAPH ch|lm|flex OUTPUT.csv");
        if (!List.of("prepare", "prepare-phone", "desktop", "phone").contains(args[0])) throw new IllegalArgumentException("Unknown command");
        boolean prepare = args[0].startsWith("prepare");
        GraphHopper hopper = args[0].endsWith("phone") ? new PhoneGraphHopper() : new GraphHopper();
        long start = System.nanoTime();
        try {
            var settings = config(Path.of(args[1]).toAbsolutePath().toString(), args[2]);
            if (!prepare) settings.putObject("graph.dataaccess.default_type", "MMAP_RO");
            hopper.init(settings);
            if (!prepare) hopper.setAllowWrites(false);
            if (!hopper.load()) throw new IllegalStateException("Missing base graph");
            double openMs = (System.nanoTime() - start) / 1e6;
            System.out.printf("mode=%s implementation=%s open_or_prepare_ms=%.3f%n", args[2], args[0], openMs);
            if (prepare) {
                Files.writeString(Path.of(args[3]), "mode,prepare_ms\n" + args[2] + "," + openMs + "\n");
                return;
            }
            var rows = new StringBuilder("mode,implementation,query,iteration,ms,distance_m,time_ms,weight,visited_nodes,error\n");
            // First iteration is warmup; raw rows retain it for auditing.
            for (int iteration = 0; iteration < 4; iteration++) {
                for (var journey : JOURNEYS) {
                    var request = new GHRequest(journey.fromLat, journey.fromLon, journey.toLat, journey.toLon)
                        .setProfile(journey.profile).setAlgorithm("astarbi");
                    request.getHints().putObject("timeout_ms", 30000);
                    long queryStart = System.nanoTime();
                    var response = hopper.route(request);
                    double ms = (System.nanoTime() - queryStart) / 1e6;
                    String prefix = String.format("%s,%s,%s,%d,%.3f,", args[2], args[0], journey.name, iteration, ms);
                    if (response.hasErrors()) {
                        rows.append(prefix).append("0,0,0,0,\"").append(response.getErrors().toString().replace('"', '\'' )).append("\"\n");
                    } else {
                        var best = response.getBest();
                        rows.append(prefix).append(String.format("%.3f,%d,%.6f,%d,\n", best.getDistance(), best.getTime(), best.getRouteWeight(), response.getHints().getLong("visited_nodes.sum", -1)));
                    }
                }
                System.out.printf("mode=%s implementation=%s iteration=%d complete%n", args[2], args[0], iteration);
            }
            Files.writeString(Path.of(args[3]), rows);
        } finally {
            hopper.close();
        }
    }
    /** Checks a forbidden left turn against the unrestricted walking route with LM enabled. */
    private static void verifyTurns(Path directory) throws Exception {
        if (Files.exists(directory)) throw new IllegalArgumentException("Use a new fixture directory");
        Files.createDirectories(directory);
        var osm = directory.resolve("junction.osm");
        var xml = new StringBuilder("<osm version='0.6'>");
        double[][] nodes = {{50.450,30.500},{50.455,30.500},{50.460,30.500},{50.455,30.495},{50.460,30.495}};
        for (int i = 0; i < nodes.length; i++) xml.append(String.format("<node id='%d' lat='%f' lon='%f' version='1'/>", i+1, nodes[i][0], nodes[i][1]));
        int[][] ways = {{1,2},{2,3},{2,4},{3,5},{5,4}};
        for (int i = 0; i < ways.length; i++) xml.append(String.format("<way id='%d' version='1'><nd ref='%d'/><nd ref='%d'/><tag k='highway' v='secondary'/></way>", i+11, ways[i][0], ways[i][1]));
        xml.append("<relation id='21' version='1'><member type='way' ref='11' role='from'/><member type='node' ref='2' role='via'/><member type='way' ref='13' role='to'/><tag k='type' v='restriction'/><tag k='restriction' v='no_left_turn'/></relation></osm>");
        Files.writeString(osm, xml);
        var settings = config(directory.resolve("graph").toString(), "lm")
            .putObject("datareader.file", osm.toString()).putObject("prepare.min_network_size", 0)
            .putObject("prepare.lm.min_network_size", 2).putObject("prepare.lm.landmarks", 4);
        var hopper = new PhoneGraphHopper();
        try {
            hopper.init(settings);
            hopper.importOrLoad();
            var car = hopper.route(new GHRequest(50.451,30.500,50.455,30.496).setProfile("car"));
            var foot = hopper.route(new GHRequest(50.451,30.500,50.455,30.496).setProfile("foot"));
            if (car.hasErrors() || foot.hasErrors()) throw new AssertionError(car.getErrors()+" "+foot.getErrors());
            if (car.getBest().getDistance() < foot.getBest().getDistance()+500) throw new AssertionError("Car took forbidden shortcut");
            var exactRequest = new GHRequest(50.451,30.500,50.455,30.496).setProfile("car");
            exactRequest.getHints().putObject("lm.disable", true);
            var exact = hopper.route(exactRequest);
            if (exact.hasErrors() || Math.abs(exact.getBest().getRouteWeight()-car.getBest().getRouteWeight())>0.001) throw new AssertionError("LM differs from flexible routing");
            System.out.printf("turn_check=PASS car_m=%.3f foot_m=%.3f%n", car.getBest().getDistance(), foot.getBest().getDistance());
        } finally { hopper.close(); }
    }

}
