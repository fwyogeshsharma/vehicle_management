# FreightDesk → vehicle_management import

A one-time data load, deliberately **not** a Liquibase changeset: it depends on data exported
from another system, and a changeset must run the same everywhere.

| FreightDesk row (its "Trust" badge) | Goes to |
|---|---|
| **Verified** (`image_api`, `review_status = PASSED`), no photos | `vehicles`, with driver, company, membership and locations |
| **Pending Review** (`image_api`, `review_status` NULL / `PENDING`), with photos | `vehicle_intake`, for a CSR to complete |
| Auto Verified (video/stream), Rejected, Verified *with* photos, Pending Review *without* photos | not imported; counted in the report |

## Run

```bat
:: 0. back up vehicle_management, and copy FreightDesk's report photos into the
::    vehicle_management bucket with their keys unchanged (intake rows point at them)

:: 1. export, against FreightDesk
psql "<freightdesk-url>" -f export.sql

:: 2. once: the import's own tables (body type map, record of what was loaded)
psql "<vm-url>" -f setup.sql

:: 3. dry run, against vehicle_management -- does everything, prints the report, rolls back
psql "<vm-url>" -f import.sql

:: 4. map any body types the report lists, then dry-run again until the report looks right
psql "<vm-url>" -c "INSERT INTO fd_import.body_type_map VALUES ('container', (SELECT id FROM body_types WHERE name_key = 'container 20 ft'))"

:: 5. for real
psql "<vm-url>" -v commit=1 -f import.sql
```

Run both from the directory that should hold the CSVs. `fd_vehicle_exceptions.csv` lists every
Verified row that could not become a vehicle, with the reason.

**Re-runnable.** `fd_import.loaded` records every FreightDesk row already imported; a re-run
skips those, so fixing a mapping and running again picks up only what failed. A dry run
advances `vehicle_intake_id_seq` (sequences ignore ROLLBACK), which leaves harmless gaps in ids.

## Rules, and where they come from

- **Registration** must match `Normalizer.REGISTRATION` (stricter than the CHECK), so an
  imported plate is one the API also accepts on the next edit. BH-series plates are rejected,
  as the API rejects them.
- **One vehicle per plate.** The newest complete row wins; other rows with that plate are
  recorded as merged into the same vehicle, and their cities are kept as locations.
- **Ownership follows `VehicleService.intake`:** the truck's number is the driver (found or
  created by mobile, `user_type` DRIVER). A company name makes the truck company-owned, with
  the driver put on the company's books; with no company the driver owns it.
- **A driver with no name** gets the placeholder `Driver <mobile>` and a note on the user row.
- **Locations** go to `vehicle_x_location` (the vehicle's own, additive to its company's), and
  only for a city name that exists in exactly one state.
- An existing plate, or a mobile that belongs to a **deactivated** user, is skipped and never
  overwritten.
