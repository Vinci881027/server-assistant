# Auto Review Rules

## Post-Edit Review

- After any code modification, automatically run a review pass before sending the final response.
- Treat the review pass as equivalent to the prompt: `review 剛剛的修改`.
- Prioritize findings first: bugs, regressions, security risks, behavior changes, and missing tests.
- Include concrete file references and line numbers when reporting findings.
- If no findings are detected, state that explicitly.
- Always state whether tests were run; if not run, explicitly say so.

## Change Scope Detection

- If git metadata is available, review `git diff` and staged changes.
- If git metadata is unavailable, review all files modified in the current task.
