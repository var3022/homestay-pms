# Homestay Property Management System (PMS)

> **Project status:** In development --- backend foundation and initial
> reservation availability validation are implemented. This document
> records the current known state as of 9 October 2026. A feature should
> not be considered production-ready until its implementation and tests
> have been verified.

## 1. Project Summary

The Homestay Property Management System (PMS) is a backend-first
application intended to manage a homestay's rooms, reservations, guests,
occupancy, and booking sources in one place.

The initial property has nine rooms. The data model is being designed to
support multiple properties in the future, with different room counts
and room configurations.

### Main goals

-   Keep reservations from direct bookings and external booking channels
    synchronized.
-   Prevent overlapping reservations and room assignments.
-   Track room types separately from individual physical rooms.
-   Store guest details and identity-verification information securely.
-   Support check-in, check-out, room assignment, cancellations, and
    stay extensions.
-   Preserve reservation history and operational audit information.
-   Keep the code modular so additional capabilities can be added
    without rewriting core booking logic.

### Out of scope for the initial backend milestone

Food ordering and meal management, laundry workflows, advanced
analytics, and automated checkout reminders are deferred until the core
booking and guest workflows are reliable.

## 2. Current Technology Stack

  -----------------------------------------------------------------------
  Area                                Current technology
  ----------------------------------- -----------------------------------
  Language                            Java 21

  Backend framework                   Spring Boot 4.1.1

  Build tool                          Maven

  Database                            PostgreSQL 16

  Database migrations                 Flyway

  Persistence                         Spring Data JPA / Hibernate

  API style                           REST over HTTP

  Validation                          Jakarta Bean Validation / Spring
                                      Boot validation

  Security dependency                 Spring Security (production
                                      configuration still needs review)

  Development environment             Windows 11, VS Code / Antigravity

  Local database runtime              Docker container
  -----------------------------------------------------------------------

### Local development details

-   Repository directory: `C:\GitHub\homestay-pms`
-   Backend directory: `C:\GitHub\homestay-pms\backend`
-   PostgreSQL container: `homestay-pms-postgres`
-   Database: `homestay_pms`
-   Local backend port: `8080`
-   Property timezone used in development: `Asia/Kolkata`

Do not commit database passwords, API keys, access tokens, guest
identity data, or other secrets to Git.

## 3. Architecture

### 3.1 Current architectural approach

The project is organized as a **layered Spring Boot backend**, with
domain concepts separated into entities, repositories, services, service
implementations, mappers, DTOs, controllers, and enums. This is a
practical modular-monolith starting point: the application is deployed
as one backend while its code is organized into business areas and
layers.

The design should remain modular and integration-friendly, but
microservices should not be introduced until there is a concrete
operational need. A well-separated modular monolith is simpler to
develop, test, and deploy for the initial property.

### 3.2 Request flow

``` text
Client / Staff UI / External Booking Integration
                    |
                    v
             REST Controller
                    |
                    v
       Request DTO + Input Validation
                    |
                    v
             Service Layer
       Business rules and transactions
                    |
          +---------+---------+
          |                   |
          v                   v
      Repositories       Mappers / DTOs
          |
          v
    Spring Data JPA
          |
          v
      PostgreSQL
          ^
          |
       Flyway migrations
```

Typical response path:

``` text
PostgreSQL -> Repository -> Service -> Response Mapper/DTO -> Controller -> HTTP response
```

Controllers should handle HTTP concerns, services should enforce
business rules, repositories should handle persistence queries, and DTOs
should define the API contract. Entity classes should not be exposed
directly as public API contracts.

### 3.3 Main layers

  -----------------------------------------------------------------------
  Layer                               Responsibility
  ----------------------------------- -----------------------------------
  `controller`                        Defines REST endpoints, HTTP status
                                      codes, and request/response
                                      handling

  `dto` (or equivalent                API request and response objects
  request/response packages)          

  `service`                           Service interfaces and
                                      business-operation contracts

  `serviceimpl`                       Business rules, orchestration,
                                      validation, and transaction
                                      boundaries

  `repository`                        Database access through Spring Data
                                      JPA

  `entity`                            JPA-mapped persistence/domain
                                      records

  `mapper`                            Converts request DTOs to entities
                                      and entities to response DTOs

  `enums`                             Controlled values such as
                                      reservation and room statuses

  `exception` / error handling        Consistent errors for validation,
                                      missing resources, and conflicts

  `resources/db/migration`            Versioned SQL schema changes
                                      managed by Flyway
  -----------------------------------------------------------------------

