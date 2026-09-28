package org.blinddriver.routing

import com.graphhopper.GHRequest
import com.graphhopper.GraphHopper
import com.graphhopper.GraphHopperConfig
import com.graphhopper.ResponsePath
import com.graphhopper.config.CHProfile
import com.graphhopper.config.Profile
import com.graphhopper.json.Statement
import com.graphhopper.matching.MapMatching
import com.graphhopper.matching.Observation
import com.graphhopper.routing.WeightingFactory
import com.graphhopper.routing.ev.BooleanEncodedValue
import com.graphhopper.routing.ev.DecimalEncodedValue
import com.graphhopper.routing.weighting.TurnCostProvider
import com.graphhopper.routing.weighting.custom.CustomWeighting
import com.graphhopper.util.CustomModel
import com.graphhopper.util.Instruction
import com.graphhopper.util.PMap
import com.graphhopper.util.shapes.GHPoint
import org.blinddriver.core.geo.GeoPoint
import org.blinddriver.core.route.Route
import org.blinddriver.core.route.Step
import org.blinddriver.core.route.TravelMode
import java.io.File
import java.util.Locale

/**
 * Everything the graph builder (desktop JVM) and the phone must agree on. A pack built with other
 * values cannot be loaded: contraction-hierarchy shortcuts bake the weighting in.
 *
 * Current packs contain two profiles, [CAR] and [FOOT]. Packs built before walking support contain
 * only [CAR] (see [PackInfo.profiles]) and are loaded with the settings they were built with.
 */
object GraphSpec {
    const val CAR = "car"
    const val FOOT = "foot"

    /** Profiles in packs built by this version. */
    val ALL_PROFILES = listOf(CAR, FOOT)

    /**
     * Encoded values stored per edge: access + speed for routing; max_speed, road_class, roundabout
     * ("take exit N") and road_environment (tunnels, ferries) for guidance.
     */
    const val CAR_ENCODED_VALUES = "car_access, car_average_speed, road_access, max_speed, road_class, roundabout, road_environment"

    /** …plus which ways pedestrians may use, how pleasant (priority) and how fast they are on foot. */
    const val ENCODED_VALUES = "$CAR_ENCODED_VALUES, foot_access, foot_priority, foot_average_speed"

    /** Car-only packs skipped everything cars cannot use; that shrinks the graph a lot. */
    const val CAR_ONLY_IGNORED_HIGHWAYS = "footway,construction,cycleway,path,steps,pedestrian,bridleway,proposed"

    /** Combined packs keep footways, paths, steps and pedestrian streets for the walking profile. */
    const val IGNORED_HIGHWAYS = "construction,proposed"

    /** How much shorter routes are preferred over faster ones (GraphHopper's "distance influence"). */
    const val DISTANCE_INFLUENCE = 90.0
    const val FOOT_DISTANCE_INFLUENCE = 70.0
    const val HEADING_PENALTY_S = 300.0

    /**
     * The routing model of a profile.
     *  - car: drive at the average speed of each road, never where cars may not go;
     *  - foot: walk at the typical walking speed of each way, preferring pleasant ways (footways,
     *    parks) over busy roads, never where pedestrians may not go.
     */
    fun customModel(profile: String = CAR): CustomModel = when (profile) {
        FOOT -> CustomModel()
            .addToPriority(Statement.If("!foot_access", Statement.Op.MULTIPLY, "0"))
            .addToPriority(Statement.Else(Statement.Op.MULTIPLY, "foot_priority"))
            .addToSpeed(Statement.If("true", Statement.Op.LIMIT, "foot_average_speed"))
            .setDistanceInfluence(FOOT_DISTANCE_INFLUENCE)
            .setHeadingPenalty(HEADING_PENALTY_S)

        else -> CustomModel()
            .addToPriority(Statement.If("!car_access", Statement.Op.MULTIPLY, "0"))
            .addToSpeed(Statement.If("true", Statement.Op.LIMIT, "car_average_speed"))
            .setDistanceInfluence(DISTANCE_INFLUENCE)
            .setHeadingPenalty(HEADING_PENALTY_S)
    }

