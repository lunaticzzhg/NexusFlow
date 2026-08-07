# Architecture

`app` is an untrusted presentation and offline-cache client. `backend` owns task state, approval decisions, permissions and all side effects. `ai` may propose a structured plan but cannot write to a calendar, notification provider or database directly.

For the MVP, the backend imports the AI package locally. This is a code boundary, not a distributed-system boundary. The AI package can become an independent worker/service once model latency, throughput or isolation requires it.