Use enums for stable, finite states and shared constants for genuinely
shared fixed values. Avoid using constants for values that belong in
property configuration or the database.

### 3.4 Core domain relationships

-   **Property** owns room types, physical rooms, and property-level
    configuration.
-   **RoomType** describes a bookable category (for example, Deluxe
    Room).
-   **Room** represents a specific physical room (for example, room 101)
    and belongs to a property and room type.
-   **BedType / RoomBed** describe the beds available in a physical
    room, including extra beds where applicable.
-   **Amenity** describes a facility; property and room amenity
    relationships associate amenities with the relevant scope.
-   **Guest** stores guest profile details.
-   **BookingSource** identifies where a reservation originated (for
    example, direct booking or an external channel).
-   **Reservation** stores booking-level information such as property,
    guest, dates, guest counts, source, external booking identifier, and
    status.
-   **ReservationRoom** records the room type, quantity, price,
    subtotal, and cancellation quantity for a reservation.
-   **RoomAssignment** links a reservation-room line to a specific
    physical room for a date range.

The distinction between `RoomType`, `ReservationRoom`, and
`RoomAssignment` is important: a guest can book a quantity of rooms of a
type before staff assigns the actual physical rooms.

### 3.5 Database and migrations

Flyway SQL migration files define schema changes in version order.
Existing migration names include:

-   `V1__create_property_and_room_schema.sql`
-   `V3__create_guest_and_reservation_schema.sql`
-   `V6__prevent_overlapping_room_assignments.sql`

The reservation schema includes a uniqueness rule for
`(booking_source_id, external_booking_id)` when an external booking ID
is present. A database exclusion constraint prevents overlapping date
ranges for the same physical room when assignments are in the `ASSIGNED`
state.

Always add future schema changes as new migration files. Do not edit a
migration that has already been applied to a shared or deployed
database; create a new migration instead.

## 4. Current Implementation Status

This section records the known status, not a guarantee that every
endpoint is production-ready.

### 4.1 Implemented or present in the codebase

-   [x] Spring Boot backend and Maven build.
-   [x] PostgreSQL integration and Flyway migrations.
-   [x] Property model and property APIs.
-   [x] Room type model and APIs.
-   [x] Physical room model and APIs.
-   [x] Bed types and room-bed associations.
-   [x] Amenities and property/room amenity associations.
-   [x] Guest and reservation schema/model foundation.
-   [x] Booking source model.
-   [x] Reservation and reservation-room model/schema.
-   [x] Room assignment schema/model foundation.
-   [x] Reservation create/list/detail and cancellation functionality
    have been exercised manually in development.
-   [x] Initial room-type availability validation on reservation
    creation.
-   [x] Validation that requested room types exist, belong to the
    requested property, are active, and are not duplicated in a single
    request.
-   [x] Availability count excludes inactive rooms and rooms marked
    `OUT_OF_SERVICE`.
-   [x] Reservation room rows are saved with the reservation in a
    transaction.
-   [x] Database protection against overlapping physical-room
    assignments.
-   [x] Manual API tests demonstrated that a request exceeding the
    remaining inventory was rejected.
-   [x] Initial application context-load test exists.

### 4.2 Current reservation availability behavior

The current availability validation checks the requested room type
against the active physical-room inventory for the property, excludes
`OUT_OF_SERVICE` rooms, and subtracts quantities reserved for
overlapping dates in selected reservation states (`PENDING`,
`CONFIRMED`, and `CHECKED_IN`).

The initial overbooking scenario was manually exercised for 10--12
November 2026: reservations requesting a total of three Deluxe rooms
were accepted when three active rooms were available, and a further
request was rejected. The test reservations were subsequently cancelled.
This was a manual API verification, not yet a complete automated
regression suite.

### 4.3 Important limitations of current status

-   Automated reservation availability tests are not yet established as
    a dedicated test suite.
-   Sequential availability validation alone does not eliminate races
    between simultaneous booking requests. Concurrency-safe inventory
    control is still required.
