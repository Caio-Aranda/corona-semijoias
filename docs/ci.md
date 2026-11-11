# O CI (GitHub Actions): o que roda e quando

O orçamento de minutos do GitHub Actions é pequeno até o fim do projeto. O CI foi organizado para gastar pouco **sem tirar nenhuma verificação**: testes, `lint`, `typecheck`, formatação, barreira do Corona Vendas, migrações e `npm audit --audit-level=high` continuam todos existindo; só não rodam quando o que mudou não pode afetá-los.

## O que roda e quando

| Situação | O que acontece |
| --- | --- |
| PR em **rascunho** | Nada roda. |
| PR tirado do rascunho, ou push novo num PR aberto | Roda o que o PR mexeu (tabela abaixo). Um push novo **cancela** o run antigo do mesmo PR. |
| Merge na `main` | **Não** roda o CI de novo (o PR já passou). |
| Toda **segunda-feira** 05:00 UTC, e sob demanda (Actions → CI → Run workflow) | Roda **tudo** na `main`, inclusive `npm audit` e OSV (pega vulnerabilidade nova em dependência que ninguém mexeu). Os testes de ponta a ponta (`E2E`) e o estresse do sync têm os próprios agendamentos. |

| Job | Roda no PR quando muda... |
| --- | --- |
| `frontend` (lint, typecheck, formatação, testes dos apps e pacotes, build, barreira) | `apps/`, `packages/`, `scripts/`, `testes-e2e/`, `package*.json`, configuração da raiz ou o workflow |
| `backend` (`./mvnw verify`, com PostgreSQL de verdade) | `backend/`, `infra/`, o `openapi.json` dos clientes, `docs/glossario-interface.md` (o `GlossarioDasMensagensTest` lê esse arquivo) ou o workflow |
| `migracoes` | `backend/src/main/resources/db/migration/` |
| `dependencias` (`npm audit` e OSV) | `package*.json`, `backend/pom.xml` ou o workflow |
| `docs` (só a formatação) | só documentos (`docs/`, `*.md`), sem nada do front |
| `mudancas` | sempre (é o que decide os outros; custa segundos) |

Um PR só de `docs/` roda só `docs`; só de `apps/` ou `packages/` não sobe o backend com Testcontainers; só de `backend/` não roda o front.

Fora de qualquer job: `deploy/` (scripts de shell e compose da VPS) não é coberto pelo CI; só se confere à mão (`bash -n`, `docker compose config`).

## O job da regra de merge: `ci`

O job **`ci`** roda sempre no fim (menos em rascunho) e só fica verde se o `mudancas` terminou com sucesso (senão nada foi decidido) e nenhum job que rodou falhou ou foi cancelado. Em run cancelado por um push novo ele nem roda (`!cancelled()`), então nunca termina verde por engano. Job pulado por filtro **não** reprova, então nenhum PR fica sem status. **A regra de merge deve exigir o `ci`** (os outros jobs podem ser pulados e não devem ser exigidos um a um).

## Regra para quem abre o PR

**Abra o PR como rascunho** e só tire do rascunho depois de rodar **localmente** o que o CI rodaria:

```bash
npm run api:test       # backend (precisa do Docker aberto)
npm run lint
npm run typecheck
npm run format:check
```

(e `npm test --workspaces --if-present` se mexeu no front). Assim o CI roda uma vez, verde, e não a cada tentativa.

## Cache

`setup-node` guarda o cache do npm (pelo `package-lock.json`) e `setup-java` o do Maven (pelo `backend/pom.xml`). O download do Maven pelo wrapper e a imagem do PostgreSQL dos testes não são guardados em cache (pequenos perto do resto).
