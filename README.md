# Vehicle Management

Companies, vehicles, the people attached to them, and where they run.

Java 21 · Spring Boot 3.3.5 · Maven · Liquibase · PostgreSQL 15+

The data model, and a REST API over it. The UI is a **separate project** and consumes this
one's API.

---

## Quick start

```bat
createdb vehicle_management                   :: or: CREATE DATABASE vehicle_management;
set VM_DATABASE_PASSWORD=your-postgres-password
set VM_JWT_SECRET=<at least 32 characters>    :: openssl rand -base64 48
set VM_ADMIN_USERNAME=root
set VM_ADMIN_PASSWORD=<a real password>
set VM_ADMIN_MOBILE=9800000001
mvn spring-boot:run
```

Liquibase builds the schema, Hibernate validates the entities against it, the seeder loads
36 states, 3,285 cities and 15 body types, the first administrator is created, and the API comes
up on `:8080`. `GET /swagger-ui.html` is the contract the UI project builds against.

Run it again and the seeder reports `Reference data already present`, the administrator is not
re-created, and nothing changes.

**`VM_JWT_SECRET` has no default and the application will not start without it.** That is
deliberate: a development fallback is exactly how a known signing key reaches production, and
anyone holding it can mint a token for any account. The bootstrap administrator is the same —
with any of its three settings missing it is skipped with a warning rather than falling back to
a built-in account with a guessable password.

For something to click around in, add `set VM_SEED_SAMPLE=true`. It loads three vehicles across
three companies and covers every branch of the model — an owner-driver, a hired driver, a driver
with no login, one person on two companies' books, and a company with no locations whose vehicle
therefore serves nowhere. It **refuses a database that already has vehicles**, and prints the
password of the one sample login it creates.

### Tests

```bat
mvn test      :: fast, no database -- validation rules only
mvn verify    :: the real run: every invariant, against a real PostgreSQL
```

The API suites (`AuthIT`, `AuthorizationIT`, `TokenRevocationIT`, `VehicleApiIT`,
`CompanyDriverApiIT`, `PagingAndSortingIT`, `CorsIT`) run against a **real server on a random
port** with a real HTTP client, not MockMvc, so the filter chain, the JSON naming strategy and
the error handler are all genuinely in the path. They build request bodies with snake_case keys
written out literally and read responses as raw JSON — round-tripping the DTO records would use
the same naming strategy on both sides and agree with itself whatever the wire format was.

`mvn verify` needs a PostgreSQL. Two ways, in this order:

```bat
:: 1. point it at a scratch database yourself
set VM_TEST_DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/vehicle_management_test
set VM_TEST_DATABASE_USER=postgres
set VM_TEST_DATABASE_PASSWORD=your-postgres-password

:: 2. or just have Docker running, and Testcontainers provides one
```

With neither, the database tests **skip with a reason** rather than passing vacuously.
`VM_TEST_DATABASE_URL` is deliberately a different variable from `VM_DATABASE_URL`, so having
your working database exported cannot cause an accident — the tests truncate.

**There is no H2 fallback, on purpose.** This schema is built out of `num_nonnulls`, stored
generated columns, partial indexes, `UNIQUE NULLS NOT DISTINCT` and the `~` regex operator, and
H2 has none of them. An H2 suite would go green while testing a database with none of the
constraints the tests exist to prove — which is worse than no tests, because it looks like
coverage.

---

## The shape of it

```
states ─< cities                         body_types
users   ─< user_x_company >─ companies
vehicles ─< vehicle_x_user >─ users      (who DRIVES; ownership is on vehicles)
vehicles ─< vehicle_x_location            companies ─< company_x_location

views:  vehicle_x_company                vehicle_effective_locations
```

### A vehicle has exactly one owner

Either a company or a person, never both, never neither. That lives in two columns on
`vehicles` with a `CHECK (num_nonnulls(owner_company_id, owner_user_id) = 1)` — **not** in two
link tables, because split across two tables the rule is unenforceable. Under `READ COMMITTED`,
two concurrent transfers each read the other's table before it commits, see nothing, and both
succeed:

