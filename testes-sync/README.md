# testes-sync

Testes do sync com **duas pontas** (o motor do Corona Vendas contra a API de verdade) e rede ruim (tarefa 5.12, ADR-0013 item 20).

## O que já tem

- `src/proxy-de-rede-ruim.ts`: proxy HTTP local, com semente, que atrasa, derruba a conexão antes de encaminhar, **corta depois de o servidor gravar e antes da resposta**, devolve 502 e limita a banda. Aceita um roteiro fixo (a falha N-ésima acontece ali) ou sorteio com peso. O histórico diz o que o servidor chegou a responder.
- `src/sorteio.ts`: sorteio com semente. Todo teste imprime a semente que usou, para repetir a falha.

## O que falta (depende das tarefas 5.6 e 5.8)

Ligar o motor real do app em Node, subir a API em Docker (como o `testes-e2e`), vários aparelhos, os cenários da §10 e §20, e o estresse curto (cerca de 1 min, todo PR) e longo (10 min, semanal).

```bash
npm test -w @corona/testes-sync
```