# Login form data compatibility (#216)

Login, cookies, saved account data and browser sessions already exist. This change only
widens the **data grammar** accepted by `LoginForm.parse`.

## Reference and supported forms

The local `legado-with-MD3` reference uses `SourceLoginViewModel.buildForm` to parse static
`loginUi` with Gson into `RowUi`. Its explicit `@js:` / `<js>` branch evaluates dynamic
forms separately. We follow that distinction and reuse `RequestOptionsJson`, the existing
bounded Gson data parser shared with request options and discovery catalogues.

Strict JSON and data arrays with bare keys / single-quoted strings now enter the same
form model. For example, `[{name:'account',type:'text'}]` is equivalent to
`[{"name":"account","type":"text"}]`. Parsing the array does not start the interpreter.

The September field inventory counted 34 nonempty declarations: 16 strict arrays,
14 lenient arrays, 2 dynamic scripts and 2 other texts. These are syntax categories, not
login success counts. URL text found in the `loginUi` field is not a form; a browser login
URL belongs in `loginUrl`. This change does not guess a replacement field or promise that
all 34 declarations have valid controls or working account actions.

## Retained boundaries

- Maximum input/normalized data: 65,536 characters; maximum nesting depth: 64.
  Trailing non-data input is rejected. Invalid syntax/non-arrays report `InvalidRule`
  at `loginUi`, without including parser input in the error.
- Maximum 32 rows, unique bounded names, known fields/types, bounded choices/defaults
  and existing submission checks remain in `LoginForm`. Semantic errors retain their
  existing row/field location.
- Standard controls remain text/password/button. Dynamic forms, select/toggle and
  default/chars/viewName retain the existing extension-profile rules and execution budget.
- Button actions remain inert strings while parsing. Layout is host-owned; this does not
  add long-press actions, arbitrary controls, new JS bridges or a settings framework.
- No cookie, account, network-route or settings-entry behavior changes.

## Verification

`LoginFormDataTest` uses synthetic fixtures for equivalent strict/lenient forms, escaped
quotes, nested data, invalid/trailing/non-array input, oversize/deep input, semantic errors,
profile boundaries and static parsing without interpreter execution.

Run `:source-rules:test :source-content:test` and the existing source login service/UI tests.
The existing dynamic-form, default/action, cancellation and account-revocation suites remain
the regression checks. A parse result is not evidence of a successful site login.
