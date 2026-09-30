# CLAUDE.md

Guidance for Claude Code (claude.ai/code) working in this repository.

## What this is

The vehicle-management backend: companies, vehicles, the people attached to them, and where
they run. Java 21 · Spring Boot 3.3.5 · Maven · Liquibase · PostgreSQL 15+.

The data model, and the REST API over it. The UI is a **separate project** and consumes this
one's API; there is no UI code here and there should not be.

Sibling repos worth knowing: `C:\repos\tts` is the older Java back office this borrows
conventions from (`Normalizer`, the seeder shape, the states/cities file); `C:\repos\FreightDesk`
is an unrelated Python truck-sighting platform whose docstrings record useful reasoning but
whose code does not transfer.

## Commands

```bat
mvn spring-boot:run   :: migrate, validate, seed, then serve on :8080
mvn test              :: fast; validation rules only, no database
mvn verify            :: the real run -- every invariant, against a real PostgreSQL
```

**`mvn spring-boot:run` fails without `VM_JWT_SECRET`,** by design. See "Security" below.

`mvn verify` needs PostgreSQL: either `VM_TEST_DATABASE_URL` pointing at a scratch database, or
Docker running so Testcontainers can supply one. With neither, the database tests **skip with a
reason** rather than passing vacuously. Maven here runs on Java 11 by default — set
`JAVA_HOME` to a JDK 21 before building.

## Architecture

**Liquibase owns the schema; Hibernate only validates it** (`ddl-auto: validate`). This is not
a style preference. Generated columns, composite foreign keys, partial and `NULLS NOT DISTINCT`
unique indexes and two views are all things Hibernate's DDL generation cannot express — and
several of them exist *precisely* so they hold against writers that are not Hibernate.

The changelog is **formatted SQL** (`db/changelog/changes/*.sql`), not XML or YAML tags, because
none of the above is expressible in Liquibase's agnostic changes. Two rules:

- **Never edit a changeset that has run anywhere.** Liquibase checksums them; an edit fails the
  next deploy with a validation error. Add a new file under `changes/`.
- **Every changeset declares `--rollback`.** Liquibase cannot infer one for raw SQL, and a
  changeset without one silently blocks rollback of everything applied after it. The full
  changelog has been rolled back and re-applied, so the declarations are tested.

The plpgsql trigger function needs `splitStatements:false` — its `$$` body is full of semicolons
and the default splitter would cut it in half.

**Native SQL is preferred over JPQL and Criteria** wherever it is clearer. Entities use plain
`Long` FK columns rather than `@ManyToOne` associations, so there are no lazy-load traps and no
accidental N+1.

### The web layer

`web/` holds the controllers, the DTOs and one `GlobalExceptionHandler`; `security/` holds the
filter chain and the token. Conventions are borrowed from `C:
epos	ts` so there is one shape
to learn — `/api` with no version segment, DTOs as nested records in one `XxxDtos` holder with
`static from(Entity)` factories, controllers returning the DTO directly rather than
`ResponseEntity` — with four of its habits deliberately **not** carried over:

- page clamping lives in **one** place (`PageParams`), not copied into every controller;
- an unknown `sort` value is **rejected** (`Sorts`), not silently ignored — a client sorting by a
  typo gets told, rather than a plausible page ordered by something else;
- the 401 and 403 bodies have **one** definition (`ApiError.NOT_SIGNED_IN` / `NOT_ALLOWED`), used
  by both the security handlers and the exception handler;
- `@PreAuthorize` takes a **constant** (`Roles.ADMIN`), because a misspelt authority is still a
  valid expression that simply never matches.

**`@RequestParam` names are not touched by Jackson's `SNAKE_CASE`.** Spell them out —
`@RequestParam(name = "page_size")` — or the client sends `page_size` and the server reads the
default.

### Security

