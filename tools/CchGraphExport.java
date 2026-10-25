import com.graphhopper.GraphHopper;
import com.graphhopper.config.CHProfile;
import com.graphhopper.routing.DijkstraBidirectionRef;
import com.graphhopper.routing.ch.CHRoutingAlgorithmFactory;
import com.graphhopper.routing.ev.Subnetwork;
import com.graphhopper.routing.util.DefaultSnapFilter;
import com.graphhopper.routing.util.TraversalMode;
import com.graphhopper.util.PMap;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.imunav.routing.*;

/**
 * Experimental directed edge-state export. Costs include production edge weights and legal turn costs.
 * Only the explicit prepare mode writes to the graph. Queries use tower nodes for identical endpoints.
 */
public final class CchGraphExport {
    private static final double SCALE = 1000;
    private static final double[][] UKRAINE = {{50.4501, 30.5234, 50.469, 30.515}, {49.8419, 24.0315, 49.817, 24.022},
        {46.4825, 30.7233, 46.431, 30.746}, {50.4501, 30.5234, 50.353, 30.956}, {50.4501, 30.5234, 50.2547, 28.6587},
        {50.4501, 30.5234, 49.8419, 24.0315}, {49.8419, 24.0315, 46.4825, 30.7233},
        {50.4501, 30.5234, 49.9935, 36.2304}};
    /** Quantize production floating-point route weights to the CCH prototype's integer unit. */
    private static int cost(double weight) {
        long result = Math.round(weight * SCALE);
        if (result < 0 || result >= 2147483647L)
            throw new IllegalArgumentException("Weight outside prototype range");
        return (int) result;
    }
    /** Export legal transitions, prepare a disposable CH graph, or benchmark a routing kernel. */
    public static void main(String[] args) throws Exception {
        if (args.length != 5)
            throw new IllegalArgumentException(
                "GRAPH PROFILE export|prepare|ch|flex OUTPUT ukraine|kyiv|monaco|fixture");
        Locale.setDefault(Locale.ROOT);
        if (!List.of("car", "foot").contains(args[1]) || !List.of("export", "prepare", "ch", "flex").contains(args[2]))
            throw new IllegalArgumentException("Unknown profile or mode");
        boolean prepare = args[2].equals("prepare");
        GraphHopper hopper = prepare ? new GraphHopper() : new PhoneGraphHopper();
        var config = GraphSpec.INSTANCE.config(args[0], GraphSpec.INSTANCE.getALL_PROFILES(), true)
                         .putObject("graph.dataaccess.default_type", prepare ? "RAM_STORE" : "MMAP_RO");
        config.setCHProfiles(prepare || args[2].equals("ch") ? List.of(new CHProfile(args[1])) : List.of());
        try {
            long loadStart = System.nanoTime();
            hopper.init(config);
            hopper.setAllowWrites(prepare);
            if (!hopper.load())
                throw new IllegalStateException("Missing graph");
            if (prepare) {
                double seconds = (System.nanoTime() - loadStart) / 1e9;
                Files.writeString(Path.of(args[3]), "profile,load_and_prepare_s\n" + args[1] + "," + seconds + "\n");
                System.out.printf("profile=%s load_and_prepare_s=%.3f%n", args[1], seconds);
                return;
            }
            var graph = hopper.getBaseGraph();
            var weighting = hopper.createWeighting(hopper.getProfile(args[1]), new PMap());
            var filter = new DefaultSnapFilter(
                weighting, hopper.getEncodingManager().getBooleanEncodedValue(Subnetwork.key(args[1])));
            List<int[]> queries = new ArrayList<>();
            if (args[4].equals("ukraine")) {
                for (var q : UKRAINE) {
                    int from = hopper.getLocationIndex().findClosest(q[0], q[1], filter).getClosestNode();
                    int to = hopper.getLocationIndex().findClosest(q[2], q[3], filter).getClosestNode();
                    queries.add(new int[] {from, to});
                    queries.add(new int[] {to, from});
                }
            } else {
                var random = new Random(20261024);
                // Snap random tower coordinates using the same access/subnetwork filter as the app.
                for (int i = 0; i < 32; i++) {
                    int[] pair = new int[2];
                    for (int j = 0; j < 2; j++) {
                        int node = random.nextInt(graph.getNodes());
                        var access = graph.getNodeAccess();
                        var snap =
                            hopper.getLocationIndex().findClosest(access.getLat(node), access.getLon(node), filter);
                        if (!snap.isValid())
                            throw new IllegalStateException("Invalid query snap");
                        pair[j] = snap.getClosestNode();
                    }
                    queries.add(pair);
                }
            }
            if (!args[2].equals("export")) {
                var rows = new StringBuilder("algorithm,profile,query,iteration,ms,weight,distance_m,edges,found\n");
                for (int iteration = 0; iteration < 6; iteration++)
                    for (int i = 0; i < queries.size(); i++) {
                        var q = queries.get(i);
                        long start = System.nanoTime();
                        var algorithm = args[2].equals("ch")
                            ? new CHRoutingAlgorithmFactory(hopper.getCHGraphs().get(args[1]))
                                  .createAlgo(new PMap().putObject("algorithm", "dijkstrabi"))
                            : new DijkstraBidirectionRef(graph, weighting,
                                  args[1].equals("car") ? TraversalMode.EDGE_BASED : TraversalMode.NODE_BASED);
                        var result = algorithm.calcPath(q[0], q[1]);
                        int edges = result.isFound() ? result.calcEdges().size() : 0;
                        double elapsed = (System.nanoTime() - start) / 1e6;
                        rows.append(String.format("%s,%s,%d,%d,%.6f,%.6f,%.3f,%d,%s%n", args[2], args[1], i, iteration,
                            elapsed, result.getWeight(), result.getDistance(), edges, result.isFound()));
                    }
                Files.writeString(Path.of(args[3]), rows);
                return;
            }
            Files.createDirectory(Path.of(args[3]));
            int[] stateByKey = new int[Math.multiplyExact(graph.getEdges(), 2)];
            Arrays.fill(stateByKey, -1);
            int count = 0;
            var edges = graph.getAllEdges();
            while (edges.next())
                for (boolean reverse : new boolean[] {false, true})
                    if (Double.isFinite(weighting.calcEdgeWeight(edges, reverse))) {
                        int key = reverse ? edges.getReverseEdgeKey() : edges.getEdgeKey();
                        stateByKey[key] = count++;
                    }
            int[] keys = new int[count];
            for (int key = 0; key < stateByKey.length; key++)
                if (stateByKey[key] >= 0)
                    keys[stateByKey[key]] = key;
            long transitions = 0;
            var explorer = graph.createEdgeExplorer();
            try (var out = new DataOutputStream(
                     new BufferedOutputStream(Files.newOutputStream(Path.of(args[3], "graph.bin"))))) {
                out.writeInt(0x43434831);
                out.writeInt(count);
                for (int key : keys) {
                    var edge = graph.getEdgeIteratorStateForKey(key);
                    int via = edge.getAdjNode();
                    out.writeInt(edge.getBaseNode());
                    out.writeInt(via);
                    out.writeInt(key);
                    out.writeInt(cost(weighting.calcEdgeWeight(edge, false)));
                    out.writeFloat((float) graph.getNodeAccess().getLat(via));
                    out.writeFloat((float) graph.getNodeAccess().getLon(via));
                    var next = explorer.setBaseNode(via);
                    var arcs = new ArrayList<int[]>();
                    while (next.next()) {
                        int target = stateByKey[next.getEdgeKey()];
                        if (target < 0)
                            continue;
                        double turn = weighting.calcTurnWeight(edge.getEdge(), via, next.getEdge());
                        if (!Double.isFinite(turn))
                            continue;
                        arcs.add(new int[] {target, cost(turn + weighting.calcEdgeWeight(next, false))});
                    }
                    out.writeInt(arcs.size());
                    for (var arc : arcs) {
                        out.writeInt(arc[0]);
                        out.writeInt(arc[1]);
                    }
                    transitions += arcs.size();
                }
            }
            try (var writer = Files.newBufferedWriter(Path.of(args[3], "queries.csv"))) {
                writer.write("query,from,to\n");
                for (int i = 0; i < queries.size(); i++)
                    writer.write(i + "," + queries.get(i)[0] + "," + queries.get(i)[1] + "\n");
            }
            System.out.printf("profile=%s states=%d transitions=%d exported=true%n", args[1], count, transitions);
        } finally {
            hopper.close();
        }
    }
}
