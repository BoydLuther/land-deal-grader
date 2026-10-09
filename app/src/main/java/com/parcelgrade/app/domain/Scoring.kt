package com.parcelgrade.app.domain

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Turns raw findings into weighted factor scores and a school-style letter grade.
 *
 * Weights (out of 100): Price vs comps 25, Access 20, Wetlands 15, Flood 15, Buildability 15, Growth 10.
 * A factor without data is left out and the remaining weights are rescaled.
 */
object Scoring {

    /** Linear interpolation through (x, y) knots, clamped at both ends. */
    fun interp(x: Double, knots: List<Pair<Double, Double>>): Double {
        if (x <= knots.first().first) return knots.first().second
        if (x >= knots.last().first) return knots.last().second
        for (i in 0 until knots.size - 1) {
            val (x0, y0) = knots[i]; val (x1, y1) = knots[i + 1]
            if (x in x0..x1) return y0 + (y1 - y0) * (x - x0) / (x1 - x0)
        }
        return knots.last().second
    }

    fun letter(score: Double): String = when {
        score >= 93 -> "A"
        score >= 90 -> "A-"
        score >= 87 -> "B+"
        score >= 83 -> "B"
        score >= 80 -> "B-"
        score >= 77 -> "C+"
        score >= 73 -> "C"
        score >= 70 -> "C-"
        score >= 67 -> "D+"
        score >= 63 -> "D"
        score >= 60 -> "D-"
        else -> "F"
    }

    fun money(v: Double?): String {
        if (v == null) return "—"
        return when {
            v >= 1_000_000 -> "$" + String.format("%.2fM", v / 1_000_000)
            v >= 10_000 -> "$" + String.format("%,.0f", v)
            else -> "$" + String.format("%,.0f", v)
        }
    }

    private fun pct(v: Double) = "${v.roundToInt()}%"

    data class Inputs(
        val user: UserInputs,
        val geocode: GeocodeResult,
        val parcel: Parcel,
        val flood: FloodResult?,
        val wetlands: WetlandResult?,
        val constrainedPct: Double?,       // % of parcel in wetlands OR the 1% floodplain
        val terrain: TerrainResult?,
        val soils: SoilResult?,
        val roads: RoadResult?,
        val ai: AiFindings?,
        val links: List<LinkItem>,
    )

    fun build(i: Inputs): Report {
        val flags = mutableListOf<Flag>()
        val ai = i.ai
        val acres = i.parcel.acres
        val constrained = (i.constrainedPct ?: max(i.wetlands?.pct ?: 0.0, i.flood?.sfhaPct ?: 0.0)).coerceIn(0.0, 100.0)
        val usableAcres = acres * (1 - constrained / 100.0)

        val asking = i.user.askingPrice ?: ai?.listing?.askingPrice
        // Prefer the median computed from the actual comps; fall back to the figure Claude reported.
        val compMedian = median(ai?.comps.orEmpty().mapNotNull { it.pricePerAcre }) ?: ai?.compMedianPricePerAcre
        val ppa = if (asking != null && acres > 0) asking / acres else null
        val ppua = if (asking != null && usableAcres > 0.05) asking / usableAcres else null

        val factors = listOf(
            priceFactor(asking, ppa, ppua, compMedian, ai, i.parcel, flags),
            accessFactor(i.roads, ai, flags),
            wetlandFactor(i.wetlands, acres, flags),
            floodFactor(i.flood, flags),
            buildFactor(i.terrain, i.soils, ai, usableAcres, acres, flags),
            growthFactor(ai, flags),
        )

        val scored = factors.filter { it.score != null }
        val weightSum = scored.sumOf { it.weight }
        var overall = if (weightSum == 0) 0.0 else scored.sumOf { it.score!! * it.weight } / weightSum

        var cap: String? = null
        val landlocked = i.roads != null && !i.roads.frontage && ai?.deededEasement != true
        if (landlocked && overall > 64.0) {
            overall = 64.0; cap = "Capped at D: no road frontage was found, so the parcel may be landlocked."
        }
        if (acres > 0 && usableAcres / acres < 0.15 && overall > 64.0) {
            overall = 64.0; cap = "Capped at D: less than 15% of the land sits outside wetlands and the floodplain."
        }

        val grade = letter(overall)
        val siteOnly = factors.first { it.key == "price" }.score == null
        val verdict = when (grade.first()) {
            'A' -> "Strong deal. The land checks out and the numbers work."
            'B' -> "Good deal with a few items to verify before you commit."
            'C' -> "Average. Workable at the right price; negotiate around the weak spots."
            'D' -> "Weak deal. Serious problems that need a discount or a clear fix."
            else -> "Avoid at this price. The problems outweigh the value."
        }

        if (i.parcel.isEstimated) {
            flags.add(Flag(Severity.INFO, "Parcel boundary is estimated as a ${fmtAcres(acres)}-acre square around the address. Add a Regrid token in Settings for the real boundary."))
        }
        ai?.redFlags?.forEach { flags.add(Flag(Severity.WARN, it)) }

        return Report(
            inputs = i.user,
            geocode = i.geocode,
            parcel = i.parcel,
            factors = factors,
            overallScore = overall,
            grade = grade,
            gradeCap = cap,
            verdict = verdict,
            flags = flags.sortedBy { it.severity.ordinal * -1 },
            askingPrice = asking,
            acres = acres,
            usableAcres = usableAcres,
            pricePerAcre = ppa,
            pricePerUsableAcre = ppua,
            compMedianPerAcre = compMedian,
            comps = ai?.comps.orEmpty(),
            listing = ai?.listing,
            cityPlans = ai?.cityPlans.orEmpty(),
            aiSummary = ai?.summary,
            links = i.links,
            sources = ai?.sources.orEmpty(),
            siteOnly = siteOnly,
            generatedAt = System.currentTimeMillis(),
        )
    }

