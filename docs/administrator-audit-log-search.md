# Administrator Audit Log Search and Pagination

## Access

The audit endpoint remains restricted to administrators with the global `AUDIT_VIEW` permission. Geographical or organisation-scoped finance permissions do not grant access to the central administrator audit log.

## Endpoint

`GET /api/admin/audit`

The endpoint remains backward compatible: requests without query parameters return the latest 100 events, ordered newest first. The maximum page size is 500.

Supported query parameters:

| Parameter | Purpose |
| --- | --- |
| `from` | Include events on or after this date (`YYYY-MM-DD`) |
| `to` | Include events through this date, inclusive |
| `action` | Case-insensitive partial match against the action code |
| `actor` | Case-insensitive partial match against the administrator username |
| `targetType` | Case-insensitive partial match against the target type |
| `targetId` | Exact target UUID |
| `search` | Case-insensitive search across action, target type, target ID, event details and actor username |
| `limit` | Page size from 1 to 500; default 100 |
| `offset` | Number of matching events to skip; default 0, maximum 100000 |

Example:

```http
GET /api/admin/audit?from=2026-01-01&to=2026-01-31&action=CONTRIBUTION&limit=50&offset=0
```

The date upper bound is inclusive. Results use a deterministic descending order by event timestamp and event UUID so that pagination does not reorder events sharing the same timestamp.

All filter values are bound as SQL parameters. The endpoint reads the existing append-only audit events; it does not alter, delete, or rewrite audit history. Invalid date ranges, oversized filters, invalid page sizes and invalid offsets are rejected.

## Operational note

Offset pagination is intended for administrator investigation and moderate audit volumes. A future high-volume audit archive may require cursor pagination and time-partitioned storage.