-   The physical room assignment table and database constraint exist,
    but the full staff-facing assignment workflow must be verified and
    completed.
-   Guest identity-document handling, retention, and access control
    require implementation/verification.
-   Check-in and check-out lifecycle behavior needs completion and
    testing.
-   The full live OTA/channel-manager integration is not confirmed as
    implemented.
-   Production-grade authentication and role-based authorization are not
    yet confirmed; security configuration must be reviewed before real
    guest data is stored.
-   Payments, refunds, receipts, and a complete billing workflow need a
    deliberate V1 scope decision.
-   API documentation, operational monitoring, backup/restore, and
    deployment hardening remain release-readiness tasks.

## 5. Code Flow for Key Workflows

### 5.1 Create a reservation

1.  Client submits a reservation request to the REST controller.
2.  Controller validates the request DTO and calls the reservation
    service.
3.  Service validates dates and requested room types.
4.  Service confirms each room type exists, is active, and belongs to
    the selected property.
5.  Service checks the available inventory for the requested dates and
    reservation states.
6.  Service maps the reservation request to a reservation entity and
    persists it.
7.  Service maps each requested room line to a `ReservationRoom` entity
    and persists those rows in the same transaction.
8.  Service returns a response DTO to the controller.
9.  Controller sends the appropriate HTTP response.

**Important:** this current flow requires additional concurrency
protection before it can safely handle simultaneous requests in
production.

### 5.2 Cancel a reservation

1.  Client requests cancellation through the cancellation endpoint.
2.  Service checks the reservation and its booking source.
3.  The cancellation rule must respect the source of truth: a locally
    managed direct booking can be cancelled according to the
    direct-booking workflow; an OTA booking must not be treated as
    cancelled until the OTA/channel manager confirms the change.
4.  Cancellation changes status and/or cancellation quantities while
    preserving the record and audit history.
5.  Availability calculations stop counting inventory that is no longer
    reserved, according to the agreed cancellation model.
6.  Refunds, if any, are separate financial records and are not implied
    by cancellation.

Verify all of these behaviors with automated tests before release.

### 5.3 Assign a physical room

Target workflow:

1.  Staff opens a confirmed reservation.
2.  Backend identifies the reservation-room lines, room type, property,
    and stay dates.
3.  Backend checks that the selected room is active, belongs to the
    property, matches the required room type, and is not out of service.
4.  Backend creates a room assignment for the stay period.
5.  Database exclusion constraint rejects overlapping assignments for
    the same physical room.
6.  Backend returns the assignment and updated reservation details.

The target workflow must be implemented and tested; the existence of the
table/constraint alone does not mean the whole workflow is complete.

### 5.4 Check-in and check-out

Target check-in flow:

1.  Staff locates the reservation and confirms the guest's details.
2.  Backend verifies the reservation is in a valid state and the
    assigned room is appropriate.
3.  Guest and identity-verification details are recorded with
    appropriate access restrictions.
4.  Backend records check-in time and staff identity, and updates
    reservation/occupancy states consistently.

Target check-out flow:

1.  The system identifies stays due for checkout using the latest
    confirmed checkout date and the property's timezone.
2.  A reservation must remain `CHECKED_IN` until staff confirms the
    guest has actually departed.
3.  The system must not release the room while occupancy is uncertain.
4.  Staff confirms departure; backend records who confirmed it and when,
    updates assignment and reservation states, and releases the room.
5.  A confirmed extension updates the checkout deadline before the
    original deadline is treated as final.

Automated reminders at noon and every 15 minutes, including escalation
from assigned staff to owner/manager, are deferred and should be
implemented only after the basic lifecycle is reliable.

### 5.5 External booking synchronization

Target integration flow:

1.  Integration receives or fetches a booking event from an OTA/channel
    manager.
2.  Backend validates the source and external booking identifier.
3.  Backend uses source + external booking ID for idempotency.
4.  Backend creates or updates a local reservation only after validating
    the external event and local inventory policy.
5.  Modifications and cancellations are applied only when confirmed by
    the external source.
6.  Failed sync attempts are logged and retried safely.
7.  Reconciliation identifies bookings that differ between the external
    source and PMS.

Do not assume that adding a `BookingSource` record automatically
connects to an OTA. The actual integration depends on the provider's API
or channel-manager contract.

