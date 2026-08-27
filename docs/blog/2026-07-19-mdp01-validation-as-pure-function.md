---
layout: post
title: "Validation as a Pure Function"
date: 2026-07-19
type: phase-update
entry_type: note
subtype: diary
projects: [casehub-pages]
tags: [pages-form, validation, json-schema]
---

## The two missing layers

`pages-form` could render fields from a JSON Schema and collect edits, but had no
vocabulary for telling the user what a field means or whether they filled it wrong.
Labels came from the property key name with CSS `text-transform: capitalize` —
`firstName` became "Firstname", and there was no way to say "First Name" or
"Your legal first name" without building it outside the component.

Validation was equally bare: `submit()` checked `schema.required` and returned
null on failure. No `pattern`, no `minimum`/`maximum`, no `minLength`. The `.error`
CSS class existed in the stylesheet and was never used.

## What changed

`FieldSchema` now carries `title`, `description`, and `placeholder` — the first
two are standard JSON Schema, the third is a UI hint. `title` overrides the
key-derived label. `description` renders as help text below the field.
`placeholder` passes through to inputs and textareas.

For validation, I kept the engine as a separate pure function: `validateField(key,
schema, value, required)` returns a string error message or null. No class, no
state, no DOM awareness. The form component calls it during `submit()` for every
field, collects errors into a `Record<string, string>`, and passes each error
through to its field renderer for inline display.

The split matters because validation logic is testable without a DOM — twelve of
the twenty-one new validation tests exercise `validateField` directly as a function
call, no element creation needed. The form's responsibility is wiring: when to
validate (submit, optionally blur), where to display errors, when to clear them.

## The IntelliJ gotcha

Claude tried `ide_edit_member` to replace the `FieldSchema` interface declaration
and got back "Member editing not supported for TypeScript. Supported: Java, Kotlin."
The tool descriptions don't mention a language restriction, so this was unexpected.
`ide_replace_text_in_file` — which operates at the text level, not PSI — handled
everything from there. Worth knowing if you're using IntelliJ MCP on a TypeScript
codebase.
