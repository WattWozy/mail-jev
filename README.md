# mail-sorter

Self-hosted inbox classifier. Ports & adapters; jev is the `Classifier` adapter.

## TDD roadmap
1. [x] PolicyEngine (red -> green)
2. [x] ConnectUseCase + ClassifyUseCase with in-memory fakes (preview/dry-run is the default)
3. [ ] Rule pre-filter (List-Unsubscribe, known senders) before calling jev
4. [ ] ImapMailProvider (contract test + GreenMail)
5. [ ] JevClassifier: one Choice question over the category set -> probabilities (contract test vs. stub HTTP server)
6. [ ] Infrastructure: web client (UI) driving Connect + Classify
7. [ ] OAuth2 (Gmail/Outlook), config loader, Docker