## 6. Remaining Implementation Plan

Recommended order:

### Phase 1 --- Booking correctness and tests

-   [ ] Add focused automated service tests for availability and
    reservation creation.
-   [ ] Add integration tests for reservation APIs and persistence.
-   [ ] Cover overlapping dates, adjacent non-overlapping dates,
    cancellation, inactive room types, inactive rooms, out-of-service
    rooms, invalid properties, and insufficient inventory.
-   [ ] Test duplicate external booking IDs and repeated events.
-   [ ] Verify reservation and reservation-room persistence roll back
    together on failure.
-   [ ] Implement concurrency-safe inventory control and test parallel
    booking attempts.

### Phase 2 --- Room assignment and operations

-   [ ] Implement assignment and reassignment endpoints/services.
-   [ ] Validate property, room type, room status, reservation dates,
    and assignment state.
-   [ ] Handle multi-room reservations.
-   [ ] Test database overlap protection and friendly conflict
    responses.
-   [ ] Define room status transitions and housekeeping/maintenance
    implications.

### Phase 3 --- Guest and identity management

-   [ ] Finish guest create/read/update workflows and validation.
-   [ ] Support multiple guests linked to a reservation.
-   [ ] Define primary guest and accompanying guest rules.
-   [ ] Record identity document type, verification status, and required
    metadata.
-   [ ] Protect identity numbers and document images; restrict access
    and avoid logging them.
-   [ ] Define data retention and deletion policies consistent with
    applicable requirements.

### Phase 4 --- Check-in, check-out, and stay changes

-   [ ] Implement check-in with valid-state checks and audit fields.
-   [ ] Implement staff-confirmed check-out and room release.
-   [ ] Support extensions, late checkout, and room changes.
-   [ ] Define allowed reservation state transitions.
-   [ ] Add idempotency protection for repeated operational requests.
-   [ ] Add audit events for important changes.
-   [ ] Later: implement scheduled checkout reminders and escalation.

### Phase 5 --- Authentication and authorization

-   [ ] Select the authentication model (session-based or token-based)
    and configure it correctly.
-   [ ] Define roles and permissions.
-   [ ] Enforce property-level access boundaries.
-   [ ] Secure guest and identity-document endpoints.
-   [ ] Review CSRF, CORS, password handling, token/session expiry,
    secrets, and error responses.
-   [ ] Add tests proving unauthorized users cannot access protected
    endpoints.

### Phase 6 --- Booking channels and financial records

-   [ ] Choose the actual OTA/channel manager and confirm API
    capabilities.
-   [ ] Implement booking import, update, cancellation, retry, and
    reconciliation.
-   [ ] Store source event IDs and processing status where useful.
-   [ ] Decide the V1 scope for rates, taxes, discounts, deposits,
    payments, outstanding balances, refunds, invoices, and receipts.
-   [ ] Keep payment transactions and refunds separate from reservation
    status.
-   [ ] Test duplicate and out-of-order external events.

### Phase 7 --- Release readiness

-   [ ] Publish API documentation (for example, OpenAPI/Swagger).
-   [ ] Standardize API error responses and validation messages.
-   [ ] Add structured logging without exposing secrets or sensitive
    guest data.
-   [ ] Add health checks and operational metrics.
-   [ ] Define database backup and restore procedures.
-   [ ] Create development/test/production configuration profiles.
-   [ ] Document deployment and rollback procedures.
-   [ ] Run full automated tests and perform a security review.
-   [ ] Test migration from a clean database and from the previous
    release.
-   [ ] Verify monitoring and recovery steps before real use.

## 7. Project Documentation Commonly Needed

