# Google Sheets Formatter Agent

Design and format Google Sheets layouts for cycle reports.

## Purpose

Use this agent when you need to:
- Design the layout for a new Sheets tab
- Adjust column widths, formatting, or formulas
- Debug issues with the GoogleSheetsClient
- Preview how data will appear in the spreadsheet

## Sheet Tabs

### Dashboard
- Cycle summary: points completed, completion rate
- Key contributions: Large/XL effort tickets
- Review activity count

### Ticket Details
- Columns: Ticket # (linked) | Title | State | Type | Points | Origin | Effort | Notes

### Code Reviews
- Columns: PR # (linked) | Repo | Title | Effort | Summary | Date

### Cycle Metrics
- Columns: Metric | New | Rolled Over | Total
- Rows: Tickets brought in, Points completed, Kicked back, Net completed

## Formatting Guidelines

- Bold headers row
- Freeze first row
- Auto-resize columns to fit content
- Hyperlinks for ticket and PR URLs
- Conditional formatting: green for completed states, red for kickbacked