    fun fmtAcres(a: Double): String = if (a >= 10) String.format("%.0f", a) else String.format("%.2f", a)

    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    // ---- Factors ---------------------------------------------------------------------------

    private fun priceFactor(
        asking: Double?, ppa: Double?, ppua: Double?, compMedian: Double?,
        ai: AiFindings?, parcel: Parcel, flags: MutableList<Flag>,
    ): Factor {
        val details = mutableListOf<String>()
        if (asking != null) details.add("Asking price: ${money(asking)}")
        if (ppa != null) details.add("Price per acre: ${money(ppa)}")
        if (ppua != null && ppa != null && ppua > ppa * 1.05) details.add("Price per usable acre: ${money(ppua)}")
        if (compMedian != null) details.add("Comparable sales median: ${money(compMedian)}/acre (${ai?.comps?.size ?: 0} comps)")
        val assessed = parcel.assessedValue ?: ai?.assessedValue
        if (assessed != null) details.add("Assessed value: ${money(assessed)}")
        val lastSale = parcel.lastSalePrice ?: ai?.lastSalePrice
        if (lastSale != null) details.add("Last recorded sale: ${money(lastSale)}${(parcel.lastSaleDate ?: ai?.lastSaleDate)?.let { " ($it)" } ?: ""}")

        if (asking == null) {
            return Factor("price", "Price vs. recent sales", 25, null,
                "No asking price found. Enter one on the home screen to grade the price.", details)
        }
        if (compMedian == null || ppua == null) {
            return Factor("price", "Price vs. recent sales", 25, null,
                "Not enough comparable land sales nearby to judge the price.", details)
        }
        val ratio = ppua / compMedian
        val score = interp(ratio, listOf(
            0.6 to 100.0, 0.8 to 93.0, 0.95 to 82.0, 1.05 to 73.0,
            1.2 to 58.0, 1.4 to 40.0, 1.7 to 20.0, 2.2 to 5.0,
        ))
        val diff = ((ratio - 1) * 100).roundToInt()
        val headline = when {
            diff <= -5 -> "${-diff}% below comparable sales per usable acre"
            diff >= 5 -> "$diff% above comparable sales per usable acre"
            else -> "In line with comparable sales"
        }
        if (ratio > 1.3) flags.add(Flag(Severity.BAD, "Priced ${diff}% above nearby land sales per usable acre."))
        if (ratio < 0.75) flags.add(Flag(Severity.GOOD, "Priced well below nearby land sales. Confirm there is no hidden defect."))
        return Factor("price", "Price vs. recent sales", 25, score, headline, details)
    }