    /** One routing profile with our custom model. */
    fun profile(name: String = CAR): Profile = Profile(name).setCustomModel(customModel(name))

    /**
     * GraphHopper settings for a pack with [profiles] — shared by the desktop builder and the phone;
     * both must agree or the graph will not load.
     */
    fun config(graphDir: String, profiles: List<String> = ALL_PROFILES): GraphHopperConfig {
        val carOnly = profiles == listOf(CAR)
        return GraphHopperConfig()
            .putObject("graph.location", graphDir)
            .putObject("graph.encoded_values", if (carOnly) CAR_ENCODED_VALUES else ENCODED_VALUES)
            .putObject("import.osm.ignored_highways", if (carOnly) CAR_ONLY_IGNORED_HIGHWAYS else IGNORED_HIGHWAYS)
            .setProfiles(profiles.map { profile(it) })
            .setCHProfiles(profiles.map { CHProfile(it) })
    }
}

/**
 * GraphHopper for Android: identical routing to the desktop build, but the weighting is created from
 * plain code instead of compiling the custom model with Janino at runtime (Android cannot load
 * JVM bytecode generated on the fly). Each function below must mirror [GraphSpec.customModel].
 */
class PhoneGraphHopper : GraphHopper() {
    override fun createWeightingFactory(): WeightingFactory = WeightingFactory { profile, _, _ ->
        when (profile.name) {
            GraphSpec.FOOT -> footWeighting()
            else -> carWeighting()
        }
    }

    /** Car: speed = car_average_speed; priority 1 where cars may drive, else 0. */
    private fun carWeighting(): CustomWeighting {
        val speed: DecimalEncodedValue = encodingManager.getDecimalEncodedValue("car_average_speed")
        val access: BooleanEncodedValue = encodingManager.getBooleanEncodedValue("car_access")
        return CustomWeighting(
            TurnCostProvider.NO_TURN_COST_PROVIDER,
            CustomWeighting.Parameters(
                { edge, reverse -> if (reverse) edge.getReverse(speed) else edge.get(speed) },
                { speed.maxOrMaxStorableDecimal },
                { edge, reverse -> if (if (reverse) edge.getReverse(access) else edge.get(access)) 1.0 else 0.0 },
                { 1.0 },
                { _, _, _, _, _ -> 0.0 },
                GraphSpec.DISTANCE_INFLUENCE,
                GraphSpec.HEADING_PENALTY_S,
            ),
        )
    }

    /** Foot: speed = foot_average_speed; priority = foot_priority where pedestrians may walk, else 0. */
    private fun footWeighting(): CustomWeighting {
        val speed: DecimalEncodedValue = encodingManager.getDecimalEncodedValue("foot_average_speed")
        val access: BooleanEncodedValue = encodingManager.getBooleanEncodedValue("foot_access")
        val priority: DecimalEncodedValue = encodingManager.getDecimalEncodedValue("foot_priority")
        return CustomWeighting(
            TurnCostProvider.NO_TURN_COST_PROVIDER,
            CustomWeighting.Parameters(
                { edge, reverse -> if (reverse) edge.getReverse(speed) else edge.get(speed) },
                { speed.maxOrMaxStorableDecimal },
                { edge, reverse ->
                    val allowed = if (reverse) edge.getReverse(access) else edge.get(access)
                    if (allowed) (if (reverse) edge.getReverse(priority) else edge.get(priority)) else 0.0
                },
                { priority.maxOrMaxStorableDecimal },
                { _, _, _, _, _ -> 0.0 },
                GraphSpec.FOOT_DISTANCE_INFLUENCE,
                GraphSpec.HEADING_PENALTY_S,
            ),
        )
    }
}

