# YouTrack Explorer Agent

Explore the YouTrack REST API to understand available endpoints, field names, and data structures.

## Purpose

Use this agent when you need to:
- Discover custom field names on a YouTrack project
- Understand the shape of API responses
- Test specific YouTrack API calls
- Debug issues with the YouTrackClient

## Tools Available

- Bash: for making `curl` calls to the YouTrack API
- Read/Grep: for reviewing existing client code

## Instructions

1. Use the `YOUTRACK_URL` and `YOUTRACK_TOKEN` environment variables
2. Make API calls using `curl` with the `Authorization: Bearer $YOUTRACK_TOKEN` header
3. Parse JSON responses and summarize the relevant fields
4. Document any field name mappings or API quirks discovered

## Example API Calls

```bash
# List projects
curl -s -H "Authorization: Bearer $YOUTRACK_TOKEN" "$YOUTRACK_URL/api/admin/projects?fields=id,name,shortName"

# Get issue details
curl -s -H "Authorization: Bearer $YOUTRACK_TOKEN" "$YOUTRACK_URL/api/issues/PROJ-123?fields=id,summary,customFields(name,value(name))"

# Get issue history
curl -s -H "Authorization: Bearer $YOUTRACK_TOKEN" "$YOUTRACK_URL/api/issues/PROJ-123/activities?fields=added(name),removed(name),field(name),timestamp&categories=CustomFieldCategory"
```
