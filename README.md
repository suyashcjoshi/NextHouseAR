# NextHouse AR

Android AR app that lets you tap a location on the map and see nearby housing and crime context over the camera view.

![NextHouse AR app screenshot](docs/assets/screenshot.jpeg)

## Architecture

```mermaid
flowchart LR
  User[Map tap] --> Renderer[NextHouseARRenderer]
  Renderer --> Geocoder[Android Geocoder]
  Renderer --> Crime[Police.uk API]
  Renderer --> Sales[HM Land Registry PPD]
  Renderer --> Transport[TfL API]
  Crime --> Overlay[AR camera panels]
  Sales --> Overlay
  Transport --> Overlay
  Transport --> Map[Map marker details]
  Geocoder --> Overlay
```

## Flow

1. ARCore tracks the device and updates the map with the current camera position.
2. The user taps a map location.
3. The app geocodes the tap to a street and postcode.
4. The selected location gets an AR billboard card that stays anchored in camera space as you move.
5. Optional camera overlay panels can be toggled on:
   - Crime: public Police.uk street-level reports near the selected lat/lng.
   - Property: latest sale price and date from HM Land Registry Price Paid Data by postcode.
   - Nearest station: closest TfL rail or Tube station, distance, and walking time.
6. The map marker still shows address and enabled context.

## Data Sources

- Crime: `https://data.police.uk/api/crimes-street/all-crime`
- Property sale price/date: `https://landregistry.data.gov.uk/data/ppi/transaction-record.json`
- Transport: `https://api.tfl.gov.uk/StopPoint`
- Geocoding: Android `Geocoder`

No paid third-party property/crime API is used.

## License

    Copyright 2021 Google LLC

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        https://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