/** The offline graph cannot answer (point outside the pack, no road connection…). */
class OfflineRoutingException(message: String) : Exception(message)

/** A recorded drive snapped onto the road network. */
data class MatchedTrack(val geometry: List<GeoPoint>, val lengthM: Double, val rawLengthM: Double)

/** A loaded offline routing pack (a GraphHopper graph folder built by [buildGraph]). */
class OfflineGraph private constructor(private val hopper: GraphHopper, val dir: File, val profiles: List<String>) : AutoCloseable {

    /** Can this pack route [mode]? (Car-only packs cannot route on foot.) */
    fun supports(mode: TravelMode): Boolean = mode.profile in profiles

    /** Bounding box of the road network: minLat, maxLat, minLon, maxLon. */
    val bounds: DoubleArray = hopper.baseGraph.bounds.let { doubleArrayOf(it.minLat, it.maxLat, it.minLon, it.maxLon) }

    /** Inside the network's bounding box, with a ~2 km margin (off-road points snap to the nearest road). */
    fun covers(p: GeoPoint): Boolean = p.lat in bounds[0] - MARGIN_DEG..bounds[1] + MARGIN_DEG && p.lon in bounds[2] - MARGIN_DEG..bounds[3] + MARGIN_DEG

    /** Route through [points] (start, optional vias, destination) for [mode]. Thread-safe. */
    fun route(points: List<GeoPoint>, mode: TravelMode = TravelMode.CAR, locale: Locale = Locale.getDefault()): Route {
        require(points.size >= 2)
        if (!supports(mode)) throw OfflineRoutingException("this map pack has no ${mode.profile} routing data")
        val outside = points.firstOrNull { !covers(it) }
        if (outside != null) throw OfflineRoutingException("point outside offline map: ${outside.lat},${outside.lon}")
        // GraphHopper rejects points outside its exact bounds; clamp near-misses onto the box, then it snaps to a road.
        val req = GHRequest(points.map { GHPoint(it.lat.coerceIn(bounds[0], bounds[1]), it.lon.coerceIn(bounds[2], bounds[3])) })
            .setProfile(mode.profile)
            .setLocale(locale)
            .setPathDetails(listOf("max_speed"))
        val rsp = hopper.route(req)
        if (rsp.hasErrors()) throw OfflineRoutingException(rsp.errors.joinToString { it.message ?: it.javaClass.simpleName })
        return toRoute(rsp.best)
    }

    /**
     * Snap a recorded track (e.g. trusted GPS fixes) onto the roads with GraphHopper's HMM map
     * matching; returns the driven road geometry and its length.
     * @param accuracyM typical GPS error, the HMM measurement sigma
     */
    fun mapMatch(points: List<GeoPoint>, accuracyM: Double = 20.0, mode: TravelMode = TravelMode.CAR): MatchedTrack {
        require(points.size >= 2) { "need at least two points" }
        val profile = if (supports(mode)) mode.profile else GraphSpec.CAR
        val mm = MapMatching.fromGraphHopper(hopper, PMap().putObject("profile", profile))
        mm.setMeasurementErrorSigma(accuracyM)
        val result = synchronized(this) { mm.match(points.map { Observation(GHPoint(it.lat, it.lon)) }) }
        val pts = result.mergedPath.calcPoints()
        return MatchedTrack((0 until pts.size()).map { GeoPoint(pts.getLat(it), pts.getLon(it)) }, result.matchLength, result.gpxEntriesLength)
    }

    /**
     * On Android GraphHopper cannot unmap memory-mapped files (it relies on the JDK-only
     * `Unsafe.invokeCleaner`); the mapping is released by the garbage collector instead.
     */
    override fun close() {
        runCatching { hopper.close() }
    }

