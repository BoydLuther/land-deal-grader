package com.parcelgrade.app.data

import com.parcelgrade.app.domain.AiFindings
import com.parcelgrade.app.domain.CityPlan
import com.parcelgrade.app.domain.Comp
import com.parcelgrade.app.domain.GeocodeResult
import com.parcelgrade.app.domain.Listing
import com.parcelgrade.app.domain.Parcel
import org.json.JSONArray
import org.json.JSONObject

/**
 * Uses Claude with web search to find the listing, comparable land sales, utilities and
 * city/county plans for the parcel. Requires the user's own Anthropic API key.
 */
object AiResearch {

    const val DEFAULT_MODEL = "claude-sonnet-5-5"
    private const val API = "https://api.anthropic.com/v1/messages"

    private fun prompt(g: GeocodeResult, parcel: Parcel, userAcres: Double?, userPrice: Double?): String = """
You are a land acquisition analyst. Research this U.S. land parcel using web search and report facts you can find online.

Address: ${g.matchedAddress}
County: ${g.county ?: "unknown"}, State: ${g.state ?: "unknown"}
Coordinates: ${g.point.lat}, ${g.point.lon}
${if (!parcel.isEstimated) "Parcel size from county records: ${"%.2f".format(parcel.acres)} acres" else ""}
${userAcres?.let { "Buyer says the parcel is $it acres." } ?: ""}
${userPrice?.let { "Buyer says the asking price is $$it." } ?: ""}

Find, in this order:
1. A current or recent sale listing for THIS property (Zillow, LandWatch, Land.com, Realtor.com, Redfin, LandSearch, Lands of America, broker sites). Asking price, acreage, status, listing URL.
2. County parcel facts: acreage, zoning, parcel ID (APN), assessed value, last sale price and date.
3. 3–8 comparable VACANT LAND sales (sold within ~24 months preferred, or active listings if sales are scarce) in the same county, ideally within 10 miles and of similar size. Each with price, acres, date and URL.
4. Utilities available at the road: public water, public sewer, electric.
5. Whether the parcel has a deeded/recorded access easement if it lacks road frontage.
6. City/county future plans near the parcel: comprehensive plan land-use designation, rezonings, new roads or highway projects, utility extensions, large developments, data centers, schools, conservation easements, landfills or other nuisances.
7. Growth outlook for the area (population and job trends).

Rules: Do not guess numbers. Use null when you can't find something. Do not include any owner names or personal information. Comparable prices must be real figures from sources you found.

When finished, reply with ONLY one JSON object in this exact shape (no prose before or after):
{
 "listing": {"found": true, "url": "", "source": "", "askingPrice": 0, "acres": 0, "status": "active|pending|sold|off-market", "listedDate": "", "description": "one sentence"} or null,
 "parcel": {"acres": 0, "zoning": "", "apn": "", "assessedValue": 0, "lastSalePrice": 0, "lastSaleDate": ""},
 "comps": [{"address": "", "acres": 0, "price": 0, "date": "", "status": "sold|active", "url": ""}],
 "compMedianPricePerAcre": 0,
 "utilities": {"water": "", "sewer": "", "electric": ""},
 "deededEasement": true|false|null,
 "accessNotes": "",
 "cityPlans": [{"title": "", "summary": "one sentence", "impact": "positive|negative|neutral", "url": ""}],
 "growthScore": 0-10,
 "growthNotes": "one or two sentences",
 "redFlags": ["short phrases"],
 "summary": "two or three sentences on whether this looks like a good land deal and why"
}
""".trimIndent()