JWT bearer tokens, no session table. `POST /api/auth/login` issues one; `Authorization: Bearer`
carries it. Signing in is enough for the domain; `ROLE_ADMIN` is needed for **accounts**
(granting or removing a login, changing a role, deactivating) **and for adding or removing a
user**. Drivers are excluded from the API by construction: they have no username and no password
hash.

**`POST /api/vehicles/intake` is the one scoped exception**: it creates a driver, and a company,
as part of registering the truck they belong to, and is open to any signed-in user. If that
endpoint is ever widened, the "only an administrator creates users" rule goes with it.

- **`VM_JWT_SECRET` has no default and boot fails without it.** A development fallback is how a
  known key reaches production. Same for the bootstrap administrator, which is skipped with a
  warning unless `VM_ADMIN_USERNAME` / `_PASSWORD` / `_MOBILE` are all set.
- **Every request re-reads the user row.** That is what lets a deactivation, a password change or
  a demotion take effect immediately rather than whenever the token happens to expire. One
  primary-key read. What it does *not* do is revoke one specific stolen token; that needs a
  denylist.
- **Authorities come from the row, not the `user_type` claim**, so a token cannot carry a
  privilege the account no longer has.
- **CSRF is disabled, correctly and only here:** the credential is a header the browser never
  attaches by itself. Move it to a cookie and CSRF protection has to come back with it.

### Photo intake

Field executives photograph trucks; an OCR worker reads them; a CSR rings the driver and turns
the result into a vehicle. `vehicle_intake` (changeset 005) holds that work in progress, and it
is modelled on FreightDesk's `trucks` row.

**It is a separate table from `vehicles`, and must stay one.** A vehicle needs a canonical unique
registration number, a body type and exactly one owner. At upload time there is none of those,
and OCR may never supply them. Putting a "pending" status on the register would mean relaxing
every constraint the register exists for. (FreightDesk has no equivalent split because its
`trucks` table *is* the sightings log — two required columns, everything else nullable.)

**Two status columns, and they are independent.** `processing_status` is what the machine has
done (QUEUED / PROCESSING / DONE / FAILED); `review_status` is what a human has decided
(PENDING / COMPLETED / DISCARDED). Do not fold them back into one. A row can be FAILED and
perfectly workable — OCR read nothing, but a CSR looking at the photo can usually see the plate,
and on real field photos that is the common case rather than the exotic one. The worklist tabs
are pairs of coordinates on those two axes; see `IntakeService.Tab`.

**`vehicle_intake` is the only table in this schema with two writers, in two languages.**
- This service owns the reported fields, `review_status`, `reviewed_*` and `vehicle_id`.
- `vehicleManagementOcr` owns `processing_status`, `processing_error`, `processed_at`,
  `claimed_at`, `attempts` and every `ocr_*` column. It connects to PostgreSQL directly; its
  queue code is `ocr/db.py` and its tests are `tests/test_db.py`.
- A column added on one side must be added on the other. `ddl-auto: validate` catches a Java
  field with no column; **nothing catches the reverse.**
- `IntakeService.retry` is the single exception — the one place this service writes the machine
  dimension, to put a row back in the queue.

Because of that split, the guarantees that must not be broken by *either* writer live in the
schema as CHECK constraints, and `IntakeIT` asserts them through raw SQL rather than the
services.

- **`IntakeService.complete` delegates to `VehicleService.intake`.** Never create a vehicle here
  directly: that one path already knows the employment rule, the driver assignment order and
  where preferred locations belong.
- **There is no automatic retry, anywhere.** One failed attempt marks the row `FAILED` and no
  worker picks it up again; `IntakeService.retry` — a human pressing **Read again** — is the only
  route back into the queue, and it does not reset `attempts`. Two things are deliberately not
  failures: a worker killed mid-job (the staleness reclaim returns the row to `QUEUED` with
  `attempts` untouched, or every deploy would destroy in-flight work), and OCR that runs cleanly
  and reads nothing (that is `DONE`, and belongs in the CSR worklist).
