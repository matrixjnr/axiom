# Contributor instructions

- Keep commits and PR titles understandable without private specifications or prior conversations.
  Describe the concrete behavior or problem; omit specification IDs and co-author trailers.
- PR descriptions explain the problem, resulting behavior, relevant design decisions,
  validation, and material limitations. Scale detail to the change.
- Keep public APIs small, document lifecycle and ownership, and enforce module boundaries.
- Test failure paths and concurrency invariants when behavior depends on them. Use deterministic
  coordination instead of sleeps. Run `./gradlew clean check` and relevant examples.
- Keep core independent of server, transport, and serialization implementations.
- Use feature branches and PRs. Main is protected; do not weaken its protection or rewrite history.
- Track limitations as issues. List every material limitation of a change in the PR description
  and file one issue per limitation (label `limitation`) in the same change; link each from the
  limitations index issue (#13) and from the PR. A PR that fixes a limitation closes its issue
  (`Fixes #N`) and the index item is ticked. Do not leave a limitation only in a PR description.
