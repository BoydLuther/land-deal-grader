package com.parcelgrade.app.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Small-area geometry in a local flat projection (accurate to well under 1% at parcel scale). */
object Geo {
    const val SQ_M_PER_ACRE = 4046.8564224
    private const val M_PER_DEG_LAT = 111_320.0

    private fun mPerDegLon(lat: Double) = M_PER_DEG_LAT * cos(lat * PI / 180.0)

    /** Projects to metres relative to an origin. */
    fun toXY(p: LatLng, origin: LatLng): Pair<Double, Double> =
        Pair((p.lon - origin.lon) * mPerDegLon(origin.lat), (p.lat - origin.lat) * M_PER_DEG_LAT)

    fun offset(origin: LatLng, dxM: Double, dyM: Double): LatLng =
        LatLng(origin.lat + dyM / M_PER_DEG_LAT, origin.lon + dxM / mPerDegLon(origin.lat))

    fun distanceM(a: LatLng, b: LatLng): Double {
        val (x, y) = toXY(b, a)
        return sqrt(x * x + y * y)
    }

    /** A square footprint of the given acreage centred on the point. */
    fun squareParcel(center: LatLng, acres: Double): List<List<LatLng>> {
        val half = sqrt(acres * SQ_M_PER_ACRE) / 2.0
        return listOf(
            listOf(
                offset(center, -half, -half),
                offset(center, half, -half),
                offset(center, half, half),
                offset(center, -half, half),
                offset(center, -half, -half),
            )
        )
    }

    /** Even-odd rule across all rings, so holes are handled. */
    fun contains(rings: List<List<LatLng>>, p: LatLng): Boolean {
        var inside = false
        for (ring in rings) {
            val n = ring.size
            if (n < 3) continue
            var j = n - 1
            for (i in 0 until n) {
                val a = ring[i]
                val b = ring[j]
                if ((a.lat > p.lat) != (b.lat > p.lat)) {
                    val xCross = (b.lon - a.lon) * (p.lat - a.lat) / (b.lat - a.lat) + a.lon
                    if (p.lon < xCross) inside = !inside
                }
                j = i
            }
        }
        return inside
    }

    data class BBox(val minLat: Double, val minLon: Double, val maxLat: Double, val maxLon: Double) {
        val center get() = LatLng((minLat + maxLat) / 2, (minLon + maxLon) / 2)
        fun expand(metres: Double): BBox {
            val lo = offset(LatLng(minLat, minLon), -metres, -metres)
            val hi = offset(LatLng(maxLat, maxLon), metres, metres)
            return BBox(lo.lat, lo.lon, hi.lat, hi.lon)
        }
        /** ArcGIS envelope string: xmin,ymin,xmax,ymax */
        fun envelope() = "$minLon,$minLat,$maxLon,$maxLat"
    }

    fun bbox(rings: List<List<LatLng>>): BBox {
        var minLat = 90.0; var maxLat = -90.0; var minLon = 180.0; var maxLon = -180.0
        rings.flatten().forEach {
            minLat = min(minLat, it.lat); maxLat = max(maxLat, it.lat)
            minLon = min(minLon, it.lon); maxLon = max(maxLon, it.lon)
        }
        return BBox(minLat, minLon, maxLat, maxLon)
    }

    /** Evenly spaced points that fall inside the parcel, used to measure coverage percentages. */
    fun samplePoints(rings: List<List<LatLng>>, perSide: Int = 20): List<LatLng> {
        val b = bbox(rings)
        val pts = ArrayList<LatLng>()
        for (i in 0 until perSide) for (j in 0 until perSide) {
            val p = LatLng(
                b.minLat + (b.maxLat - b.minLat) * (i + 0.5) / perSide,
                b.minLon + (b.maxLon - b.minLon) * (j + 0.5) / perSide,
            )
            if (contains(rings, p)) pts.add(p)
        }
        if (pts.isEmpty()) pts.add(b.center)
        return pts
    }

    /** Percentage of sample points that fall inside any of the polygons. */
    fun coveragePct(samples: List<LatLng>, polygons: List<List<List<LatLng>>>): Double {
        if (samples.isEmpty() || polygons.isEmpty()) return 0.0
        val boxes = polygons.map { bbox(it) }
        val hit = samples.count { p ->
            polygons.indices.any { k ->
                val b = boxes[k]
                p.lat in b.minLat..b.maxLat && p.lon in b.minLon..b.maxLon && contains(polygons[k], p)
            }
        }
        return 100.0 * hit / samples.size
    }

    /** Planar area of the rings in acres (outer ring minus holes via signed areas). */
    fun areaAcres(rings: List<List<LatLng>>): Double {
        if (rings.isEmpty()) return 0.0
        val origin = rings[0][0]
        var total = 0.0
        rings.forEachIndexed { idx, ring ->
            var s = 0.0
            val pts = ring.map { toXY(it, origin) }
            for (i in pts.indices) {
                val (x1, y1) = pts[i]
                val (x2, y2) = pts[(i + 1) % pts.size]
                s += x1 * y2 - x2 * y1
            }
            val a = abs(s) / 2.0
            total += if (idx == 0) a else -a
        }
        return abs(total) / SQ_M_PER_ACRE
    }

    private fun pointSegDist(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        val cx = ax + t * dx; val cy = ay + t * dy
        return sqrt((px - cx) * (px - cx) + (py - cy) * (py - cy))
    }

    private fun segmentsIntersect(
        ax: Double, ay: Double, bx: Double, by: Double,
        cx: Double, cy: Double, dx: Double, dy: Double,
    ): Boolean {
        fun orient(px: Double, py: Double, qx: Double, qy: Double, rx: Double, ry: Double) =
            (qx - px) * (ry - py) - (qy - py) * (rx - px)
        val o1 = orient(ax, ay, bx, by, cx, cy)
        val o2 = orient(ax, ay, bx, by, dx, dy)
        val o3 = orient(cx, cy, dx, dy, ax, ay)
        val o4 = orient(cx, cy, dx, dy, bx, by)
        return (o1 > 0) != (o2 > 0) && (o3 > 0) != (o4 > 0)
    }

    /** Shortest distance in metres from a polyline to the parcel (0 when it touches or crosses it). */
    fun polylineToParcelM(line: List<LatLng>, rings: List<List<LatLng>>): Double {
        if (line.isEmpty() || rings.isEmpty()) return Double.MAX_VALUE
        if (line.any { contains(rings, it) }) return 0.0
        val origin = rings[0][0]
        val l = line.map { toXY(it, origin) }
        var best = Double.MAX_VALUE
        for (ring in rings) {
            val r = ring.map { toXY(it, origin) }
            for (i in 0 until r.size - 1) {
                val (ax, ay) = r[i]; val (bx, by) = r[i + 1]
                if (l.size == 1) {
                    best = min(best, pointSegDist(l[0].first, l[0].second, ax, ay, bx, by))
                    continue
                }
                for (k in 0 until l.size - 1) {
                    val (cx, cy) = l[k]; val (dx, dy) = l[k + 1]
                    if (segmentsIntersect(ax, ay, bx, by, cx, cy, dx, dy)) return 0.0
                    best = min(best, pointSegDist(ax, ay, cx, cy, dx, dy))
                    best = min(best, pointSegDist(bx, by, cx, cy, dx, dy))
                    best = min(best, pointSegDist(cx, cy, ax, ay, bx, by))
                    best = min(best, pointSegDist(dx, dy, ax, ay, bx, by))
                }
            }
        }
        return best
    }
}