- **The claim is no longer here.** It is `FOR UPDATE SKIP LOCKED` in the Python worker's
  `ocr/db.py`, tested there against a real PostgreSQL. The two worker endpoints that used to sit
  outside authentication (`/api/intake/claim`, `/api/intake/*/result`) and the shared secret that
  gated them are **gone**. Do not re-add a `permitAll` for a machine caller without reading the
  note in `SecurityConfig`: an empty-by-default secret plus `permitAll` is an open endpoint on
  any deployment that forgets to set the variable.
- **Photos are written to the object store BEFORE the row is inserted**, and the storage key is
  built from a UUID rather than the row id so that it can be. The ordering is what lets
  `image_keys` be NOT NULL, and it is what stops a polling worker seeing a QUEUED row whose
  photos have not landed. FreightDesk inserts first and attaches keys a moment later; that is
  safe there only because an in-process queue hands the id over at the very end.
- **Photo bytes are never in PostgreSQL** (`ImageStore`, with `LocalImageStore` and
  `GcsImageStore` behind it, chosen by `vehicle-management.intake.storage-backend`). Nothing
  parses a key — that discipline is what let FreightDesk change its object layout twice with no
  migration, and it is what lets one key resolve against local disk here and a bucket in
  production. An unrecognised backend name fails startup rather than falling back to local disk.

### The rules the database enforces, and why they live there

Read `src/main/resources/db/changelog/changes/001-init-schema.sql` before changing any of this. Each of these is
load-bearing, and `SchemaInvariantsIT` proves every one of them through **raw SQL**, not through
the services — the whole point is that they hold against a native `UPDATE`, a migration or psql.

- **A vehicle has exactly one owner.** Two nullable columns on `vehicles` with
  `CHECK (num_nonnulls(owner_company_id, owner_user_id) = 1)`. **Not** two link tables: split
  across two tables the rule is unenforceable, because under `READ COMMITTED` two concurrent
  transfers each read the other's table before it commits, see nothing, and both succeed.
  `OwnershipConcurrencyIT` is that scenario. `vehicle_x_company` survives as a **read-only view**.
- **`vehicle_x_user` is drivers only.** It has no `role` column: ownership is on the vehicle row
  and nowhere else. An owner-operator appears in both places, once per fact. It *used* to carry
  OWNER rows mirroring `vehicles.owner_user_id`, policed by a composite FK over a generated
  column; `002` removed the rows and the machinery together. Do not reintroduce a `role` here —
  `SchemaInvariantsIT$OwnershipIsNotInTheLinkTable` will fail, which is the point.
- **`fk_vxu_vehicle` and `fk_vxu_user` are what hold driver rows to real rows** — keep them.
- **A company's vehicle may only be driven by someone on that company's books.** Same
  composite-FK chain: `vehicle_x_user` mirrors the vehicle's `owner_company_id` and
  `is_company_owned`, one FK pins the mirror to the vehicle, another pins it to
  `user_x_company`. The `is_company_owned` FK has two NOT NULL columns so it is never skipped —
  that is what stops a row claiming to be person-owned to dodge the employment check. Ending
  employment cascades the assignment away. A person-owned vehicle has no such rule.
- **A vehicle's own locations are ADDITIVE to its company's** (changeset 009). Until then a
  company-owned truck could hold none of its own: a `ck_vxl_not_company_owned` CHECK, an
  `is_company_owned` discriminator column and a composite FK with `ON UPDATE NO ACTION` all
  enforced it, and `VehicleService.requirePlacesAllowed` refused it at the door. The enforcement
  was sound; the rule was wrong about the business. A haulier whose fleet runs Maharashtra still
  has one truck on a Nagpur shuttle, and the only way to say so was to reroute the whole company.
  `vehicle_effective_locations` now returns both sets and tags each row COMPANY or VEHICLE — so
  callers wanting a list of *places* rather than *claims* must DISTINCT on (state_id, city_id),
  which `VehicleRepository.locationsFor` does.
- **`VehicleService.companyLocations` duplicates CompanyService's validation** deliberately —
  injecting CompanyService here would make the two services a dependency cycle. If the location
  rules change, both copies change.
