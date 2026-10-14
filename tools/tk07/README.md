# TK07 Review

This managed module uses the Toolkit's plaintext shared account, shared ChromeDriver, selected External/Internal portal, and `settings.csv` preferences:

- `Search` (required, at most two non-empty terms separated by `&&`)
- `Description_Approve` (optional)
- `Description_Reject` (optional)

It reviews complete TK07 Request Type category matches whose Project Name contains one canonical phrase: `single grid`, `consolida`, `blackcell grey`, `asg card expansion`, `geo b2b`, `support olt small pocket`, or `ip clock`. Matching ignores letter case.

The module opens the exact request's Activity/Milestone, TK07 activity, and attachment view. It approves when a readable RFS PDF contains the normalized site identifier, or rejects when the loaded attachment list confirms no RFS file or a readable RFS PDF lacks that identifier. The original site rules remove a leading `D`; otherwise they remove a trailing `Z`, `A`, or `T`.

The attachment check requires a completed response for the selected activity in the exact current request, an actual post-response list transition, and the complete response matching the loaded filenames. An unchanged cached list, an incomplete rendered list, or an unknown loading state does not submit a decision. Multiple RFS attachments, non-PDF RFS files, unreadable or image-only PDFs, and invalid site identifiers also do not submit a decision. An unconfirmed PDF download stops the run, preventing a late file from being mistaken for the next request's evidence. Each run downloads into a new `downloads/run-*` folder. Existing PDFs and the original standalone application are never deleted or modified. PDF contents are not printed to task logs.

Approve/reject counts require a confirmed response and a fresh pending-list search showing that the exact request left pending. Unconfirmed outcomes stop without automatic resubmission. The legacy per-module driver updater and global ChromeDriver process termination are omitted.
