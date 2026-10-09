# Parcel Grade

An Android app that grades a land deal A–F from just a property address.

## What it checks

| Factor | Weight | Source |
|---|---|---|
| Price vs. recent land sales (per usable acre) | 25% | Claude web search: listing + comparable sales |
| Road access / landlocked | 20% | OpenStreetMap roads vs. parcel boundary |
| Wetlands | 15% | U.S. Fish & Wildlife National Wetlands Inventory |
| Flood zone | 15% | FEMA National Flood Hazard Layer |
| Buildability (slope, septic & foundation soils) | 15% | USGS elevation, USDA soil survey |
| Growth & city plans | 10% | Claude web search: comp plans, rezonings, roads, utilities |

Grades use a school scale (A ≥ 93, A- ≥ 90, B+ ≥ 87 … F < 60). Missing factors are left out and the
other weights rescale. A parcel with no road frontage (and no known easement) is capped at **D**, as is
one where less than 15% of the land is outside wetlands and the floodplain.

## Install on your phone

Every push to `main` builds a signed APK with GitHub Actions and publishes it under **Releases**.
Open the latest release on your phone, tap `ParcelGrade.apk`, and allow your browser to install apps
when Android asks.

## Settings

- **Claude API key** (recommended) – enables listing, comps, utilities and city-plan research.
  Get one at console.anthropic.com. Default model: `claude-sonnet-5-5`.
- **Regrid token** (optional) – real parcel boundaries, acreage, zoning and assessed value. Without it
  the parcel is estimated as a square of the listed acreage around the address.

## Build locally

Open the folder in Android Studio and run, or `./gradlew assembleRelease`.

The signing key in `app/parcelgrade.keystore` exists only so updates install over each other when
sideloading. Replace it before publishing to the Play Store.