| | T1: sell to company 3 | T2: sell to user 9 |
|---|---|---|
| 1 | reads `vehicle_x_user` → no owner | |
| 2 | | reads `vehicle_x_company` → no owner (T1 invisible) |
| 3 | inserts | inserts |
| 4 | commits | commits → **two owners, no constraint violated** |

On one row they contend on that row's lock instead, and no service code has to remember to take
it. `vehicle_x_company` still exists as a **read-only view**, so queries written against that
name work; inserting into it fails, which is correct.

### Owning and driving are different facts

`vehicles` answers **who owns this**. `vehicle_x_user` answers **who drives this** — for a
company's trucks and an owner-operator's alike.

The owner-operator — a driver who owns his own truck, and most of Indian trucking — is recorded
once for each:

```
vehicles.owner_user_id = Ramesh     he owns it
vehicle_x_user         = Ramesh     he drives it
```

`vehicle_x_user` used to carry a `role` of OWNER or DRIVER as well. The OWNER rows were a copy of
`vehicles.owner_user_id`, held honest by a composite foreign key over a generated column — a lot
of machinery to stop a duplicate drifting. `002-driver-only-links.sql` removed the rows and
everything that policed them. Nothing was lost: every OWNER row was reconstructable from the
vehicle it pointed at, which is exactly what that changeset's rollback does.

Because the owner-operator is the common case, `create(...)` takes an `ownerAlsoDrives` flag and
writes the driver row for you. It is explicit rather than inferred from `user_type` — an owner who
employs a driver and never sits in the cab is just as real.

### A company's truck takes only a company's driver

There is no direct company-to-driver table; there are two links, and they are now tied together:

- **employment** — `user_x_company`, many-to-many, with a free-text position
- **driving a company's truck** — `vehicle_x_user` reached through `vehicles.owner_company_id`

A driver on a company vehicle **must** be on that company's books. `vehicle_x_user` carries two
mirror columns, `owner_company_id` and `is_company_owned`, pinned by foreign keys so they cannot
drift: one to the vehicle's own owner, one to `user_x_company`. Naming the wrong company, naming
no company, or claiming the truck is personally owned are all refused. An owner-operator is
unaffected — a person-owned vehicle has no payroll to be on, so he may hand the keys to anyone.

Employment ends with `ON DELETE CASCADE`: leaving the company removes the truck assignment too,
which is the only way the rule stays true without someone remembering a second step.

### Preferred locations: a state, optionally one city

`(state_id NOT NULL, city_id NULL)`. A null city means the whole state — "anywhere in
Maharashtra" — and such a row matches **every city in that state** when you ask who serves a
place. Two constraints carry it:

- `UNIQUE NULLS NOT DISTINCT (owner, state_id, city_id)` — without it, PostgreSQL treats every
  NULL as distinct and a whole-state preference could be added unboundedly.
- A composite FK `(city_id, state_id) → cities (id, state_id)` — makes "Nagpur, Gujarat"
  referentially impossible, and skips itself when the city is null.

**A vehicle's own locations exist only while no company owns it.** With a company, the company's
locations apply. That is enforced, not documented: inserting one against a company-owned vehicle
is refused, and so is selling the vehicle to a company while such rows exist. `setOwner` clears
them first, and they are *deleted* rather than left dormant — otherwise a list from two owners
ago silently comes back to life if the vehicle is later sold to a driver again.

The fallback itself is one SQL view, `vehicle_effective_locations`, so "where does this vehicle
run?" and "which vehicles run to Nagpur?" cannot drift apart. A company with no locations serves
**nowhere**; it does not fall back to the vehicle.

### What the database owns, and what Java owns

A `CHECK` encodes what is permanently true — physics, canonical storage format, a regulator's
numbering plan. Everything merely *usual* is a Java validator, because a CHECK you have to relax
costs a migration on a live database and a validator costs a commit.

So the registration number is checked twice, differently: the database enforces only that what is
stored is canonical (`^[A-Z0-9]{5,12}$`), and the full format lives in `Normalizer`. That regex
**rejects the BH series** (`22BH1234AA`), which is on the road today — as a CHECK, the first such
truck would be a production migration at 6pm. `NormalizerTest` records the gap so it is
discoverable rather than folklore.

