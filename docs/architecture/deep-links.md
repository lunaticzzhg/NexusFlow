# Deep-link architecture

Orbit inbound links use three layers. Each layer has a single responsibility so platform
callbacks do not become product routing code.

1. **Platform ingress** receives a raw URI from Android or iOS and delivers it to
   `AppLinkSource`. It must not parse paths, navigate, or log the raw value.
2. **Common decoding** validates a confirmed link contract and converts it to a typed
   `DeepLinkIntent`. A decoder does not check object visibility, user state, or decide a page.
3. **Feature coordination** applies the business rules and performs the final navigation or
   safe fallback.

W1 implements ingress and the decoding runtime only. The production decoder list is
intentionally empty until a product link contract is confirmed. Runtime events have no UI
consumer in this stage.

## Adding a business link

Add a complete thin slice in the owning feature:

```text
feature/<feature>/application/
  XxxDeepLinkIntent
  XxxDeepLinkDecoder

feature/<feature>/presentation/
  XxxDeepLinkCoordinator
```

The coordinator owns login and onboarding gates, household and object permissions, object
state, one-time consumption, page selection, and a safe fallback when the target is unavailable.
The feature registers its decoder in the app composition root after its contract is reviewed.

Do not parse business paths or navigate from `MainActivity`, `AppDelegate`, `App.kt`, platform
bridges, or `DeepLinkRuntime`. Do not log raw URIs, hosts, paths, query values, tokens,
invitation codes, or object identifiers. Do not add a shared pending store or coordinator framework
until two implemented feature coordinators demonstrate the same lifecycle and consumption rules.