    suspend fun research(
        apiKey: String, model: String, g: GeocodeResult, parcel: Parcel,
        userAcres: Double?, userPrice: Double?,
    ): AiFindings {
        val tool = JSONObject()
            .put("type", "web_search_20250305")
            .put("name", "web_search")
            .put("max_uses", 10)
            .put("user_location", JSONObject().put("type", "approximate").put("country", "US").apply {
                g.state?.let { put("region", it) }
                g.city?.let { put("city", it) }
            })
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", prompt(g, parcel, userAcres, userPrice)))
        val headers = mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01")

        var response: JSONObject? = null
        // The API may pause a long search loop; resend the partial turn to let it continue.
        for (attempt in 0 until 4) {
            val body = JSONObject()
                .put("model", model.ifBlank { DEFAULT_MODEL })
                .put("max_tokens", 8000)
                .put("tools", JSONArray().put(tool))
                .put("messages", messages)
            val r = try {
                Http.postJson(API, body, headers, slow = true)
            } catch (e: HttpException) {
                throw IllegalStateException(
                    when (e.code) {
                        401 -> "Claude rejected the API key. Check it in Settings."
                        404 -> "Model \"$model\" wasn't found. Check the model name in Settings."
                        429 -> "Claude API rate limit reached. Wait a minute and try again."
                        else -> "Claude API error (${e.code})."
                    }
                )
            }
            response = r
            if (r.optString("stop_reason") != "pause_turn") break
            messages.put(JSONObject().put("role", "assistant").put("content", r.getJSONArray("content")))
        }
        val resp = response ?: throw IllegalStateException("No response from Claude")

        val blocks = resp.optJSONArray("content").objects()
        // Only the text after the last search result holds the final JSON answer.
        val lastToolIdx = blocks.indexOfLast { it.optString("type") == "web_search_tool_result" }
        val text = blocks.drop(lastToolIdx + 1).filter { it.optString("type") == "text" }
            .joinToString("") { it.optString("text") }
            .ifBlank { blocks.filter { it.optString("type") == "text" }.joinToString("") { it.optString("text") } }

        val sources = linkedMapOf<String, String>()
        blocks.filter { it.optString("type") == "text" }.forEach { b ->
            b.optJSONArray("citations").objects().forEach { c ->
                val url = c.str("url"); if (url != null) sources.putIfAbsent(url, c.str("title") ?: url)
            }
        }
        return parse(extractJson(text), sources.map { it.value to it.key })
    }

    private fun extractJson(text: String): JSONObject {
        val fenced = Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(text)?.groupValues?.get(1)
        val candidate = fenced ?: run {
            val s = text.indexOf('{'); val e = text.lastIndexOf('}')
            if (s < 0 || e <= s) throw IllegalStateException("Claude's research came back without structured results. Try again.")
            text.substring(s, e + 1)
        }
        return JSONObject(candidate)
    }

    private fun parse(j: JSONObject, sources: List<Pair<String, String>>): AiFindings {
        val l = j.optJSONObject("listing")
        val listing = if (l != null && l.bool("found") != false && (l.str("url") != null || l.num("askingPrice") != null)) Listing(
            url = l.str("url"), source = l.str("source"),
            askingPrice = l.num("askingPrice")?.takeIf { it > 0 },
            acres = l.num("acres")?.takeIf { it > 0 },
            status = l.str("status"), listedDate = l.str("listedDate"), description = l.str("description"),
        ) else null
        val p = j.optJSONObject("parcel") ?: JSONObject()
        val comps = j.optJSONArray("comps").objects().map {
            Comp(
                address = it.str("address") ?: "Nearby parcel",
                acres = it.num("acres")?.takeIf { a -> a > 0 },
                price = it.num("price")?.takeIf { v -> v > 0 },
                date = it.str("date"), status = it.str("status"), url = it.str("url"),
            )
        }.filter { it.pricePerAcre != null }
        val utilities = j.optJSONObject("utilities")?.let { u ->
            u.keys().asSequence().mapNotNull { k -> u.str(k)?.let { k to it } }.toMap()
        } ?: emptyMap()
        val plans = j.optJSONArray("cityPlans").objects().mapNotNull {
            val t = it.str("title") ?: return@mapNotNull null
            CityPlan(t, it.str("summary") ?: "", it.str("impact") ?: "neutral", it.str("url"))
        }
        val flags = j.optJSONArray("redFlags")?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } } } ?: emptyList()
        return AiFindings(
            listing = listing,
            acres = p.num("acres")?.takeIf { it > 0 } ?: listing?.acres,
            zoning = p.str("zoning"),
            apn = p.str("apn"),
            assessedValue = p.num("assessedValue")?.takeIf { it > 0 },
            lastSalePrice = p.num("lastSalePrice")?.takeIf { it > 0 },
            lastSaleDate = p.str("lastSaleDate"),
            comps = comps,
            compMedianPricePerAcre = j.num("compMedianPricePerAcre")?.takeIf { it > 0 },
            utilities = utilities,
            deededEasement = j.bool("deededEasement"),
            accessNotes = j.str("accessNotes"),
            cityPlans = plans,
            growthScore = j.num("growthScore"),
            growthNotes = j.str("growthNotes"),
            redFlags = flags,
            summary = j.str("summary"),
            sources = sources,
        )
    }
}