    private fun accessFactor(r: RoadResult?, ai: AiFindings?, flags: MutableList<Flag>): Factor {
        if (r == null) return Factor("access", "Road access", 20, null,
            "Road data could not be loaded.", listOfNotNull(ai?.accessNotes))
        val details = mutableListOf<String>()
        val score: Double
        val headline: String
        when {
            r.publicFrontage -> {
                score = if (r.unpaved) 85.0 else 100.0
                headline = "Fronts ${r.frontageRoad ?: "a public road"}${if (r.unpaved) " (unpaved)" else ""}"
            }
            r.frontage -> {
                score = 60.0
                headline = "Touches a ${r.frontageType ?: "private"} road or drive only"
                details.add("Confirm a recorded access easement to a public road.")
                flags.add(Flag(Severity.WARN, "Access is by a private drive or track. Verify a deeded easement."))
            }
            r.nearestPublicRoadM != null && r.nearestPublicRoadM <= 200 -> {
                score = 35.0
                headline = "Possibly landlocked: nearest public road is ${r.nearestPublicRoadM.roundToInt()} m away"
                flags.add(Flag(Severity.BAD, "No road frontage found. Parcel may be landlocked."))
            }
            else -> {
                score = 10.0
                headline = "Landlocked: no road found next to the parcel"
                flags.add(Flag(Severity.BAD, "Landlocked. No road reaches the parcel; access would need an easement."))
            }
        }
        r.nearestPublicRoadName?.let { n ->
            r.nearestPublicRoadM?.let { d -> details.add("Nearest public road: $n, ${d.roundToInt()} m from the boundary") }
        }
        var final = score
        if (!r.frontage && ai?.deededEasement == true) {
            final = max(final, 65.0)
            details.add("Records found online mention a deeded access easement.")
        }
        ai?.accessNotes?.takeIf { it.isNotBlank() }?.let { details.add(it) }
        return Factor("access", "Road access", 20, final, headline, details)
    }

    private fun wetlandFactor(w: WetlandResult?, acres: Double, flags: MutableList<Flag>): Factor {
        if (w == null) return Factor("wetlands", "Wetlands", 15, null, "Wetlands inventory could not be loaded.", emptyList())
        val score = interp(w.pct, listOf(0.0 to 100.0, 5.0 to 92.0, 10.0 to 85.0, 25.0 to 60.0, 50.0 to 30.0, 80.0 to 5.0))
        val details = mutableListOf<String>()
        if (w.types.isNotEmpty()) details.add("Types: ${w.types.joinToString(", ")}")
        if (w.pct > 0) details.add("About ${fmtAcres(acres * w.pct / 100)} acres mapped as wetland or water.")
        details.add("Source: U.S. Fish & Wildlife National Wetlands Inventory. Mapped wetlands need a field delineation to confirm.")
        if (w.pct >= 25) flags.add(Flag(Severity.BAD, "${pct(w.pct)} of the parcel is mapped wetland."))
        else if (w.pct >= 5) flags.add(Flag(Severity.WARN, "${pct(w.pct)} of the parcel is mapped wetland."))
        val headline = if (w.pct < 0.5) "No mapped wetlands on the parcel" else "${pct(w.pct)} mapped wetland or water"
        return Factor("wetlands", "Wetlands", 15, score, headline, details)
    }

    private fun floodFactor(f: FloodResult?, flags: MutableList<Flag>): Factor {
        if (f == null) return Factor("flood", "Flood zone", 15, null, "FEMA flood maps could not be loaded.", emptyList())
        val details = mutableListOf<String>()
        if (!f.mapped) {
            return Factor("flood", "Flood zone", 15, 75.0, "No FEMA flood map for this spot",
                listOf("The area has no digital FEMA flood data. Check the county's paper maps or order an elevation survey."))
        }
        if (f.zones.isNotEmpty()) details.add("FEMA zones on the parcel: ${f.zones.joinToString(", ")}")
        var score = interp(f.sfhaPct, listOf(0.0 to 100.0, 10.0 to 85.0, 25.0 to 65.0, 50.0 to 35.0, 100.0 to 5.0))
        if (f.sfhaPct < 0.5 && f.moderatePct > 0) score = min(score, 92.0)
        score -= f.floodwayPct * 0.5
        if (f.coastalV) { score -= 15; details.add("Coastal high-hazard (V) zone present: stricter building rules apply.") }
        if (f.floodwayPct > 0) details.add("${pct(f.floodwayPct)} lies in a regulatory floodway, where building is generally prohibited.")
        if (f.sfhaPct > 0) details.add("Flood insurance is typically required for financed buildings in the 1% annual-chance zone.")
        if (f.sfhaPct >= 25) flags.add(Flag(Severity.BAD, "${pct(f.sfhaPct)} of the parcel is in the 100-year floodplain."))
        else if (f.sfhaPct >= 3) flags.add(Flag(Severity.WARN, "${pct(f.sfhaPct)} of the parcel is in the 100-year floodplain."))
        val headline = when {
            f.sfhaPct >= 0.5 -> "${pct(f.sfhaPct)} in the 100-year floodplain"
            f.moderatePct > 0 -> "Outside the 100-year floodplain; part is in the 500-year zone"
            else -> "Minimal flood risk (Zone X)"
        }
        return Factor("flood", "Flood zone", 15, score.coerceIn(0.0, 100.0), headline, details)
    }

