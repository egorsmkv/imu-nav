package org.imunav.routing.reference

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
import com.graphhopper.routing.ev.Orientation
import com.graphhopper.routing.ev.TurnRestriction
import com.graphhopper.routing.weighting.DefaultTurnCostProvider
import com.graphhopper.routing.weighting.TurnCostProvider
import com.graphhopper.routing.weighting.custom.CustomWeighting
import com.graphhopper.routing.weighting.custom.CustomWeightingHelper
import com.graphhopper.util.CustomModel
import com.graphhopper.util.Instruction
import com.graphhopper.util.PMap
import com.graphhopper.util.TurnCostsConfig
import com.graphhopper.util.shapes.GHPoint
import org.imunav.core.geo.GeoPoint
import org.imunav.core.route.Route
import org.imunav.core.route.Step
import org.imunav.core.route.TravelMode
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

    /** Packs with elevation: extra height samples on edges longer than this, meters. */
    const val ELEVATION_SAMPLING_M = 60

    /** Turn-cost packs store each edge's compass direction, from which the turn angle is computed. */
    const val TURN_COST_ENCODED_VALUES = "orientation"

    /** Weight of a U-turn for the car, seconds (a legal U-turn is still possible, just rarely worth it). */
    const val U_TURN_COSTS_S = 60

    /**
     * Car turn penalties, applied in addition to the travel time. They change which route is chosen,
     * not its estimated duration. Left turns cross oncoming traffic in Ukraine, so they cost more.
     * The same table drives the desktop model ([customModel]) and the phone weighting ([PhoneGraphHopper]).
     */
    val CAR_TURN_PENALTIES = listOf(
        TurnPenalty(TurnPenalty.RIGHT, 25.0, 80.0, 2.0),
        TurnPenalty(TurnPenalty.RIGHT, 80.0, 181.0, 5.0),
        TurnPenalty(TurnPenalty.LEFT, 25.0, 80.0, 8.0),
        TurnPenalty(TurnPenalty.LEFT, 80.0, 181.0, 12.0),
    )

    /**
     * The routing model of a profile.
     *  - car: drive at the average speed of each road, never where cars may not go; with [turnCosts]
     *    also pay [CAR_TURN_PENALTIES] (turn restrictions and U-turn costs live in the profile);
     *  - foot: walk at the typical walking speed of each way, preferring pleasant ways (footways,
     *    parks) over busy roads, never where pedestrians may not go.
     */
    fun customModel(profile: String = CAR, turnCosts: Boolean = false): CustomModel = when (profile) {
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
            .also { model -> if (turnCosts) CAR_TURN_PENALTIES.forEach { model.addToTurnPenalty(it.statement()) } }
    }

    /**
     * One routing profile with our custom model. With [turnCosts] the car obeys OSM turn restrictions
     * (no left turn, only straight on…) and pays [U_TURN_COSTS_S] for a U-turn; walking never has turn costs.
     */
    fun profile(name: String = CAR, turnCosts: Boolean = false): Profile {
        val profile = Profile(name).setCustomModel(customModel(name, turnCosts))
        return if (turnCosts && name == CAR) profile.setTurnCostsConfig(TurnCostsConfig.car().setUTurnCosts(U_TURN_COSTS_S)) else profile
    }

    /**
     * GraphHopper settings for a pack with [profiles] — shared by the desktop builder and the phone;
     * both must agree or the graph will not load. [turnCosts] is what [PackInfo.turnCosts] says:
     * new packs have it, packs built before it are loaded without.
     */
    fun config(graphDir: String, profiles: List<String> = ALL_PROFILES, turnCosts: Boolean = false): GraphHopperConfig {
        val carOnly = profiles == listOf(CAR)
        val encodedValues = if (carOnly) CAR_ENCODED_VALUES else ENCODED_VALUES
        return GraphHopperConfig()
            .putObject("graph.location", graphDir)
            .putObject("graph.encoded_values", if (turnCosts) "$encodedValues, $TURN_COST_ENCODED_VALUES" else encodedValues)
            .putObject("import.osm.ignored_highways", if (carOnly) CAR_ONLY_IGNORED_HIGHWAYS else IGNORED_HIGHWAYS)
            .setProfiles(profiles.map { profile(it, turnCosts) })
            .setCHProfiles(profiles.map { CHProfile(it) })
    }
}

/**
 * Extra route weight ([seconds]) for turning to the [side] by at least [fromDeg] and less than
 * [untilDeg] degrees. GraphHopper's `change_angle` is positive for right turns, negative for left.
 */
data class TurnPenalty(val side: Int, val fromDeg: Double, val untilDeg: Double, val seconds: Double) {
    /** Does a turn by [changeAngle] degrees fall into this band? */
    fun matches(changeAngle: Double): Boolean {
        val towardSide = changeAngle * side
        return towardSide >= fromDeg && towardSide < untilDeg
    }

    /** The same band as a GraphHopper custom-model statement (desktop builder, compiled by Janino). */
    fun statement(): Statement {
        val condition = if (side == RIGHT) {
            "change_angle >= $fromDeg && change_angle < $untilDeg"
        } else {
            "change_angle <= -$fromDeg && change_angle > -$untilDeg"
        }
        return Statement.If(condition, Statement.Op.ADD, seconds.toString())
    }

    companion object {
        const val RIGHT = 1
        const val LEFT = -1
    }
}

/**
 * GraphHopper for Android: identical routing to the desktop build, but the weighting is created from
 * plain code instead of compiling the custom model with Janino at runtime (Android cannot load
 * JVM bytecode generated on the fly). Each function below must mirror [GraphSpec.customModel].
 */