A professional repository usually contains the following documents. They
can be added incrementally; not every document needs to be exhaustive at
the beginning.

  --------------------------------------------------------------------------------
  Document                Suggested file                   Purpose
  ----------------------- -------------------------------- -----------------------
  Project overview and    `README.md`                      Explains what the
  status                                                   project does, current
                                                           status, and quick start

  Architecture and design `docs/architecture.md`           Layers, modules, domain
                                                           relationships,
                                                           decisions, and diagrams

  Requirements            `docs/requirements.md`           Functional and
                                                           non-functional
                                                           requirements and V1
                                                           boundaries

  API reference           `docs/api.md` or generated       Endpoints,
                          OpenAPI docs                     request/response
                                                           examples, status codes,
                                                           authentication

  Database design         `docs/database-design.md`        Tables, relationships,
                                                           constraints, indexes,
                                                           migration policy

  Setup guide             `docs/development-setup.md`      Prerequisites, local
                                                           configuration,
                                                           database, build and run
                                                           commands

  Testing guide           `docs/testing.md`                Test types, test data,
                                                           commands, and coverage
                                                           expectations

  Deployment guide        `docs/deployment.md`             Environment
                                                           configuration,
                                                           deployment, migration,
                                                           health checks, rollback

  Security and privacy    `docs/security-and-privacy.md`   Roles, permissions,
                                                           secrets, guest identity
                                                           data, retention,
                                                           incident handling

  Integration guide       `docs/integrations.md`           OTA/channel-manager
                                                           contracts, webhooks,
                                                           retries, idempotency,
                                                           reconciliation

  User/staff guide        `docs/user-guide.md`             How staff carry out
                                                           reservations, room
                                                           assignment, check-in
                                                           and checkout

  Release notes           `CHANGELOG.md`                   User-visible changes
                                                           and fixes by release

  Contribution guide      `CONTRIBUTING.md`                Branching, code style,
                                                           pull requests, and
                                                           review expectations

  License                 `LICENSE`                        Terms under which the
                                                           project may be used or
                                                           distributed

  Environment template    `.env.example`                   Names and example
                                                           placeholders for
                                                           configuration
                                                           variables, never real
                                                           secrets

  Work plan               `docs/roadmap.md`                Remaining milestones,
                                                           dependencies, and
                                                           deferred features
  --------------------------------------------------------------------------------

### Minimum recommended documentation to add first

1.  `README.md` --- overview, prerequisites, setup commands, build/test
    commands, current status.
2.  `docs/architecture.md` --- architecture, code flow, domain
    relationships, and design decisions.
3.  `docs/requirements.md` --- V1 requirements and explicit out-of-scope
    items.
4.  `docs/database-design.md` --- tables, relationships, and migration
    rules.
5.  `docs/testing.md` --- test strategy and commands.
6.  `docs/roadmap.md` --- implementation checklist.
7.  `docs/security-and-privacy.md` --- planned controls for
    authentication and sensitive guest data.

This document can serve as the starting project overview. As the code
changes, keep the documentation synchronized with what is actually
implemented.

## 8. Local Development Commands

Run Maven commands from the backend directory.

### Run tests

``` powershell
cd C:\GitHub\homestay-pms\backend
mvn test "-Duser.timezone=Asia/Kolkata"
```

### Clean build and tests

``` powershell
cd C:\GitHub\homestay-pms\backend
mvn clean test "-Duser.timezone=Asia/Kolkata"
```

### Run the backend

``` powershell
cd C:\GitHub\homestay-pms\backend
mvn spring-boot:run "-Dspring-boot.run.jvmArguments=-Duser.timezone=Asia/Kolkata"
```

### Check Git status

``` powershell
cd C:\GitHub\homestay-pms
git status
git log -1 --oneline
```

Never put actual passwords, tokens, production connection strings, or
real guest information into source control or sample commands.

## 9. Definition of V1 Backend Complete

The backend is ready for a controlled V1 launch when:

-   [ ] A reservation cannot overbook room-type inventory, including
    under concurrent requests.
-   [ ] External bookings are idempotent and reconciled with their
    source.
-   [ ] Room assignments cannot overlap and only valid rooms can be
    assigned.
-   [ ] Guest records and identity information are securely handled.
-   [ ] Check-in, extension, cancellation, and staff-confirmed checkout
    behave consistently.
-   [ ] Payments and cancellation/refund status are not conflated.
-   [ ] Authentication, authorization, and property-level access
    restrictions are tested.
-   [ ] Core workflows have automated tests.
-   [ ] Migrations, backups, deployment, monitoring, and recovery are
    documented and tested.
-   [ ] API and staff documentation matches the actual implementation.

**Current next priority:** strengthen reservation availability with
automated regression tests and concurrency-safe inventory handling, then
complete the room assignment and guest check-in/check-out workflows.
