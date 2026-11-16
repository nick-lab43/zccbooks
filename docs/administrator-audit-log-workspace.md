# Administrator audit log workspace

The Users & Access workspace provides a searchable, paginated view of administrator audit history.

## Filters

- From and to dates, interpreted by the API as UTC calendar dates with an inclusive end date.
- Action code.
- Actor username.
- Target type and target UUID.
- Free-text search across supported audit event fields.

Filters are applied together. Clearing filters restores the unfiltered latest-first view.

## Pagination

The workspace requests 50 events by default and supports page sizes of 25, 50, 100, 250 and 500. Previous and Next use the API's bounded offset pagination. The Next control is enabled when the current page is full; the API response does not expose a total count, so the interface does not claim an exact total.

## Access

The API requires the global `AUDIT_VIEW` permission. The frontend only displays the audit workspace inside the existing administrator access area. Backend authorization remains authoritative.

## Limitations

Offset pagination can shift when new audit events are recorded between page requests. The API orders by event timestamp and ID to make ordering deterministic for a given result set. A future cursor-based API could provide more stable traversal for rapidly changing logs.