class PhoneGraphHopper : GraphHopper() {
    override fun createWeightingFactory(): WeightingFactory = WeightingFactory { profile, _, disableTurnCosts ->
        when (profile.name) {
            GraphSpec.FOOT -> footWeighting()
            else -> carWeighting(profile.turnCostsConfig.takeIf { profile.hasTurnCosts() && !disableTurnCosts })
        }
    }

    /**
     * Car: speed = car_average_speed; priority 1 where cars may drive, else 0. With [turnCosts]
     * (packs built with them) also turn restrictions, U-turn costs and [GraphSpec.CAR_TURN_PENALTIES],
     * exactly like GraphHopper's own factory does for the Janino-compiled model.
     */
    private fun carWeighting(turnCosts: TurnCostsConfig?): CustomWeighting {
        val speed: DecimalEncodedValue = encodingManager.getDecimalEncodedValue("car_average_speed")
        val access: BooleanEncodedValue = encodingManager.getBooleanEncodedValue("car_access")
        val turnPenalty: CustomWeighting.TurnPenaltyMapping = if (turnCosts == null) {
            CustomWeighting.TurnPenaltyMapping { _, _, _, _, _ -> 0.0 }
        } else {
            val orientation: DecimalEncodedValue = encodingManager.getDecimalEncodedValue(Orientation.KEY)
            CustomWeighting.TurnPenaltyMapping { graph, edgeIntAccess, inEdge, viaNode, outEdge ->
                // Same direction flags as GraphHopper's generated code for `change_angle`.
                val angle = CustomWeightingHelper.calcChangeAngle(
                    edgeIntAccess,
                    orientation,
                    inEdge,
                    !graph.isAdjNode(inEdge, viaNode),
                    outEdge,
                    !graph.isAdjNode(outEdge, viaNode),
                )
                GraphSpec.CAR_TURN_PENALTIES.filter { it.matches(angle) }.sumOf { it.seconds }
            }
        }
        val turnCostProvider = if (turnCosts == null) {
            TurnCostProvider.NO_TURN_COST_PROVIDER
        } else {
            DefaultTurnCostProvider(encodingManager.getTurnBooleanEncodedValue(TurnRestriction.key(GraphSpec.CAR)), baseGraph, turnCosts, turnPenalty)
        }
        return CustomWeighting(
            turnCostProvider,
            CustomWeighting.Parameters(
                { edge, reverse -> if (reverse) edge.getReverse(speed) else edge.get(speed) },
                { speed.maxOrMaxStorableDecimal },
                { edge, reverse -> if (if (reverse) edge.getReverse(access) else edge.get(access)) 1.0 else 0.0 },
                { 1.0 },
                turnPenalty,
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

/** Immutable coverage metadata, safe to consult after the graph itself has been closed. */
data class GraphCoverage(val minLat: Double, val maxLat: Double, val minLon: Double, val maxLon: Double) {
    fun covers(point: GeoPoint): Boolean = point.lat in minLat - MARGIN_DEG..maxLat + MARGIN_DEG && point.lon in minLon - MARGIN_DEG..maxLon + MARGIN_DEG

    private companion object {
        const val MARGIN_DEG = 0.02
    }
}

/** A loaded offline routing pack (a GraphHopper graph folder built by [buildGraph]). */
class OfflineGraph private constructor(private val hopper: GraphHopper, val dir: File, val profiles: List<String>) : AutoCloseable {

    /** The pack stores road heights; its routes carry an elevation profile. */
    val hasElevation: Boolean get() = hopper.hasElevation()

    /** Can this pack route [mode]? (Car-only packs cannot route on foot.) */
    fun supports(mode: TravelMode): Boolean = mode.profile in profiles

    /** Bounding box of the road network: minLat, maxLat, minLon, maxLon. */
    val bounds: DoubleArray = hopper.baseGraph.bounds.let { doubleArrayOf(it.minLat, it.maxLat, it.minLon, it.maxLon) }

    val coverage = GraphCoverage(bounds[0], bounds[1], bounds[2], bounds[3])

    /** Inside the network's bounding box, with a ~2 km margin (off-road points snap to the nearest road). */
    fun covers(p: GeoPoint): Boolean = coverage.covers(p)

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

        /** Load a pack from [dir]; memory-maps the graph so large regions do not need a large heap. */
        fun load(dir: File, memoryMapped: Boolean = true): OfflineGraph {
            // Which profiles the pack was built with decides the settings it must be loaded with.
            val info = File(dir, PackInfo.FILE).takeIf { it.exists() }?.let { PackInfo.parse(it.readText()) }
            val profiles = info?.profiles ?: GraphSpec.ALL_PROFILES
            // Turn costs change the stored encoded values and the CH shortcuts; older packs have none.
            val turnCosts = info?.turnCosts == true
            val hopper = PhoneGraphHopper()
            val cfg = GraphSpec.config(dir.absolutePath, profiles, turnCosts)
                .putObject("graph.dataaccess.default_type", if (memoryMapped) "MMAP_RO" else "RAM_STORE")
            // init() copies every custom model through Jackson, which needs Class.getRecordComponents
            // (Android 14+) for GraphHopper's record classes. Our models are plain objects already, so
            // initialize without them and hand the same profiles over directly.
            hopper.init(cfg.setProfiles(emptyList()))
            hopper.setProfiles(profiles.map { GraphSpec.profile(it, turnCosts) })
            // A 3-D graph must be opened as 3-D (GraphHopper checks the stored dimension); no DEM is needed to read it.
            hopper.setElevation(info?.elevation == true)
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
            val heights = if (pts.is3D) DoubleArray(pts.size()) { pts.getEle(it) }.takeIf { h -> h.all { it.isFinite() } } else null
            val steps = ArrayList<Step>()
            var index = 0
            val instructions = path.instructions
            for ((i, element) in instructions.withIndex()) {
                val ins = element
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
                elevationM = heights,
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
