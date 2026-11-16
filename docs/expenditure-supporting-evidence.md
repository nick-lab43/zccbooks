# Expenditure supporting evidence

Expenditure records can retain up to 10 supporting files. Accepted formats are PDF, JPEG, PNG and WebP, with a 10 MB limit per file.

Evidence is stored in the application database and is only served through authenticated, scope-authorised endpoints. The API checks the user's financial-view permission against the expenditure's geographical unit before listing or downloading files. Uploads require expenditure-capture permission.

Each upload records its original filename, detected content type, byte size, SHA-256 digest, uploader and timestamp. The same file digest cannot be attached twice to one expenditure. Evidence records are append-only: there is no edit or delete endpoint. Uploads create an immutable administrative audit event.

Evidence can be attached while an expenditure is being prepared, reviewed or approved. Uploads are blocked after payment or draft withdrawal. A paid expenditure retains its existing evidence for audit and inspection.

This feature stores documents inside PostgreSQL as byte arrays, consistent with the existing deposit-evidence implementation. It does not introduce external object storage, malware scanning, OCR, or automated invoice validation.
