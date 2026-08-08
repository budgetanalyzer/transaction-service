# Saved Views

**Status:** Active
**Service:** transaction-service

## Overview

Saved views are user-owned transaction filters with optional pinned and excluded
transaction overrides. They are exposed through `/v1/views/**` and persist in
the `saved_view` table.

All saved-view membership is owner-scoped. A view cannot supply its own owner
ID; the service injects the authenticated user.

## Criteria

Saved views persist the user-facing transaction filters below in the `criteria`
object. All fields are optional.

- `dateFrom`, `dateTo` - Inclusive transaction date range.
- `searchText` - Text matched against transaction descriptions.
- `bankNames`, `accountIds`, `currencyIsoCodes` - Multi-value fields. Any
  supplied value can match. Blank entries are ignored.
- `minAmount`, `maxAmount` - Inclusive transaction amount range.
- `type` - Transaction type, `DEBIT` or `CREDIT`.

`startDate` and `endDate` are not part of the saved-view API contract. Migration
`V16__delete_legacy_saved_views.sql` deletes saved views persisted with that old
criteria JSON shape.

Example request body:

```json
{
  "name": "December Debits",
  "criteria": {
    "dateFrom": "2024-12-01",
    "dateTo": "2024-12-31",
    "bankNames": ["Capital One"],
    "accountIds": ["checking-12345"],
    "currencyIsoCodes": ["USD"],
    "minAmount": 10.00,
    "maxAmount": 500.00,
    "type": "DEBIT",
    "searchText": "coffee"
  },
  "openEnded": false
}
```

## Open-Ended Views

When `openEnded=true` and `criteria.dateTo` is omitted, the service resolves the
upper date bound to the current date at membership lookup time. When
`criteria.dateTo` is present, that stored value is used.

## Pins And Exclusions

Pins and exclusions are stored as transaction ID sets on the same `saved_view`
row:

- Pinning a transaction adds it to `pinned_ids` and removes it from
  `excluded_ids`.
- Excluding a transaction adds it to `excluded_ids` and removes it from
  `pinned_ids`.
- Deleting a saved view removes its pinned and excluded IDs with the same row.

Pinned and excluded IDs are filtered to active transactions owned by the view
owner before membership is returned. Membership resolution fetches the union of
stored pinned and excluded IDs with one owner-scoped active-ID lookup, then
partitions the IDs into the response groups in memory.

Bulk endpoints are also owner-scoped:

- `POST /v1/views/{id}/pin` with body `{ "ids": [...] }`
- `POST /v1/views/{id}/exclude` with body `{ "ids": [...] }`

Both operations process every requested ID and return:

- `updatedCount` for unique successfully processed IDs. Duplicate valid IDs are
  applied once and counted once.
- `notFoundIds` for IDs that are missing, soft-deleted, or owned by another
  user. These IDs keep request order, including duplicate invalid IDs.

Both endpoints return `200 OK` for full or partial success, return `400 Bad
Request` for null or empty ID lists, and return `404 Not Found` only when the
saved view itself does not exist for the authenticated user.

## Membership Response

`GET /v1/views/{id}/transactions` returns transaction IDs grouped by membership
type:

- `matched` - Active transaction IDs matching the view criteria, excluding
  active excluded IDs.
- `pinned` - Active pinned transaction IDs not already included in `matched`.
- `excluded` - Active excluded transaction IDs.

The saved-view transaction count is derived from the same effective membership
set:

```text
(matching IDs - active excluded IDs) + active pinned IDs
```

`SavedViewResponse` derives all three count fields from that same resolution:

- `transactionCount` is `matched + pinned`, after active exclusions are
  applied.
- `pinnedCount` is the number of active, owner-owned stored pin IDs. It includes
  an active pin that also matches the criteria, even though that ID is presented
  only in `matched` membership.
- `excludedCount` is the number of active, owner-owned stored exclusion IDs,
  including exclusions that do not currently match the criteria.

Soft-deleted, missing, and foreign-owner transactions are ignored in all three
membership groups and counts. Deleting a transaction does not remove its ID
from the persisted override arrays. If an equivalent transaction is later
created with a new ID, the historical exclusion does not transfer to it; the
replacement can enter an open-ended view when it matches the criteria.

## Storage

The `saved_view` table stores:

- `criteria` as JSON text using the current `dateFrom` and `dateTo` field names.
- `open_ended` as a boolean.
- `pinned_ids` as JSON text.
- `excluded_ids` as JSON text.

The ID arrays retain historical IDs after a transaction is soft-deleted. Their
stored array sizes therefore are not the `pinnedCount` or `excludedCount`
returned by the API; response counts include only active, owner-owned IDs.

`criteria`, `pinned_ids`, and `excluded_ids` are required persistence values.
An empty criteria object (`{}`) and empty ID arrays (`[]`) are valid explicit
states. Null, blank, or JSON-null stored values are treated as persistence
corruption and fail instead of being converted to broad empty filters.

See [Database Schema](database-schema.md#saved_view) for table and index
details.
