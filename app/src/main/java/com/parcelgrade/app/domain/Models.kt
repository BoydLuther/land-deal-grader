package com.parcelgrade.app.domain

/** A WGS84 coordinate. */
data class LatLng(val lat: Double, val lon: Double)

data class GeocodeResult(
    val matchedAddress: String,
    val point: LatLng,
    val county: String?,
    val state: String?,       // two-letter code when known
    val city: String?,
    val zip: String?,
    val source: String,
)

/** Parcel footprint: either a real boundary (Regrid) or an estimated square around the point. */
data class Parcel(
    val rings: List<List<LatLng>>,
    val acres: Double,
    val isEstimated: Boolean,
    val apn: String? = null,
    val zoning: String? = null,
    val landUse: String? = null,
    val assessedValue: Double? = null,
    val lastSalePrice: Double? = null,
    val lastSaleDate: String? = null,
)

data class FloodResult(
    val zones: List<String>,          // distinct FEMA zone labels found on the parcel
    val sfhaPct: Double,              // % of parcel in the 1% annual-chance floodplain (A/V zones)
    val floodwayPct: Double,          // % in a regulatory floodway
    val moderatePct: Double,          // % in 0.2% annual-chance (shaded X)
    val coastalV: Boolean,            // any V/VE zone
    val mapped: Boolean,              // false when FEMA returned no polygons at all
)

data class WetlandResult(
    val pct: Double,                  // % of parcel covered by NWI wetlands / water
    val types: List<String>,
)

data class TerrainResult(
    val minFt: Double,
    val maxFt: Double,
    val avgSlopePct: Double,
    val maxSlopePct: Double,
    val samples: Int,
)

data class SoilResult(
    val mapUnit: String,
    val component: String?,
    val drainage: String?,
    val hydric: String?,
    val septic: String?,              // NRCS "ENG - Septic Tank Absorption Fields" rating
    val dwellings: String?,           // NRCS "ENG - Dwellings Without Basements" rating
)

data class RoadResult(
    val frontage: Boolean,
    val frontageRoad: String?,        // name/ref of the road touching the parcel
    val frontageType: String?,        // OSM highway class
    val publicFrontage: Boolean,
    val unpaved: Boolean,
    val nearestPublicRoadM: Double?,  // distance from the parcel edge to the closest public road
    val nearestPublicRoadName: String?,
)

data class Comp(
    val address: String,
    val acres: Double?,
    val price: Double?,
    val date: String?,
    val status: String?,              // "sold" or "active"
    val url: String?,
) {
    val pricePerAcre: Double? get() = if (acres != null && acres > 0 && price != null) price / acres else null
}

data class CityPlan(
    val title: String,
    val summary: String,
    val impact: String,               // positive / negative / neutral
    val url: String?,
)

data class Listing(
    val url: String?,
    val source: String?,
    val askingPrice: Double?,
    val acres: Double?,
    val status: String?,
    val listedDate: String?,
    val description: String?,
)

/** Everything the AI web-research step found. Every field is optional. */
data class AiFindings(
    val listing: Listing?,
    val acres: Double?,
    val zoning: String?,
    val apn: String?,
    val assessedValue: Double?,
    val lastSalePrice: Double?,
    val lastSaleDate: String?,
    val comps: List<Comp>,
    val compMedianPricePerAcre: Double?,
    val utilities: Map<String, String>,
    val deededEasement: Boolean?,
    val accessNotes: String?,
    val cityPlans: List<CityPlan>,
    val growthScore: Double?,         // 0..10
    val growthNotes: String?,
    val redFlags: List<String>,
    val summary: String?,
    val sources: List<Pair<String, String>>, // title, url
)

/** What the user may type beyond the address. */
data class UserInputs(
    val address: String,
    val askingPrice: Double? = null,
    val acres: Double? = null,
)

enum class Severity { GOOD, INFO, WARN, BAD }

data class Factor(
    val key: String,
    val name: String,
    val weight: Int,
    val score: Double?,               // 0..100; null = not enough data, excluded from the grade
    val headline: String,
    val details: List<String>,
)

data class Flag(val severity: Severity, val text: String)

data class LinkItem(val label: String, val url: String, val note: String? = null)

data class Report(
    val inputs: UserInputs,
    val geocode: GeocodeResult,
    val parcel: Parcel,
    val factors: List<Factor>,
    val overallScore: Double,
    val grade: String,
    val gradeCap: String?,            // why the grade was capped, if it was
    val verdict: String,
    val flags: List<Flag>,
    val askingPrice: Double?,
    val acres: Double,
    val usableAcres: Double,
    val pricePerAcre: Double?,
    val pricePerUsableAcre: Double?,
    val compMedianPerAcre: Double?,
    val comps: List<Comp>,
    val listing: Listing?,
    val cityPlans: List<CityPlan>,
    val aiSummary: String?,
    val links: List<LinkItem>,
    val sources: List<Pair<String, String>>,
    val siteOnly: Boolean,            // true when no price data was available
    val generatedAt: Long,
)
