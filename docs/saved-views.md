# Saved Views

**Status:** Active
**Service:** transaction-service

## Overview

Saved views are user-owned named static sets of transactions. They are exposed
through `/v1/views/**`; metadata is stored in `saved_view`, and membership is
stored in `saved_view_transaction`.

There are no saved predicates, open-ended dates, pins, exclusions, membership
order, or full-membership replacement operation. A client creates a view from
an exact transaction ID set and later changes it with atomic additions and
removals.

## Ownership And Validation

Every view belongs to the authenticated user. View lookup deliberately returns
not found for a foreign owner, and this API has no cross-user `:any` variant.
View names are unique within an owner using a case-insensitive comparison while
retaining the submitted casing for display. Create and rename requests trim
leading and trailing whitespace from names before validation and persistence.
Different users can use the same name.

Create and membership-add operations canonicalize duplicate IDs and lock the
requested transaction rows in ascending ID order. Every requested addition
must resolve to an active transaction owned by the caller. If any addition is
missing, soft-deleted, or foreign-owned, the complete operation rolls back with
`422 APPLICATION_ERROR` and code `SAVED_VIEW_MEMBERSHIP_STALE`. The response
does not identify inaccessible IDs.

Each submitted membership array is limited to 10,000 raw entries before
sorting and duplicate canonicalization, so duplicate IDs count toward the
limit. Create applies the limit to `transactionIds`. Membership deltas apply
the limit independently to `addTransactionIds` and `removeTransactionIds`.

An empty create membership is valid. Membership is an unordered set; the read
endpoint returns IDs in ascending order only to make responses deterministic.

## HTTP Contract

Create a view:

```http
POST /v1/views
Content-Type: application/json

{
  "name": "December review",
  "transactionIds": [123, 456]
}
```

List and get responses contain metadata and the active `transactionCount`:

```json
{
  "id": "6715a545-bab3-426d-9927-26dcea680871",
  "name": "December review",
  "transactionCount": 2,
  "createdAt": "2026-01-15T12:00:00Z",
  "updatedAt": "2026-01-15T12:00:00Z"
}
```

Rename a view with `PATCH /v1/views/{id}` and body `{ "name": "New name" }`.
Create and rename operations trim surrounding name whitespace and cannot reuse
a case-insensitive name already owned by the same user. A conflict returns
HTTP 422 with error type `APPLICATION_ERROR` and code
`SAVED_VIEW_NAME_ALREADY_EXISTS`. Clients must use the status, type, and code
rather than the human-readable message for programmatic handling. Delete a
view with `DELETE /v1/views/{id}`.

Read complete membership:

```http
GET /v1/views/{id}/transactions
```

```json
{
  "transactionIds": [123, 456]
}
```

Apply an atomic delta:

```http
PATCH /v1/views/{id}/transactions
Content-Type: application/json

{
  "addTransactionIds": [789],
  "removeTransactionIds": [123]
}
```

Both arrays are required, every ID must be positive, the add and remove sets
must be disjoint, and at least one array must be nonempty. Unknown removals are
idempotent. Successful deltas return `204 No Content`; clients refresh
membership and metadata caches. An explicit delta updates the view's
`updated_at` timestamp only when it changes the persisted membership set.
Repeating additions that already exist or removals that no longer exist leaves
the timestamp unchanged.

Required permissions are `views:read`, `views:write`, and `views:delete` for
the corresponding operations.

## Browser Snapshot Integration

The membership endpoint returns transaction IDs because the browser already
holds the complete active transaction snapshot from `GET /v1/transactions`.
The browser intersects the membership set with that snapshot, then performs
view filtering, sorting, aggregates, and presentation pagination locally. The
intersection tolerates cache timing; it is not the mechanism that enforces the
active-membership database invariant.

Creating a view from a filtered transaction table submits the exact visible ID
set. The service does not receive or reevaluate the browser's transient filter
definition, display currency, sort, or page state.

## Membership Persistence

Pure JPA remains the default persistence mechanism. Saved-view membership
insertion is the single JDBC exception because standard JPA has no portable
batch-insert operation, while persisting each association individually would
add ORM and managed-entity overhead to the exercised 10,000-membership path.
The exception stays behind the repository interface.

The repository sorts and deduplicates requested transaction IDs, uses JPQL to
find memberships that already exist, and sends only missing associations to a
JDBC batch. The batch SQL must remain a plain, provider-neutral, parameterized
`INSERT`; database-specific conflict clauses and JDBC driver update counts are
not part of the inserted-count contract.

Pre-query filtering is safe because every mutation of an existing view holds
the same pessimistic saved-view lifecycle lock through the surrounding service
transaction. Creation uses a newly generated view ID with no competing writer.
The database primary key remains the final integrity constraint, and any batch
failure propagates so the complete transaction rolls back.

## Soft Deletion And Concurrency

Rename, membership-delta, and saved-view deletion operations share a
pessimistic lifecycle lock on the owner-scoped saved-view row. When a
membership delta adds transactions, it acquires locks in this order:

1. Saved-view row.
2. Added transaction rows by ascending transaction ID.

This order serializes every saved-view mutation with deletion. Creation has no
existing saved-view row to lock and continues to lock only its requested
transaction rows in ascending ID order. Read-only operations use unlocked
owner-scoped lookups.

The association-table invariant is that every membership references an active
transaction. Both membership additions and transaction soft deletion acquire
pessimistic locks on sorted unique transaction IDs. Single and bulk soft
deletion mark the transaction rows deleted and remove all referencing
membership rows in the same database transaction.

Transaction-driven cleanup does not update saved-view timestamps. Membership
reads and counts use `saved_view_transaction` directly; they do not join
`transaction` to filter deleted rows. Deleting a view cascades its memberships,
while the transaction foreign key uses normal restrictive behavior.

## Destructive Cutover

`V22__replace_saved_views_with_static_membership.sql` deletes every existing
saved view before dropping the dynamic criteria and override columns. No old
view is migrated or interpreted. Historical Flyway migrations remain immutable
so clean database construction and checksum validation continue to work.

See [Database Schema](database-schema.md#saved_view) for table and index
details.
