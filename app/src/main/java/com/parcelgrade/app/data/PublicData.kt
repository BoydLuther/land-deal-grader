package com.parcelgrade.app.data

import com.parcelgrade.app.domain.FloodResult
import com.parcelgrade.app.domain.GeocodeResult
import com.parcelgrade.app.domain.Geo
import com.parcelgrade.app.domain.LatLng
import com.parcelgrade.app.domain.Parcel
import com.parcelgrade.app.domain.RoadResult
import com.parcelgrade.app.domain.SoilResult
import com.parcelgrade.app.domain.TerrainResult
import com.parcelgrade.app.domain.WetlandResult
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

/** Free, keyless U.S. public data sources. */
object PublicData {

    // ---- Geocoding --------------------------------------------------------------------------

    private val LATLON = Regex("""^\s*(-?\d{1,2}\.\d+)\s*[, ]\s*(-?\d{1,3}\.\d+)\s*$""")

    suspend fun geocode(input: String): GeocodeResult {
        LATLON.matchEntire(input)?.let { m ->
            val p = LatLng(m.groupValues[1].toDouble(), m.groupValues[2].toDouble())
            val (county, state) = runCatching { censusCountyAt(p) }.getOrDefault(null to null)
            return GeocodeResult("${"%.5f".format(p.lat)}, ${"%.5f".format(p.lon)}", p, county, state, null, null, "Coordinates")
        }
        runCatching { censusGeocode(input) }.getOrNull()?.let { return it }
        runCatching { nominatim(input) }.getOrNull()?.let { return it }
        throw IllegalStateException("Couldn't find that address. Check the spelling, add the city and state, or enter coordinates like 36.85, -76.29.")
    }

