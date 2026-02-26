# /pr-push — Commit, push, and create a PR

Commit staged changes, push the branch, and open a pull request.

## Steps

1. Run `git status` and `git diff --staged` to review what will be committed
2. Run `git log --oneline -5` to check recent commit style
3. Compose a commit message following this format:
   - **Title**: `<Type>: <short imperative summary>` (max 72 chars)
   - Types: `Feature`, `Fix`, `Chore`, `Docs`, `Refactor`
   - Body (optional): bullet points explaining *why*, not *what*
4. Commit using a heredoc:
   ```bash
   git commit -m "$(cat <<'EOF'
   Type: short summary

   - Detail if needed
   EOF
   )"
   ```
5. Push the branch: `git push -u origin HEAD`
6. Create a PR with `gh pr create`:
   - Title matches the commit title (or summarizes multiple commits)
   - Body uses this template:
     ```
     ## Summary
     - Bullet points describing the change

     ## Test plan
     - [ ] Verification steps
     ```
7. Report the PR URL
