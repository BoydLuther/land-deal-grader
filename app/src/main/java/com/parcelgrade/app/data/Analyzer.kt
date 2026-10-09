package com.parcelgrade.app.data

import com.parcelgrade.app.domain.AiFindings
import com.parcelgrade.app.domain.GeocodeResult
import com.parcelgrade.app.domain.Geo
import com.parcelgrade.app.domain.LinkItem
import com.parcelgrade.app.domain.Parcel
import com.parcelgrade.app.domain.Report
import com.parcelgrade.app.domain.Scoring
import com.parcelgrade.app.domain.UserInputs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.net.URLEncoder

enum class StepStatus { WAITING, RUNNING, DONE, SKIPPED, FAILED }

enum class Step(val label: String) {
    LOCATE("Finding the address"),
    PARCEL("Loading the parcel boundary"),
    RESEARCH("Searching listings, sales & city plans"),
    FLOOD("Checking FEMA flood zones"),
    WETLANDS("Checking mapped wetlands"),
    TERRAIN("Measuring slope"),
    SOILS("Reading soil surveys"),
    ROADS("Checking road access"),
    GRADE("Grading the deal"),
}

data class Settings(val anthropicKey: String, val model: String, val regridToken: String)

class Analyzer(
    private val settings: Settings,
    private val onStep: (Step, StepStatus, String?) -> Unit,
) {
    private suspend fun <T> step(s: Step, block: suspend () -> T?): T? {
        onStep(s, StepStatus.RUNNING, null)
        return try {
            val r = block()
            onStep(s, if (r == null) StepStatus.SKIPPED else StepStatus.DONE, if (r == null) "No data found" else null)
            r
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            onStep(s, StepStatus.FAILED, t.message?.take(140) ?: t.javaClass.simpleName)
            null
        }
    }

    suspend fun run(input: UserInputs): Report = coroutineScope {
        // 1. Locate (required)
        onStep(Step.LOCATE, StepStatus.RUNNING, null)
        val g = try { PublicData.geocode(input.address) } catch (t: Throwable) {
            onStep(Step.LOCATE, StepStatus.FAILED, t.message); throw t
        }
        onStep(Step.LOCATE, StepStatus.DONE, g.matchedAddress)

        // 2. Parcel boundary from Regrid when a token is set
        val regrid: Parcel? = if (settings.regridToken.isBlank()) {
            onStep(Step.PARCEL, StepStatus.SKIPPED, "No Regrid token; using an estimated footprint"); null
        } else step(Step.PARCEL) { PublicData.regridParcel(g.point, settings.regridToken) }

        val provisional = regrid ?: Parcel(Geo.squareParcel(g.point, input.acres ?: 5.0), input.acres ?: 5.0, true)

        // 3. AI research runs alongside the site checks when we already know the acreage.
        val aiJob = async {
            if (settings.anthropicKey.isBlank()) {
                onStep(Step.RESEARCH, StepStatus.SKIPPED, "Add a Claude API key in Settings to find listings and comps"); null
            } else step(Step.RESEARCH) {
                AiResearch.research(settings.anthropicKey, settings.model, g, provisional, input.acres, input.askingPrice)
            }
        }
        val ai: AiFindings? = if (regrid == null && input.acres == null) aiJob.await() else null

        val acres = regrid?.acres ?: input.acres ?: ai?.acres ?: 5.0
        val parcel = regrid ?: Parcel(
            rings = Geo.squareParcel(g.point, acres),
            acres = acres,
            isEstimated = true,
            zoning = ai?.zoning,
            apn = ai?.apn,
            assessedValue = ai?.assessedValue,
            lastSalePrice = ai?.lastSalePrice,
            lastSaleDate = ai?.lastSaleDate,
        )
        val samples = Geo.samplePoints(parcel.rings)

        // 4. Site checks in parallel
        val floodJob = async { step(Step.FLOOD) { PublicData.flood(parcel.rings, samples) } }
        val wetJob = async { step(Step.WETLANDS) { PublicData.wetlands(parcel.rings, samples) } }
        val terrainJob = async { step(Step.TERRAIN) { PublicData.terrain(parcel.rings) } }
        val soilJob = async { step(Step.SOILS) { PublicData.soils(Geo.bbox(parcel.rings).center) } }
        val roadJob = async { step(Step.ROADS) { PublicData.roads(parcel.rings, parcel.isEstimated) } }

        val flood = floodJob.await()
        val wet = wetJob.await()
        val findings = ai ?: aiJob.await()

        onStep(Step.GRADE, StepStatus.RUNNING, null)
        val constrained = if (flood != null || wet != null)
            PublicData.unionPct(samples, flood?.sfhaPolygons.orEmpty(), wet?.polygons.orEmpty()) else null
        val report = Scoring.build(
            Scoring.Inputs(
                user = input,
                geocode = g,
                parcel = parcel,
                flood = flood?.result,
                wetlands = wet?.result,
                constrainedPct = constrained,
                terrain = terrainJob.await(),
                soils = soilJob.await(),
                roads = roadJob.await(),
                ai = findings,
                links = links(g, findings),
            )
        )
        onStep(Step.GRADE, StepStatus.DONE, report.grade)
        report
    }

    companion object {
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        fun links(g: GeocodeResult, ai: AiFindings?): List<LinkItem> {
            val p = g.point
            val area = listOfNotNull(g.county, g.state).joinToString(", ").ifBlank { g.matchedAddress }
            val out = mutableListOf<LinkItem>()
            ai?.listing?.let { l -> l.url?.let { out.add(LinkItem("Listing for this property", it, l.source)) } }
            out += LinkItem("Zillow – this address", "https://www.zillow.com/homes/${enc(g.matchedAddress).replace("+", "-")}_rb/")
            out += LinkItem("LandWatch – land for sale in $area", "https://www.google.com/search?q=${enc("site:landwatch.com land for sale $area")}")
            out += LinkItem("Land.com – land for sale in $area", "https://www.google.com/search?q=${enc("site:land.com land for sale $area")}")
            if (g.city != null && g.state != null) {
                out += LinkItem("Realtor.com – lots & land in ${g.city}", "https://www.realtor.com/realestateandhomes-search/${enc(g.city.replace(' ', '-'))}_${g.state}/type-land")
            }
            out += LinkItem("County GIS & parcel records", "https://www.google.com/search?q=${enc("$area GIS parcel viewer")}")
            out += LinkItem("County comprehensive plan & zoning map", "https://www.google.com/search?q=${enc("$area comprehensive plan future land use map")}")
            out += LinkItem("Satellite view", "https://www.google.com/maps/search/?api=1&query=${p.lat},${p.lon}")
            out += LinkItem("FEMA Flood Map Service Center", "https://msc.fema.gov/portal/search?AddressQuery=${enc(g.matchedAddress)}")
            out += LinkItem("USFWS Wetlands Mapper", "https://fwsprimary.wim.usgs.gov/wetlands/apps/wetlands-mapper/")
            out += LinkItem("USDA Web Soil Survey", "https://websoilsurvey.nrcs.usda.gov/app/WebSoilSurvey.aspx")
            return out
        }
    }
}