- **A vehicle sold to a new owner loses its own locations**, the same way it loses its drivers:
  a route from two owners ago silently matching loads is worse than re-entering it.
  `VehicleService.setOwner` deletes them. This is the one part of the old company-owned rule that
  survives changeset 009, and it survives for a different reason — provenance, not exclusivity.
- **A city must be in the state named beside it** — composite FK to `cities (id, state_id)`,
  which skips itself when the city is null (the whole-state case).
- **`updated_at` is a trigger**, not `@UpdateTimestamp`, which fires only for ORM flushes.
- **`capacity_tons` is a generated column** derived from the free-text `capacity`.

### Traps this code has already hit

- **A bare `%` in a `.sql` file is a parameter placeholder** to several drivers and tools —
  psycopg refuses the whole migration over one, *even inside a comment*. Use `mod(a, b)`.
- **`substring(x from pattern)` returns the first parenthesised subexpression**, not the whole
  match. Written `'[0-9]+(\.[0-9]+)?'` it silently yields `.5` for `22.5 MT`. The outer
  parentheses and the non-capturing inner group in `capacity_tons` are deliberate.
- **Hibernate flushes inserts before deletes**, which trips `fk_vxu_owner_matches` on every
  ownership change. `VehicleService.setOwner` deletes and flushes explicitly, in that order. Do
  not "simplify" it.
- **`ON DELETE SET NULL` is unavailable on the owner FKs** — nulling either would leave
  `num_nonnulls(...) = 0`. `RESTRICT` is forced.
- **`findServingCity` is a UNION of two branches, not one `OR`.** Measured at 20k vehicles the
  `OR` form makes the planner scan the whole view (221-253ms); the UNION form is 102-156ms for
  the identical result.
- **Never make `users.username` `NULLS NOT DISTINCT`** — it would permit exactly one
  password-less driver in the entire table.
- **Dropping a column drops any partial index whose predicate mentions it.** `002` tried to
  `DROP INDEX uq_vxu_primary_driver` after dropping `role`; Postgres had already removed it. Use
  `DROP INDEX IF EXISTS` when the column is going too.
- **`save()` on an assigned composite id MERGES, it does not insert.** `vehicle_x_user` and
  `user_x_company` both have assigned `@IdClass` keys, so `JpaRepository.save()` finds the
  existing row and updates it. `addDriver` and `joinCompany` are therefore *idempotent*, and
  their primary keys never fire through the services — which is a reasonable contract but is
  inherited from JPA rather than chosen. `ConstraintMessagesIT` pins it.
- **A `@RestControllerAdvice` runs before Spring Security's `ExceptionTranslationFilter`.** So a
  catch-all `@ExceptionHandler(Exception.class)` swallows `AccessDeniedException` and turns every
  `@PreAuthorize` failure into a 500 — the endpoint looks broken rather than guarded.
  `GlobalExceptionHandler` handles `AccessDeniedException` and `AuthenticationException`
  explicitly for that reason; do not remove them.
- **A database-generated column is stale in the object after a write** unless it carries
  Hibernate's `@Generated`. `capacity_tons`, `is_company_owned` and the trigger-maintained
  `updated_at` all do, because without it a PUT returns the values the row had *before* the edit
  it just made — a wrong number, returned confidently, by the request that changed it.
- **A JWT's `iat` has one-second resolution**, which is too coarse to compare against
  `password_changed_at`: truncating lets a token minted in the same second as a password change
  survive it, and not truncating rejects the token that change produced. Hence the private
  `iat_ms` claim.
- **A bare parameter in `:p IS NULL` fails in PostgreSQL** with "could not determine data type of
  parameter". Every filter in the `search` queries casts first; the casts are load-bearing.
- **`HttpURLConnection` silently drops `Origin` and the `Access-Control-Request-*` headers**, and
  throws rather than returning a 401 to a POST. That is why `httpclient5` is a test dependency —
  without it the CORS tests pass for the wrong reason and the auth tests cannot run at all.