`updated_at` is maintained by a **trigger**, not `@UpdateTimestamp`, because this codebase
prefers native SQL and `@UpdateTimestamp` fires only for ORM flushes. `capacity_tons` is a
**generated column** derived from the free-text `capacity`, so the two cannot contradict each
other.

---

## Layout

```
src/main/java/com/vehiclemanagement/
  domain/      entities; generated and trigger-maintained columns are read-only and @Generated
  repo/        Spring Data; native @Query for the location lookups and the filtered lists
  service/     the rules, and the statement ORDER the schema's guards require
  web/         controllers, DTOs, paging/sorting, one GlobalExceptionHandler
  security/    the filter chain, the token, and the per-request account check
  exception/   ApiException, FieldValidationException -- deliberately free of Spring web types,
               so the services stay callable from a seeder or a batch import
  bootstrap/   MasterDataSeeder (reference data), AdminBootstrap, SampleDataSeeder (dev only)
src/main/resources/
  db/changelog/db.changelog-master.yaml    include order; add a file, never edit a ran one
  db/changelog/changes/001-init-schema.sql the whole schema, as a formatted-SQL changelog
  data/india_states_cities.txt             CODE|State|City;City -- edit and restart
```

### Schema changes

**Liquibase owns the schema; `ddl-auto` is `validate`.** Generated columns, composite foreign
keys, partial and `NULLS NOT DISTINCT` indexes and views are all things Hibernate's DDL
generation cannot express — and several of them exist precisely so they hold against writers
that are not Hibernate. `validate` catches an entity that has drifted at boot rather than at the
first query.

The changelog is **formatted SQL, not XML or YAML tags**. Almost nothing in this schema is
expressible in Liquibase's database-agnostic changes, so tags would mean `<sql>` blocks wrapped
in ceremony — with an extra chance to mistranscribe DDL that already works. The trade is that
the changelog is PostgreSQL-only, which the schema already was.

**Never edit a changeset that has run anywhere.** Liquibase checksums them; an edited one fails
the next deploy. Add a new file under `changes/` — `includeAll` picks it up in name order.

Every changeset declares its own `--rollback`, because Liquibase cannot infer one for raw SQL and
a changeset without one silently blocks rolling back everything applied after it. The whole
changelog has been rolled back and re-applied cleanly, so those declarations are tested, not
decorative:

```bat
mvn liquibase:status  -Dliquibase.url=... -Dliquibase.password=...
mvn liquibase:rollback -Dliquibase.rollbackCount=1 -Dliquibase.url=... -Dliquibase.password=...
mvn liquibase:updateSQL   :: print the DDL instead of running it -- for a release review
```

Reference data is seeded at start-up rather than in a migration, so **extending
`india_states_cities.txt` and restarting is enough**; there is no ledger to repair.

---

## Known limits

- **`user_type` cannot say which company a person owns.** One value per person, so someone who
  owns Company A and drives for Company B is just `OWNER`. This is the model's weakest joint;
  the fix is an additive `role` column on `user_x_company` the moment a second company appears
  against one person.
- **Mobile is the natural key**, which breaks when someone re-registers with a number already in
  the table. A merge path is needed before the first real import, not after.
- **One vehicle, one registration number.** A tractor-trailer has two plates and there is
  nowhere for the second; if trailers matter that is a `trailers` table with its own owner.
- **No permission model.** Authorisation is one line: signed in, or `ROLE_ADMIN`. That is enough
  while STAFF and ADMIN are the only accounts, and it is a `@PreAuthorize` per endpoint to
  tighten later. A wrong permission *vocabulary*, by contrast, is hard to remove — which is why
  there is not one yet.
- **A stolen token cannot be revoked on its own.** Deactivating the account, changing its
  password or removing its login all work immediately; revoking one token while leaving the
  account usable would need a denylist.
- **`findServingCity` was measured at 20,000 vehicles / 21,000 preference rows**: the obvious
  `OR` form makes the planner scan the whole view (221-253ms), so it is written as a UNION of two
  index-able branches instead (102-156ms, identical result). Re-measure if the shape changes.
