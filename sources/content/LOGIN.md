# Login panel data and dispatch

Login panels support up to 128 rows and 128 submitted bindings, covering the
synthetic 12/44/58/61-row compatibility fixtures. The existing 65,536-character
JSON, 64-level nesting, 4,096-character input and 16,384-character aggregate
submission bounds remain. Exceeding a size/depth bound reports `Limit` at
`loginUi` or `loginUi.values`.

Each control has an opaque ID derived from its type, input binding (for inputs)
and action, plus an occurrence suffix for otherwise identical controls. Button
names and evaluated labels are display text and need not be unique. Reordering
distinct actions or changing their labels preserves their IDs. Each form load
also gets a fresh form ID; the UI carries that ID through submission. Actions
from the UI are selected only by control ID, and an outdated form ID is rejected before
credentials are saved or actions run. Refresh invalidates the previous form.
Trusted programmatic calls without a form snapshot retain the unique-name action
API; ambiguous names fail, and the UI never uses this compatibility path.

The pinned E reference (`8b87c5aba4df91c39a3a0939a68a1180b9f2ee1c`,
`SourceLoginDialog.handleButtonClick`/`getLoginData`) binds input data by name
while invoking the clicked row's own action. Here repeated input bindings share
one visible value when their type, default and choices agree. Conflicting
definitions report `InvalidRule` at `loginUi.name`, instead of the reference's
last-row overwrite. A button may have the same name as an input without becoming
a submitted value. This protocol is independent of the host's account/panel
lifetime and never establishes authentication by displaying or saving a form.