    companion object {
        private const val MARGIN_DEG = 0.02

        /** Load a pack from [dir]; memory-maps the graph so large regions do not need a large heap. */
        fun load(dir: File, memoryMapped: Boolean = true): OfflineGraph {
            // Which profiles the pack was built with decides the settings it must be loaded with.
            val profiles = File(dir, PackInfo.FILE).takeIf { it.exists() }?.let { PackInfo.parse(it.readText())?.profiles } ?: GraphSpec.ALL_PROFILES
            val hopper = PhoneGraphHopper()
            val cfg = GraphSpec.config(dir.absolutePath, profiles)
                .putObject("graph.dataaccess.default_type", if (memoryMapped) "MMAP_RO" else "RAM_STORE")
            hopper.init(cfg)
            hopper.setAllowWrites(false)
            if (!hopper.load()) {
                runCatching { hopper.close() }
                throw OfflineRoutingException("not a routing pack: ${dir.absolutePath}")
            }
            return OfflineGraph(hopper, dir, profiles)
        }

        /** Convert a GraphHopper path into the engine's [Route] (OSRM-style step vocabulary). */
        internal fun toRoute(path: ResponsePath): Route {
            val pts = path.points
            val geometry = (0 until pts.size()).map { GeoPoint(pts.getLat(it), pts.getLon(it)) }
            val steps = ArrayList<Step>()
            var index = 0
            val instructions = path.instructions
            for (i in 0 until instructions.size) {
                val ins = instructions[i]
                val (type, modifier) = stepKind(ins, first = i == 0)
                val exit = (ins.extraInfoJSON["exit_number"] as? Number)?.toInt()
                if (ins.sign != Instruction.IGNORE) {
                    steps += Step(
                        type = type,
                        modifier = modifier,
                        name = ins.name.orEmpty(),
                        distanceM = ins.distance,
                        durationS = ins.time / 1000.0,
                        geometryIndex = index.coerceAtMost(geometry.lastIndex),
                        roundaboutExit = exit,
                    )
                }
                index += ins.length
            }
            val maxspeed = arrayOfNulls<Int>((geometry.size - 1).coerceAtLeast(0))
            path.pathDetails["max_speed"]?.forEach { d ->
                val v = (d.value as? Number)?.toDouble()
                if (v != null && v.isFinite() && v > 0) {
                    for (seg in d.first until minOf(d.last, maxspeed.size)) maxspeed[seg] = v.toInt()
                }
            }
            return Route(
                geometry = geometry,
                steps = steps,
                durationS = path.time / 1000.0,
                maxspeedKmh = if (maxspeed.any { it != null }) maxspeed.toList() else emptyList(),
                summary = "offline",
            )
        }

        /** GraphHopper turn sign → OSRM-style (type, modifier), so the rest of the app handles both routers the same way. */
        private fun stepKind(ins: Instruction, first: Boolean): Pair<String, String?> = when (ins.sign) {
            Instruction.CONTINUE_ON_STREET -> if (first) "depart" to null else "new name" to "straight"
            Instruction.TURN_SLIGHT_LEFT -> "turn" to "slight left"
            Instruction.TURN_LEFT -> "turn" to "left"
            Instruction.TURN_SHARP_LEFT -> "turn" to "sharp left"
            Instruction.TURN_SLIGHT_RIGHT -> "turn" to "slight right"
            Instruction.TURN_RIGHT -> "turn" to "right"
            Instruction.TURN_SHARP_RIGHT -> "turn" to "sharp right"
            Instruction.KEEP_LEFT -> "fork" to "slight left"
            Instruction.KEEP_RIGHT -> "fork" to "slight right"
            Instruction.U_TURN_LEFT, Instruction.U_TURN_RIGHT, Instruction.U_TURN_UNKNOWN -> "turn" to "uturn"
            Instruction.USE_ROUNDABOUT -> "roundabout" to "right"
            Instruction.LEAVE_ROUNDABOUT -> "exit roundabout" to null
            Instruction.FINISH -> "arrive" to null
            Instruction.REACHED_VIA -> "continue" to "straight"
            else -> if (first) "depart" to null else "continue" to "straight"
        }
    }
}
