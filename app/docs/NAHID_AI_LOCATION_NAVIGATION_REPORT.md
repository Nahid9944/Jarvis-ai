# NAHID AI — Live GPS & Worldwide Navigation Report (REQ 4)

## 1. Silent Dhaka Fallback Eliminated (`AssistantToolExecutor.kt`, `ToolRegistry.kt`)
- Previously, omitting `origin` in `calculateDistanceAndRoute` silently defaulted to `"ঢাকা"`.
- Now when `origin` is omitted:
  1. Checks `JarvisLiveLocationTracker.isLocationPermissionGranted(context)`. If missing -> returns `success = false`, `status = "PERMISSION_REQUIRED"`, `needsLocationPermission = true`.
  2. Queries `JarvisLiveLocationTracker.fetchCurrentLocation(context)`. If neither live GPS fix nor valid cached fix exists -> returns `success = false`, `status = "LOCATION_UNRESOLVED"` (no silent city guess).
  3. When cached GPS is used, explicitly flags `originIsCached = true`, `originSource = "GPS_CACHED"`, and appends cache age notice to `summaryBengali`.

## 2. Worldwide Geocoding & Real Road Routing (`BangladeshCalendarAndGeoEngine.kt`)
- Added `geocodeWorldwidePlace(context, query)` four-tier resolution:
  1. Direct `"lat,lng"` coordinate literal (`COORDINATE_LITERAL`).
  2. Fast Bangladesh district/landmark offline table (`BD_OFFLINE_INDEX`).
  3. Android platform `Geocoder` when present (`ANDROID_GEOCODER`).
  4. Worldwide OpenStreetMap Nominatim HTTPS endpoint (`NOMINATIM_OSM`, `User-Agent: NAHID-AI-Android/1.0`).
- Added `calculateWorldwideRouteAndEta(...)` with injectable `geoHttpClient`:
  - Queries live OSRM road routing API (`OSRM_ROAD_ROUTE`, `isExactRoadRoute = true`) for real driving distance (meters/km) and road duration ETA.
  - Falls back transparently to Haversine straight-line calculation (`ESTIMATED_STRAIGHT_LINE_HAVERSINE`, `isExactRoadRoute = false`) when offline or OSRM fails, stating the approximation clearly in Bengali.
- `getCurrentGpsLocation` also emits `isFresh = !liveLoc.isCached` and `ageMinutes`.

## 3. Verification Status
- Permission gate, no-silent-Dhaka rejection, cached timestamp honesty, international Nominatim OSM + OSRM road route JSON parsing, and offline Haversine fallback: **Verified via Robolectric JVM tests**.
- Physical device GPS satellite/network fix & Google Maps Intent launch: **`DEVICE TEST PENDING`**.