    private fun buildFactor(
        t: TerrainResult?, s: SoilResult?, ai: AiFindings?,
        usableAcres: Double, acres: Double, flags: MutableList<Flag>,
    ): Factor {
        if (t == null && s == null) return Factor("build", "Buildability", 15, null,
            "Terrain and soil data could not be loaded.", emptyList())
        val details = mutableListOf<String>()
        val parts = mutableListOf<Pair<Double, Double>>() // score, weight

        if (t != null) {
            val slopeScore = interp(t.avgSlopePct, listOf(0.0 to 100.0, 5.0 to 100.0, 10.0 to 85.0, 15.0 to 65.0, 25.0 to 35.0, 40.0 to 10.0))
            parts.add(slopeScore to 1.0)
            details.add("Average slope ${String.format("%.1f", t.avgSlopePct)}%, steepest ${String.format("%.0f", t.maxSlopePct)}%")
            details.add("Elevation ${t.minFt.roundToInt()}–${t.maxFt.roundToInt()} ft")
            if (t.avgSlopePct > 15) flags.add(Flag(Severity.WARN, "Steep ground (average ${t.avgSlopePct.roundToInt()}% slope) raises site-work costs."))
        }

        val sewer = ai?.utilities?.get("sewer").orEmpty().lowercase()
        val hasSewer = listOf("public", "available", "yes", "municipal", "city").any { sewer.contains(it) } && !sewer.contains("no ")
        if (s != null) {
            details.add("Soil: ${s.mapUnit}${s.drainage?.let { ", $it" } ?: ""}")
            fun rate(r: String?): Double? = when {
                r == null -> null
                r.contains("Not limited", true) -> 100.0
                r.contains("Somewhat", true) -> 72.0
                r.contains("Very limited", true) -> 38.0
                else -> null
            }
            val septic = rate(s.septic)
            val dwell = rate(s.dwellings)
            if (s.septic != null) details.add("Septic suitability: ${s.septic}${if (hasSewer) " (public sewer reported, so less important)" else ""}")
            if (s.dwellings != null) details.add("Building foundations: ${s.dwellings}")
            if (septic != null) parts.add(septic to if (hasSewer) 0.3 else 1.0)
            if (dwell != null) parts.add(dwell to 0.7)
            if (s.hydric.equals("Yes", true)) {
                parts.add(40.0 to 0.5)
                details.add("Hydric (wet) soil, often a sign of wetlands.")
            }
            if (!hasSewer && s.septic?.contains("Very limited", true) == true) {
                flags.add(Flag(Severity.WARN, "Soils are rated very limited for septic. Get a perc test before buying."))
            }
        }

        ai?.utilities?.let { u ->
            if (u.isNotEmpty()) details.add("Utilities: " + u.entries.joinToString("; ") { "${it.key}: ${it.value}" })
        }
        ai?.zoning?.let { details.add("Zoning: $it") }

        var score = if (parts.isEmpty()) null else parts.sumOf { it.first * it.second } / parts.sumOf { it.second }
        if (score != null && acres > 0 && usableAcres / acres < 0.25) score = min(score, 30.0)
        val headline = when {
            score == null -> "Limited data"
            score >= 85 -> "Easy to build on"
            score >= 70 -> "Buildable with typical site work"
            score >= 50 -> "Buildable with added cost (slope or soils)"
            else -> "Hard to build on"
        }
        return Factor("build", "Buildability", 15, score, headline, details)
    }

    private fun growthFactor(ai: AiFindings?, flags: MutableList<Flag>): Factor {
        val g = ai?.growthScore
        if (ai == null || g == null) return Factor("growth", "Growth & city plans", 10, null,
            "Add a Claude API key in Settings to research city plans and growth.", emptyList())
        val details = mutableListOf<String>()
        ai.growthNotes?.let { details.add(it) }
        ai.cityPlans.forEach { details.add("${it.title} (${it.impact}): ${it.summary}") }
        ai.cityPlans.filter { it.impact.equals("negative", true) }.forEach {
            flags.add(Flag(Severity.WARN, "Nearby plan: ${it.title}"))
        }
        val score = (g.coerceIn(0.0, 10.0)) * 10
        val headline = when {
            g >= 8 -> "Strong growth area"
            g >= 6 -> "Steady growth"
            g >= 4 -> "Flat or mixed outlook"
            else -> "Weak outlook"
        }
        return Factor("growth", "Growth & city plans", 10, score, headline, details)
    }
}