    private suspend fun censusGeocode(address: String): GeocodeResult? {
        val url = "https://geocoding.geo.census.gov/geocoder/geographies/onelineaddress?address=${enc(address)}" +
            "&benchmark=Public_AR_Current&vintage=Current_Current&layers=Counties,States&format=json"
        val matches = Http.getJson(url).optJSONObject("result")?.optJSONArray("addressMatches")
        val m = matches?.optJSONObject(0) ?: return null
        val c = m.getJSONObject("coordinates")
        val comps = m.optJSONObject("addressComponents")
        val geos = m.optJSONObject("geographies")
        val county = geos?.optJSONArray("Counties")?.optJSONObject(0)?.str("NAME")
        val state = comps?.str("state") ?: geos?.optJSONArray("States")?.optJSONObject(0)?.str("STUSAB")
        return GeocodeResult(
            matchedAddress = m.optString("matchedAddress", address),
            point = LatLng(c.getDouble("y"), c.getDouble("x")),
            county = county, state = state,
            city = comps?.str("city")?.lowercase()?.split(" ")?.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } },
            zip = comps?.str("zip"),
            source = "U.S. Census Geocoder",
        )
    }

    private suspend fun censusCountyAt(p: LatLng): Pair<String?, String?> {
        val url = "https://geocoding.geo.census.gov/geocoder/geographies/coordinates?x=${p.lon}&y=${p.lat}" +
            "&benchmark=Public_AR_Current&vintage=Current_Current&layers=Counties,States&format=json"
        val geos = Http.getJson(url).optJSONObject("result")?.optJSONObject("geographies")
        return geos?.optJSONArray("Counties")?.optJSONObject(0)?.str("NAME") to
            geos?.optJSONArray("States")?.optJSONObject(0)?.str("STUSAB")
    }

    private suspend fun nominatim(q: String): GeocodeResult? {
        val url = "https://nominatim.openstreetmap.org/search?q=${enc(q)}&format=jsonv2&addressdetails=1&limit=1&countrycodes=us"
        val arr = Http.getJsonArray(url)
        val r = arr.optJSONObject(0) ?: return null
        val a = r.optJSONObject("address") ?: JSONObject()
        val iso = a.str("ISO3166-2-lvl4")
        return GeocodeResult(
            matchedAddress = r.optString("display_name", q),
            point = LatLng(r.getString("lat").toDouble(), r.getString("lon").toDouble()),
            county = a.str("county"),
            state = iso?.removePrefix("US-") ?: a.str("state"),
            city = a.str("city") ?: a.str("town") ?: a.str("village") ?: a.str("hamlet"),
            zip = a.str("postcode"),
            source = "OpenStreetMap Nominatim",
        )
    }

    // ---- ArcGIS helpers ---------------------------------------------------------------------

    class Feature(val attrs: JSONObject, val rings: List<List<LatLng>>)

    private fun parseRings(geom: JSONObject?): List<List<LatLng>> {
        val rings = geom?.optJSONArray("rings") ?: return emptyList()
        return (0 until rings.length()).map { i ->
            val ring = rings.getJSONArray(i)
            (0 until ring.length()).map { k ->
                val pt = ring.getJSONArray(k)
                LatLng(pt.getDouble(1), pt.getDouble(0))
            }
        }
    }

    private suspend fun arcgisQuery(layerUrl: String, box: Geo.BBox, outFields: String): List<Feature> {
        val url = "$layerUrl/query?geometry=${enc(box.envelope())}&geometryType=esriGeometryEnvelope&inSR=4326" +
            "&spatialRel=esriSpatialRelIntersects&outFields=${enc(outFields)}&returnGeometry=true&outSR=4326" +
            "&geometryPrecision=6&f=json"
        val json = Http.getJson(url)
        json.optJSONObject("error")?.let { throw IllegalStateException(it.optString("message", "Map service error")) }
        return json.optJSONArray("features").objects().map { Feature(it.optJSONObject("attributes") ?: JSONObject(), parseRings(it.optJSONObject("geometry"))) }
    }

    // ---- FEMA flood zones -------------------------------------------------------------------

    private val NFHL_URLS = listOf(
        "https://hazards.fema.gov/arcgis/rest/services/public/NFHL/MapServer/28",
        "https://hazards.fema.gov/gis/nfhl/rest/services/public/NFHL/MapServer/28",
    )

    class FloodData(val result: FloodResult, val sfhaPolygons: List<List<List<LatLng>>>)

    suspend fun flood(rings: List<List<LatLng>>, samples: List<LatLng>): FloodData {
        val box = Geo.bbox(rings)
        var lastError: Throwable? = null
        var features: List<Feature>? = null
        for (u in NFHL_URLS) {
            try { features = arcgisQuery(u, box, "FLD_ZONE,ZONE_SUBTY,SFHA_TF"); break } catch (t: Throwable) { lastError = t }
        }
        val fs = features ?: throw lastError ?: IllegalStateException("FEMA unavailable")
        val real = fs.filter { (it.attrs.str("FLD_ZONE") ?: "").uppercase() != "AREA NOT INCLUDED" }
        fun zone(f: Feature) = (f.attrs.str("FLD_ZONE") ?: "").uppercase()
        fun sub(f: Feature) = (f.attrs.str("ZONE_SUBTY") ?: "").uppercase()
        val sfha = real.filter { f ->
            f.attrs.str("SFHA_TF").equals("T", true) || zone(f).startsWith("A") || zone(f).startsWith("V")
        }
        val floodway = real.filter { sub(it).contains("FLOODWAY") }
        val moderate = real.filter { zone(it) == "X" && sub(it).contains("0.2") }
        val inParcel = real.filter { f -> samples.any { p -> Geo.contains(f.rings, p) } }
        val zones = inParcel.map { f ->
            val z = zone(f)
            when {
                sub(f).contains("FLOODWAY") -> "$z (floodway)"
                z == "X" && sub(f).contains("0.2") -> "X (500-year)"
                else -> z
            }
        }.distinct().sorted()
        val sfhaPolys = sfha.map { it.rings }
        return FloodData(
            FloodResult(
                zones = zones,
                sfhaPct = Geo.coveragePct(samples, sfhaPolys),
                floodwayPct = Geo.coveragePct(samples, floodway.map { it.rings }),
                moderatePct = Geo.coveragePct(samples, moderate.map { it.rings }),
                coastalV = inParcel.any { zone(it).startsWith("V") },
                mapped = real.isNotEmpty(),
            ),
            sfhaPolys,
        )
    }

    // ---- USFWS National Wetlands Inventory --------------------------------------------------

    private const val NWI_URL = "https://fwspublicservices.wim.usgs.gov/wetlandsmapservice/rest/services/Wetlands/MapServer/0"

    class WetlandData(val result: WetlandResult, val polygons: List<List<List<LatLng>>>)

    suspend fun wetlands(rings: List<List<LatLng>>, samples: List<LatLng>): WetlandData {
        val fs = arcgisQuery(NWI_URL, Geo.bbox(rings), "ATTRIBUTE,WETLAND_TYPE")
        val inParcel = fs.filter { f -> samples.any { p -> Geo.contains(f.rings, p) } }
        val polys = fs.map { it.rings }
        return WetlandData(
            WetlandResult(
                pct = Geo.coveragePct(samples, polys),
                types = inParcel.mapNotNull { it.attrs.str("WETLAND_TYPE") }.distinct(),
            ),
            polys,
        )
    }

    // ---- USGS elevation -> slope ------------------------------------------------------------

    suspend fun terrain(rings: List<List<LatLng>>): TerrainResult? = coroutineScope {
        val b = Geo.bbox(rings)
        val n = 4
        val grid = (0 until n).flatMap { i ->
            (0 until n).map { j ->
                LatLng(
                    b.minLat + (b.maxLat - b.minLat) * (0.1 + 0.8 * i / (n - 1)),
                    b.minLon + (b.maxLon - b.minLon) * (0.1 + 0.8 * j / (n - 1)),
                )
            }
        }
        val elev = grid.map { p ->
            async {
                runCatching {
                    val j = Http.getJson("https://epqs.nationalmap.gov/v1/json?x=${p.lon}&y=${p.lat}&wkid=4326&units=Feet&includeDate=false")
                    j.num("value")?.takeIf { it > -10_000 }
                }.getOrNull()
            }
        }.map { it.await() }
        if (elev.count { it != null } < 4) return@coroutineScope null

        val slopes = mutableListOf<Double>()
        for (i in 0 until n) for (j in 0 until n) {
            val here = elev[i * n + j] ?: continue
            if (j + 1 < n) elev[i * n + j + 1]?.let { slopes.add(slope(here, it, grid[i * n + j], grid[i * n + j + 1])) }
            if (i + 1 < n) elev[(i + 1) * n + j]?.let { slopes.add(slope(here, it, grid[i * n + j], grid[(i + 1) * n + j])) }
        }
        val vals = elev.filterNotNull()
        TerrainResult(
            minFt = vals.min(), maxFt = vals.max(),
            avgSlopePct = if (slopes.isEmpty()) 0.0 else slopes.average(),
            maxSlopePct = slopes.maxOrNull() ?: 0.0,
            samples = vals.size,
        )
    }

    private fun slope(z1Ft: Double, z2Ft: Double, a: LatLng, b: LatLng): Double {
        val run = Geo.distanceM(a, b)
        if (run < 1) return 0.0
        return abs(z1Ft - z2Ft) * 0.3048 / run * 100.0
    }

    // ---- USDA soils ---------------------------------------------------------------------------

    suspend fun soils(p: LatLng): SoilResult? {
        val sql = """
            SELECT TOP 1 mu.muname, c.compname, c.comppct_r, c.drainagecl, c.hydricrating,
              (SELECT TOP 1 interphrc FROM cointerp WHERE cokey = c.cokey AND mrulename = 'ENG - Septic Tank Absorption Fields' AND ruledepth = 0) AS septic,
              (SELECT TOP 1 interphrc FROM cointerp WHERE cokey = c.cokey AND mrulename = 'ENG - Dwellings Without Basements' AND ruledepth = 0) AS dwell
            FROM mapunit mu INNER JOIN component c ON c.mukey = mu.mukey
            WHERE mu.mukey IN (SELECT * FROM SDA_Get_Mukey_from_intersection_with_WktWgs84('point(${p.lon} ${p.lat})'))
            ORDER BY c.comppct_r DESC
        """.trimIndent()
        val body = JSONObject().put("query", sql).put("format", "JSON+COLUMNNAME")
        val res = Http.postJson("https://SDMDataAccess.sc.egov.usda.gov/Tabular/post.rest", body)
        val table = res.optJSONArray("Table") ?: return null
        if (table.length() < 2) return null
        val cols = table.getJSONArray(0)
        val row = table.getJSONArray(1)
        val m = (0 until cols.length()).associate { cols.getString(it) to row.opt(it)?.toString()?.takeIf { s -> s != "null" && s.isNotBlank() } }
        return SoilResult(
            mapUnit = m["muname"] ?: "Unnamed soil unit",
            component = m["compname"],
            drainage = m["drainagecl"],
            hydric = m["hydricrating"],
            septic = m["septic"],
            dwellings = m["dwell"],
        )
    }

    // ---- OpenStreetMap roads -> access ------------------------------------------------------

    private val PUBLIC = setOf("trunk", "primary", "secondary", "tertiary", "unclassified", "residential", "living_street", "road")
    private val UNPAVED = setOf("unpaved", "gravel", "dirt", "ground", "compacted", "fine_gravel", "sand", "grass", "earth", "mud", "pebblestone")

    suspend fun roads(rings: List<List<LatLng>>, estimated: Boolean): RoadResult {
        val b = Geo.bbox(rings)
        val c = b.center
        val halfDiag = Geo.distanceM(LatLng(b.minLat, b.minLon), LatLng(b.maxLat, b.maxLon)) / 2
        val radius = (halfDiag + 400).toInt()
        val q = "[out:json][timeout:25];way(around:$radius,${c.lat},${c.lon})[highway];out tags geom;"
        val json = try {
            Http.postForm("https://overpass-api.de/api/interpreter", mapOf("data" to q))
        } catch (t: Throwable) {
            Http.postForm("https://overpass.kumi.systems/api/interpreter", mapOf("data" to q))
        }
        val touch = if (estimated) 30.0 else 15.0

        data class Road(val name: String, val type: String, val public: Boolean, val unpaved: Boolean, val dist: Double)
        val roads = json.optJSONArray("elements").objects().mapNotNull { el ->
            val tags = el.optJSONObject("tags") ?: return@mapNotNull null
            val type = tags.str("highway") ?: return@mapNotNull null
            if (type in setOf("footway", "path", "cycleway", "steps", "bridleway", "pedestrian", "corridor", "proposed", "construction", "platform")) return@mapNotNull null
            val geom = el.optJSONArray("geometry").objects().map { LatLng(it.getDouble("lat"), it.getDouble("lon")) }
            if (geom.isEmpty()) return@mapNotNull null
            val access = tags.str("access")?.lowercase()
            val isPublic = type in PUBLIC && access !in setOf("private", "no")
            val unpaved = type == "track" || (tags.str("surface")?.lowercase() in UNPAVED)
            Road(
                name = tags.str("name") ?: tags.str("ref") ?: "unnamed ${type.replace('_', ' ')}",
                type = type, public = isPublic, unpaved = unpaved,
                dist = Geo.polylineToParcelM(geom, rings),
            )
        }.filter { it.type != "motorway" && !it.type.endsWith("_link") }

        val touching = roads.filter { it.dist <= touch }
        val publicTouch = touching.filter { it.public }.minByOrNull { if (it.unpaved) 1 else 0 }
        val anyTouch = publicTouch ?: touching.minByOrNull { it.dist }
        val nearestPublic = roads.filter { it.public }.minByOrNull { it.dist }
        return RoadResult(
            frontage = anyTouch != null,
            frontageRoad = anyTouch?.name,
            frontageType = anyTouch?.type?.replace('_', ' '),
            publicFrontage = publicTouch != null,
            unpaved = anyTouch?.unpaved ?: false,
            nearestPublicRoadM = nearestPublic?.dist?.let { max(0.0, it) },
            nearestPublicRoadName = nearestPublic?.name,
        )
    }

    // ---- Regrid parcel boundary (optional, needs a token) -----------------------------------

    suspend fun regridParcel(p: LatLng, token: String): Parcel? {
        val url = "https://app.regrid.com/api/v2/parcels/point?lat=${p.lat}&lon=${p.lon}&token=${enc(token)}&return_geometry=true&limit=1"
        val root = Http.getJson(url)
        val features = root.optJSONObject("parcels")?.optJSONArray("features") ?: root.optJSONArray("features")
        val f = features?.optJSONObject(0) ?: return null
        val geom = f.optJSONObject("geometry") ?: return null
        val rings = geoJsonRings(geom)
        if (rings.isEmpty()) return null
        val props = f.optJSONObject("properties")
        val fields = props?.optJSONObject("fields") ?: props ?: JSONObject()
        val acres = fields.num("ll_gisacre") ?: fields.num("gisacre") ?: Geo.areaAcres(rings)
        return Parcel(
            rings = rings,
            acres = acres,
            isEstimated = false,
            apn = fields.str("parcelnumb"),
            zoning = listOfNotNull(fields.str("zoning"), fields.str("zoning_description")).joinToString(" – ").ifBlank { null },
            landUse = fields.str("usedesc"),
            assessedValue = fields.num("parval") ?: fields.num("landval"),
            lastSalePrice = fields.num("saleprice"),
            lastSaleDate = fields.str("saledate"),
        )
    }

    private fun geoJsonRings(geom: JSONObject): List<List<LatLng>> {
        fun ring(a: JSONArray) = (0 until a.length()).map { val pt = a.getJSONArray(it); LatLng(pt.getDouble(1), pt.getDouble(0)) }
        val coords = geom.optJSONArray("coordinates") ?: return emptyList()
        return when (geom.optString("type")) {
            "Polygon" -> (0 until coords.length()).map { ring(coords.getJSONArray(it)) }
            "MultiPolygon" -> (0 until coords.length()).flatMap { i ->
                val poly = coords.getJSONArray(i)
                (0 until poly.length()).map { ring(poly.getJSONArray(it)) }
            }
            else -> emptyList()
        }
    }

    /** Combined % of sample points inside either polygon set. */
    fun unionPct(samples: List<LatLng>, a: List<List<List<LatLng>>>, b: List<List<List<LatLng>>>): Double =
        Geo.coveragePct(samples, a + b)

    /** Side length helper for display. */
    fun sideFeet(acres: Double) = sqrt(acres * Geo.SQ_M_PER_ACRE) * 3.28084
}
