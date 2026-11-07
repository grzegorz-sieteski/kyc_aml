# AI SDLC — przepływ etapów i bramek

Diagram opisuje proces faktycznie zastosowany w tym repozytorium. Każdy etap wykonuje osobna
rola (osobny kontekst). Bramka to warunek, który orkiestrator sprawdza, zanim przekaże pracę dalej.

```mermaid
flowchart TD
    E0["Etap 0 · @orchestrator<br/>tablica stanu docs/workflow/"]
    E1["Etap 1 · @business-analyst<br/>docs/specs/"]
    E2["Etap 2 · @architect<br/>docs/adr/"]
    E3["Etap 3 · @test-designer · RED<br/>src/test/ + kontrakty"]
    E4["Etap 4 · @developer · GREEN<br/>src/main/"]
    E5["Etap 5 · @qa-explorer<br/>requests/ + testy adversarial"]
    E6A["Etap 6 · @code-reviewer<br/>tylko odczyt"]
    E6B["Etap 6 · @sec-reviewer<br/>tylko odczyt"]
    DONE["Commit modułu"]

    E0 --> E1
    E1 -- "BRAMKA: brak ASSUMPTION" --> E2
    E2 -- "BRAMKA: projekt pokrywa każdą komendę" --> E3
    E3 -- "BRAMKA: test-compile OK<br/>ale test FAIL" --> E4
    E4 -- "BRAMKA: verify OK<br/>src/test nietknięte" --> E5
    E5 -- "BRAMKA: src/main nietknięte" --> E6A
    E5 --> E6B
    E6A -- "[APPROVED]" --> DONE
    E6B -- "[APPROVED]" --> DONE
    E6A -. "CHANGES REQUIRED" .-> E3
    E6B -. "CHANGES REQUIRED" .-> E3

    classDef gate fill:#fff3cd,stroke:#856404,color:#000
    classDef review fill:#f8d7da,stroke:#721c24,color:#000
    class E3 gate
    class E6A,E6B review
```

## Blokady zapisu (kto może co dotykać)

| Rola | Może pisać | Nie może pisać |
|---|---|---|
| `@business-analyst` | `docs/specs/` | kod, projekt |
| `@architect` | `docs/adr/` | kod, testy |
| `@test-designer` | `src/test/`, kontrakty w `src/main/` | logika biznesowa w `src/main/` |
| `@developer` | `src/main/` | `src/test/` (wyjątek: lista z `scripts/green-signature-allowlist.txt`) |
| `@qa-explorer` | `requests/`, nowe testy | `src/main/` |
| `@code-reviewer`, `@sec-reviewer` | nic | wszystko |

Blokady sprawdza `scripts/check-write-lock.sh red|green|qa`. Porównuje z ostatnim zatwierdzonym
punktem odniesienia, nie z dowolnym `HEAD`.

## Zasada powrotu

Gdy recenzja zwróci `CHANGES REQUIRED`, praca **wraca do etapu 3**, nie do etapu 4. Najpierw
powstaje czerwony test dla każdego znaleziska, dopiero potem poprawka. Test, który nie pada przed
poprawką, nie dowodzi poprawki.

Opisy w języku angielskim i polskim: `ai-sdlc-flow.en.md` oraz `ai-sdlc-flow.pl.md`.