- **An unmapped path raises `NoResourceFoundException` in Boot 3, not `NoHandlerFoundException`.**
  Handling only the latter left the former to the catch-all, which reported **500** for a URL
  typo — so a client is told the server broke rather than that the path is wrong.
  `GlobalExceptionHandler` catches both; `VehicleApiIT` pins it.
- **`Sorts` yields COLUMN names, so it is only valid for a native query.** Spring Data resolves
  a `Sort` on a *derived* query against entity property names, so handing it `created_at` raises
  `PropertyReferenceException` and a 500 — the property is `createdAt`. Endpoints backed by
  derived queries put the ordering in the method name and pass `Sort.unsorted()`; see
  `IntakeController.list`.
- **Tests must not share the application's upload directory.** The default is `./uploads`,
  relative to the Maven working directory — the same place a locally-running server writes. A
  test run once silently replaced a real 56 KB upload there with a 1x1 pixel fixture, and the
  OCR worker then read an empty image and reported no plate. Storage keys are UUID-based now, so
  that particular id collision is gone, but `DatabaseTest` still points tests at a temp
  directory: they should not be writing where a running server is serving from.
- **A constraint violation surfaces at flush, and an unflushed write flushes at commit** —
  after the service method returned and its try/catch went out of scope. Service writes that
  need a readable error use `saveAndFlush` inside `ConstraintErrors.translating(...)`. Swap it
  back to `save` and the translation silently stops working, with every test still green until
  one asserts the message.

### Error messages

`ConstraintErrors` maps named constraints to readable text — a `Conflict` when the problem is
not one field, a `FieldValidationException` keyed to the input when it is. **Anything unmapped
is rethrown untouched**, deliberately: inventing friendly words for a constraint nobody
anticipated would hide a real bug behind reassuring prose. This works at all only because every
constraint in the schema is explicitly named.

Over HTTP that rethrow would put raw PostgreSQL text, constraint names and column values on the
wire, so `GlobalExceptionHandler`'s catch-all **logs the detail and returns a generic message**.
Everything else becomes `{"detail": "..."}`, or `{"detail":[{"loc":["body",field],"msg":...}]}`
when it belongs to one input. `ConstraintErrors`' messages are now a public interface: read them
as a stranger before changing one.

### Where a rule belongs

A `CHECK` encodes what is permanently true — physics, canonical storage format, a regulator's
numbering plan. Everything merely *usual* is a Java validator, because a CHECK you have to relax
costs a migration on a live database and a validator costs a commit. Hence the registration
number is checked twice, differently: the database enforces only that what is stored is
canonical, and `Normalizer` holds the full format — which **rejects the BH series**, recorded as
a known gap in `NormalizerTest`.

Likewise `no_of_axles`, `no_of_wheels`, `capacity` and `length_ft` are nullable in the database
and required by `VehicleService`. The first partial third-party import will arrive missing half
of them.

## Tests

**Never H2.** It has no `num_nonnulls`, no stored generated columns, no partial indexes, no
`UNIQUE NULLS NOT DISTINCT` and no `~` operator — an H2 suite would go green while testing a
database with none of the constraints these tests exist to prove.

`DatabaseTest.resetDomainData()` truncates the domain tables and **restores** `is_active` on the
reference tables, because a test that retires a body type would otherwise break every later one.

## Known limits

- `user_type` is one value per person, so it cannot say *which* company someone owns. The fix,
  when a second company appears against one person, is an additive `role` column on
  `user_x_company`.
- `user_type` is deliberately ONE column (DRIVER/OWNER/BOTH/STAFF/ADMIN) mixing domain role and
  system access. CSR / SuperAdmin and a real permissions model belong to the security increment,
  not another value here — adding one is a changeset, so decide the whole set at once.
- Mobile is the natural key; a merge path is needed before the first real import.
- One vehicle, one registration number — no place for a trailer's second plate.
