// Shared UI state. Modules mutate it, then call refreshControls() from ui.js.

export const state = {
    hasAccount: false,
    busy: false,
    autopilot: false,
    /** id of the plan shown in the table, null while showing recent activity */
    planId: null,
    /** user overrides for the current plan: { emailId: action } */
    overrides: {},
    /** names of the account's folders, for rule targets */
    folders: [],
};
