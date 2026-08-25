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

Create and membership-add operations canonicalize duplicate IDs and lock the
requested transaction rows in ascending ID order. Every requested addition
must resolve to an active transaction owned by the caller. If any addition is
missing, soft-deleted, or foreign-owned, the complete operation rolls back with
`422 APPLICATION_ERROR` and code `SAVED_VIEW_MEMBERSHIP_STALE`. The response
does not identify inaccessible IDs.

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
Delete it with `DELETE /v1/views/{id}`.

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

## Soft Deletion And Concurrency

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
