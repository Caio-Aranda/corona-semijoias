# testes-sync

Testes do sync com **duas pontas**: o motor de verdade do Corona Vendas (a mesma montagem do app, `apps/seller-app/src/main/montagem.ts`, com o banco SQLCipher de verdade) rodando em Node contra a API de verdade e um PostgreSQL descartável, com vários computadores e uma rede ruim com semente (tarefa 5.12; ADR-0013, item 20).

## Como rodar

Precisa do Docker aberto e das portas **5498** (PostgreSQL) e **8098** (API) livres. Nunca usa o banco de desenvolvimento (5432) nem o do e2e (5499).

```bash
npm test -w @corona/testes-sync                    # só os testes sem API (proxy, varredura): segundos
npm run test:duas-pontas -w @corona/testes-sync    # cenários e estresse curto (1 min): cerca de 6 a 8 min
CORONA_SYNC_DURACAO=PT10M npm run estresse -w @corona/testes-sync   # só o estresse, longo
```

| Variável              | Para quê                                                                                                                  |
| --------------------- | ------------------------------------------------------------------------------------------------------------------------- |
| `CORONA_SYNC_SEMENTE` | Repete uma falha: a semente sai no log de cada arquivo ("Duas pontas (...): semente=..."). Sem ela, vale a hora.          |
| `CORONA_SYNC_DURACAO` | Duração do estresse (ISO-8601: `PT1M`, `PT10M`). Padrão `PT1M`.                                                           |
| `CORONA_SYNC_JAR`     | Pula a compilação da API e usa este jar (caminho absoluto ou relativo à pasta `testes-sync`). Use um jar do código atual. |

O `globalSetup` (`ambiente/preparar.ts`) derruba e sobe o PostgreSQL (`ambiente/docker-compose.yml`, projeto `corona-sync`, sem volume), compila a API (`mvnw -DskipTests package`), sobe o jar e espera a saúde; no fim, desmonta tudo. O log da API fica em `testes-sync/resultados/api.log` (no CI, vira artefato quando falha).

## Como funciona

- `src/aparelho.ts`: um "computador do vendedor". Pasta temporária própria, cofre de teste (AES-GCM com chave na memória, no lugar do DPAPI), o `fetch` do Node apontado para **um proxy só dele** e um relógio de teste (hora real mais um deslocamento; as esperas do motor andam 10 vezes mais rápido). O teste fala com o app pelos mesmos pedidos que a tela manda pela ponte (conferidos pela mesma validação) e só **lê** o banco local.
- `src/dona.ts`: a dona pela API de verdade, sem proxy (vendedor, peça, estoque, foto, maleta, preço, códigos, trocar e bloquear computador, quarentena, envios com problema, conciliação).
- `src/proxy-de-rede-ruim.ts`: atraso, queda antes, **corte depois de o servidor gravar**, corte no meio da resposta, 502, banda; roteiro geral, **roteiro por caminho** ("o 1º push corta depois de gravar") ou sorteio com semente; troca de cabeçalho (a versão do app, para imitar a dona subindo a versão mínima); observador de toda resposta.
- `src/oraculo.ts`: o que é de cada vendedor, calculado à parte em SQL (a mesma regra do `CenarioDeSync.oraculo` do backend), lido pelo `psql` do contêiner (só leitura).
- `src/afirmacoes.ts`: a afirmação final, a mesma para todo cenário e para o estresse: cópia local igual ao oráculo (toda linha, versão e lápide; nenhuma provisória), nada privado de outro vendedor, nenhum marcador proibido (`scripts/marcadores-proibidos.json` mais custo e fornecedor) em **nenhuma** resposta que passou pela rede, colunas locais só as da lista do pull, cada operação do livro com destino no servidor (aceita uma vez, recusada ou em conflito nos "envios com problema", ou em quarentena) e a conciliação "estoque + maletas" fechando. Na falha, imprime a semente, o histórico de cada proxy e o registro de cada app.

## Cenários

| Arquivo                               | Cenários                                                                                                                                                                                                                                                                     |
| ------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `cenarios/fumaca`                     | o caminho inteiro com rede boa                                                                                                                                                                                                                                               |
| `cenarios/envio-e-recebimento`        | 1. corte depois de gravar e reenvio "já recebido"; 2. lotes de 50 com corte, 502 e banda baixa; 3. queda no meio do recebimento (o cursor não anda pela metade); 4. estorno de venda já enviada com corte                                                                    |
| `cenarios/varios-aparelhos`           | 5. dois vendedores ao mesmo tempo com a dona mudando preço e foto; 6. segundo computador e troca; 7. bloqueado com vendas aguardando (quarentena com corte, apagar, aprovar e descartar); 8. bloqueado sem nada aguardando, e com a quarentena sem resposta; 9. CPF repetido |
| `cenarios/prazo-relogio-versao-fotos` | 10. prazo sem internet esgotado; 11. relógio adiantado e atrasado; 12. app abaixo da versão mínima enviando; 13. foto trocada sem internet e queda no meio do download                                                                                                       |
| `estresse`                            | 5 vendedores (um troca de computador no meio), a dona mexendo e rede ruim sorteada                                                                                                                                                                                           |

A devolução fora do prazo offline e o acerto com sobra e falta entram nas Fases 6 e 7, neste mesmo `testes-sync/`.

## Como repetir uma falha

1. Pegue a semente no log (`Duas pontas (...): semente=...`) ou no relatório da falha.
2. Rode só aquele arquivo com ela: `CORONA_SYNC_SEMENTE=<semente> npm run test:duas-pontas -w @corona/testes-sync -- src/cenarios/<arquivo>`.
3. A semente repete as decisões da rede de cada pedido e o roteiro do estresse; a hora exata em que cada pedido sai não se repete (rede e API de verdade), por isso o estresse só afirma o estado final.
