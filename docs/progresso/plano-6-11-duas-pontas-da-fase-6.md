# Plano da tarefa 6.11: duas pontas da Fase 6

Tarefa crítica (modo de plano; dinheiro, estoque da maleta e sync). **Nenhum código antes do `[ORQUESTRADORA] APROVADO`.** Fase PLANO feita pela conta 1; a fase CÓDIGO fica com a conta 2, depois que a #120 (6.10) fechar. Base: [fase-6-plano.md](fase-6-plano.md) (Regras da Fase 6, seção 6.11, R1 a R6 respondidas "Sim", D1 a D13 pelo padrão seguro), [ADR-0014](../decisoes/ADR-0014-devolucao-troca-e-garantia.md) (Aceito; itens 4 a 6, 8, 9, 11 e 14, com as linhas datadas da 6.5 e da 6.7), a herança da 6.7 e da 6.8 em [fase-6.md](fase-6.md) e a §10 da especificação ("Testes obrigatórios de sync": devolução fora do prazo offline, relógio errado). Código olhado: `testes-sync/` inteiro (o da 5.12, com as correções das issues #127 e #130), `apps/seller-app/src/shared/operacoes.ts` (os pedidos da ponte da 6.8) e, do backend, só `OperacoesDoVendedor` (hora corrigida e limitada), `ServicoDeAutorizacoes.pedir` (pedido desnecessário pelo envio) e os gatilhos de `vendas` e `devolucoes`.

## O que já existe (não refazer)

- **`testes-sync/` (5.12):** o computador de verdade em Node (`aparelho.ts`, SQLCipher, relógio com deslocamento), a dona pela API (`dona.ts`), o proxy de rede ruim com roteiro por caminho, o oráculo com as **23 tabelas** (a 6.7 já pôs as oito da Fase 6 e as linhas privadas novas em `idsDeOutrosVendedores`), o livro e a afirmação final (voltas até o servidor parar, oráculo, nada de outro vendedor, marcadores, colunas, destino de cada operação, conciliação "estoque + maletas").
- **Servidor (6.3 a 6.7):** os cinco tipos novos pelo envio, formato 1; fora do prazo, acima do limite e crédito acima do saldo **viram revisão, nunca recusa**; `RECUSADO` só para o impossível. O pedido de autorização "desnecessário" que chega pelo envio é gravado (`FORA_DO_PRAZO`, `PEDIDA`), não recusado. A hora de cada operação é a declarada menos a diferença do relógio, limitada entre a venda (ou o último contato) e a chegada.
- **Corona Vendas (6.8):** as operações pela ponte (`devolucoes.registrar`, `autorizacoes.pedir`, `trocas.registrar`, `devolucoes.cancelar`, `trocas.cancelar`, `vendas.vender` com pagamento `CREDITO` e `creditoId`, as prévias, `devolucoes.listar`, `autorizacoes.listar`, `creditos.doCliente`, `envios.naoAceitos`), o prazo pela cópia local da venda e pelo relógio do computador, o JSON da outbox sem nenhum valor.
- **Backend:** `PushDaFase6Test`, `PullDaFase6Test`, `CreditoSemSaldoTest` e o oráculo de dois vendedores já provam cada regra contra o PostgreSQL. Aqui a prova é **o motor de verdade do app contra a API de verdade**, com rede ruim: nada de repetir as regras uma a uma.

## O problema do tempo (a decisão que muda o resto)

A API das duas pontas usa o relógio real e **corrige e limita** toda hora que vem do computador: a venda nunca fica antes do envio da maleta nem depois da chegada. Então, sem ajuda, toda venda do teste é "de hoje" para o servidor, e **o servidor nunca acha nada fora do prazo**: o caso da §10 e do ADR-0014, item 5 (o app achou dentro, o servidor acha fora), não acontece. Mudar o relógio do computador não basta (o servidor corrige justamente isso).

**Proposta (dúvida E1, padrão):** uma "máquina do tempo" só do `testes-sync/`, em `src/maquina-do-tempo.ts`, que **envelhece uma venda já gravada** no banco descartável das duas pontas: numa transação só, `ALTER TABLE corona.vendas DISABLE TRIGGER vendas_conteudo_imutavel`, `UPDATE corona.vendas SET vendida_em = <meio-dia de Brasília de N dias atrás> WHERE id = ...`, `ENABLE TRIGGER` e `COMMIT` (DDL do PostgreSQL é transacional: ninguém vê o gatilho desligado). Os outros gatilhos continuam ligados, então a versão e o `sync_seq` sobem e **a venda envelhecida chega ao computador pelo pull** (cópia local e servidor com a mesma data, como na vida real). Usa o mesmo `psql` do contêiner com o usuário de administração do banco de teste; nunca toca o banco de desenvolvimento nem o código da API. Fica num arquivo separado de `banco-do-servidor.ts`, que continua só leitura, e o README diz que só ele grava e só a data da venda.

- Se uma trava pedir coerência (por exemplo, a venda não poder ficar antes do envio da maleta), a máquina envelhece também `remessas.enviada_em` da mesma forma; o código confere isso primeiro e anota em "Decisões que tomei".
- **Virada do dia em Brasília:** os casos no limite do prazo dependem do "dia de hoje" em Brasília. `esperarForaDaVirada()` espera passar a meia-noite de Brasília (03:00 UTC) se faltar menos de 10 minutos para ela, antes de envelhecer (dúvida E2). Os prazos vêm de `GET /api/dona/configuracoes/garantia` (nada de 7 e 30 escritos no teste).
- Teste sem API (`maquina-do-tempo.test.ts`, roda no `npm test`): o meio-dia de Brasília de N dias atrás para qualquer hora do dia (inclusive 02:59 e 03:00 UTC) e o "perto da virada".

## Cenários (dois arquivos novos em `testes-sync/src/cenarios/`, numeração seguindo a 5.12)

Cada caso termina com `cenario.afirmarNoFim(...)` (o oráculo, o livro e a conciliação), mais as afirmações próprias abaixo. Preços fixos (por exemplo 80,00, 50,00 e 30,00) para os valores serem conferidos à mão.

### `devolucao-fora-do-prazo.duas-pontas.test.ts`

- **14. Devolução fora do prazo sem internet, relógio certo (§10; ADR-0014, item 6).** Venda enviada e envelhecida para `prazo + 1` dias. Sem internet: a prévia diz fora do prazo e `devolucoes.registrar` é recusado no app com o texto do glossário; `autorizacoes.pedir` grava na outbox. Internet: o pedido chega `PEDIDA`, tipo `FORA_DO_PRAZO`, com a hora corrigida. A dona **aprova** pela API; o pull traz `APROVADA` e o "vale até". Sem internet de novo: a devolução com o `autorizacaoId` passa no app e vai; no servidor fica `ACEITA` (não revisão), a autorização consumida, a peça boa volta à maleta (`DEVOLUCAO_CLIENTE`), o reembolso negativo igual ao valor pago, e a segunda devolução com a mesma autorização é recusada no app. Na mesma maleta, um segundo pedido que a dona **nega** chega ao app como negado e a devolução continua bloqueada.
- **15. Relógio atrasado no limite (ADR-0014, item 5; dúvida R2).** Venda envelhecida para `prazo + 1` dias; relógio do computador **1 dia atrasado**: para o app é o último dia do prazo, e a devolução (arrependimento, peça boa) é registrada sem internet. Internet: o envio fica `ACEITO` (nunca `RECUSADO`), a devolução nasce `EM_REVISAO` com `hora_ajustada`, o app mostra "A dona vai olhar esta devolução." A dona **não aceita** (observação obrigatória): a versão nova chega ao computador; os movimentos e o reembolso **ficam** (a peça está com o vendedor). Variação com **defeito** no limite do `prazoDefeitoDias` (`prazo_defeito + 1`, 1 dia atrasado, peça com defeito): `EM_REVISAO`, peça para a loja (`destino_peca = LOJA`), **nenhum** movimento na maleta nas duas pontas; a dona **mantém**.
- **16. Relógio adiantado no limite.** Venda envelhecida para exatamente `prazo` dias (dentro para o servidor); relógio **1 dia adiantado**: o app bloqueia a devolução e o vendedor pede autorização sem internet. O servidor grava o pedido (o "desnecessário" pelo envio vira `FORA_DO_PRAZO`, `PEDIDA`, sem recusa); a dona aprova; o app, ainda adiantado, vê a autorização valendo e registra a devolução sem internet; o servidor a aceita `ACEITA`.

### `troca-e-credito.duas-pontas.test.ts`

- **17. Troca sem internet com a peça B vendida no mesmo lote.** Maleta com A (2 unidades, 80,00) e B (2, 50,00). Sem internet, um lote só: venda de A ao cliente X; **troca** de A por B dessa venda ainda não enviada (dependência; B mais barata, sobra 30,00 de crédito); venda da **última** B ao cliente Y; o app recusa uma quarta operação com B (saldo zero na maleta). Internet: o lote vai num envio, tudo `ACEITO` na ordem; no servidor, B com saldo 0 e A com 2 (venda −1, `TROCA_ENTRADA` +1), três vendas (a nova da troca com `troca_id`), `TROCA_SAIDA` de B, crédito de 30,00 `ABERTO`; os mesmos números na maleta e nas consultas do computador.
- **18. Crédito gerado e usado sem internet.** Sem internet: troca com sobra de 30,00 e, depois, venda de 50,00 ao mesmo cliente com 30,00 de `CREDITO` (`creditoId`) e 20,00 em dinheiro; um segundo uso do mesmo crédito é recusado no app (saldo zero). Internet: tudo `ACEITO`; no servidor o crédito `UTILIZADO`, um uso de 30,00, **nenhuma** linha em `creditos_sem_saldo`, e a conta de sempre fecha em cada venda (total − devolvido = pagos + a receber). Um segundo vendedor com computador ligado não recebe nada do crédito (oráculo e varredura).
  - **18b (proposta, dúvida E3).** A venda de A já foi enviada e o computador fica sem internet; a dona registra **pela loja** a devolução da peça inteira; sem saber, o vendedor faz sem internet a troca da mesma peça e usa o crédito numa venda. Internet: a troca é `RECUSADO` (acima do vendido, o impossível), a venda com o crédito vem `DEPENDE_DE_ENVIO_RECUSADO`, o app desfaz as provisórias (saldo, crédito, "a receber") e mostra os dois em "envios não aceitos"; a dona os vê nos envios com problema.
- **19. Cancelar a troca depois de enviada.** Troca com sobra de crédito, enviada; no mesmo dia, sem internet, **Cancelar troca**. Internet: `ACEITO`; no servidor a troca com `cancelamento_id`, os movimentos, pagamentos e crédito de sinal oposto (nada apagado, nada alterado além do cancelamento), a venda nova estornada; o computador igual. Variação: com o crédito já usado, o app recusa o cancelamento com o texto do servidor.
  - **19b (proposta, dúvida E3).** Troca enviada (crédito em aberto); sem internet o vendedor cancela a troca; nesse meio-tempo a dona **transfere** o crédito para outro vendedor. Internet: o cancelamento é `RECUSADO` (crédito já usado), o app desfaz e mostra o motivo; o outro vendedor recebe o crédito novo e o primeiro não recebe o id dele.
- **20. Rede ruim no meio do envio da troca.** Sem internet: a troca e a venda que usa o crédito dela. Internet com roteiro: o 1º envio **corta depois de o servidor gravar**, o 2º leva 502, o 1º recebimento corta no meio. No fim, o reenvio dá `JA_RECEBIDO`, e há **exatamente um** de cada no servidor: troca, devolução da troca, venda nova, crédito, uso, `TROCA_ENTRADA`, `TROCA_SAIDA` e pagamento do crédito; o cursor do computador não andou pela metade e não sobra provisória.

## Arquivos a criar e mudar (só `testes-sync/` e documentos)

- **Novos:** `src/maquina-do-tempo.ts` e `src/maquina-do-tempo.test.ts`; `src/cenarios/devolucao-fora-do-prazo.duas-pontas.test.ts`; `src/cenarios/troca-e-credito.duas-pontas.test.ts`.
- **`src/cenario.ts` (`Tela`):** `devolver`, `pedirAutorizacao`, `trocar` (devolve os ids da troca, da venda nova e do crédito), `cancelarDevolucao`, `cancelarTroca`, `venderComCredito`. Todos pelos pedidos da ponte (a mesma validação), sem nenhum valor calculado no teste, e cada um no livro. As recusas esperadas usam `chamar` (sem exigir sucesso).
- **`src/dona.ts`:** `garantia()`, `autorizacoes()`, `aprovarAutorizacao`, `negarAutorizacao`, `manterDevolucao`, `naoAceitarDevolucao`, `devolverPelaLoja`, `transferirCredito` (as rotas da dona que já existem, 6.4, 6.5 e 6.9).
- **`src/livro.ts`:** os cinco tipos novos; o lançamento da troca guarda a venda nova.
- **`src/afirmacoes.ts`:**
  1. "existe no servidor" passa a olhar `devolucoes` (id e `cancelamento_id`), `trocas` (id e `cancelamento_id`) e `autorizacoes_excecao`; a regra "recusada e mesmo assim existe" vale para os tipos novos.
  2. A venda nova de uma troca aceita conta como venda do livro (senão "venda no servidor e não no livro" acusa toda troca); a de uma troca recusada não pode existir.
  3. **Conferência da Fase 6 em SQL próprio**, à parte do código da API, como o oráculo: devolvido por linha ≤ vendido; Σ créditos − Σ usos = créditos em aberto, crédito nunca negativo; reembolso ≤ pago; total − devolvido = pagos + a receber em toda venda. Se as conferências da 6.10 já estiverem na `main`, a afirmação também chama a rota delas e exige que venham vazias (dúvida E5).
  4. O texto "15 tabelas" do log vira o número de `TABELAS` (hoje 23).
- **`README.md` do `testes-sync`:** os casos 14 a 20, a máquina do tempo (o único ponto que grava no banco, e só a data da venda) e a espera da virada; a frase "A devolução fora do prazo offline ... entram nas Fases 6 e 7" passa a dizer que a da Fase 6 entrou.
- **Documentos no fim:** seção 6.11 em `docs/progresso/fase-6.md` e a linha da 6.11 em `docs/PROGRESSO.md`.
- **Nada no backend nem no Corona Vendas**, a menos que um cenário ache defeito (abaixo). Nenhuma migração (nada no banco muda).

## Ordem dos commits (cada passo com push e Situação atualizada)

1. `test(sync): máquina do tempo das duas pontas` (o módulo e o teste sem API, visto falhando antes).
2. `test(sync): livro, tela e dona da Fase 6` (os atalhos e as mudanças da afirmação final; os 17 testes de antes continuam verdes).
3. `test(sync): devolução fora do prazo nas duas pontas` (14 a 16).
4. `test(sync): troca e crédito nas duas pontas` (17 a 20).
5. `docs: relatório da tarefa 6.11` (README, `fase-6.md`, `PROGRESSO.md`).

Se um cenário achar defeito: commit `fix(...)` separado, **teste do backend ou do app escrito antes** (dinheiro, estoque e sync), e a explicação no PR. Correção grande (mais de um serviço, ou migração) para e pergunta à orquestradora antes.

## Como rodar e o que conta como pronto

- `npm test -w @corona/testes-sync` (sem API, inclui a máquina do tempo) e `npm run test:duas-pontas -w @corona/testes-sync` (todos os arquivos, inclusive os 17 de antes e o estresse curto), local, com Docker; cada arquivo novo também com uma semente fixa repetida duas vezes, para pegar instabilidade.
- `npm run lint`, `typecheck` e `format:check`. `api:test` e `barreira:varrer` só se o backend ou o Corona Vendas mudarem.
- PR em rascunho até tudo passar local; o job `sync-duas-pontas` do CI verde.

## Riscos

- **Tempo de CI:** cada caso leva de 30 s a 1 min; os 7 a 9 casos somam de 4 a 7 minutos ao job `sync-duas-pontas` (hoje de 6 a 8). Cada arquivo cria a própria dona, categoria e vendedores, sem depender dos outros.
- **Virada do dia:** um caso no limite que passa da meia-noite de Brasília inverte o resultado; a espera de até 10 minutos evita, e a máquina do tempo calcula "hoje" na hora de envelhecer.
- **Gatilho desligado:** o `DISABLE TRIGGER` só vive dentro da transação; os arquivos rodam um por vez (`fileParallelism: false`), então nada mais escreve em `vendas` naquele instante. Se a transação falhar, o `ROLLBACK` deixa o gatilho ligado.
- **Afirmação final mais rígida:** a conferência da Fase 6 e a regra da venda da troca passam a valer também para os arquivos de antes e o estresse (que não fazem devolução: nada muda para eles, e o passo 2 prova isso antes dos casos novos).
- **Relógio adiantado e a validade da autorização:** com o relógio 1 dia adiantado, o "vale até" de 7 dias continua valendo no app; com mais dias, o app acharia vencida. O caso 16 usa só 1 dia de propósito.
- **Instabilidade da rede ruim:** o caso 20 usa roteiro por caminho (sem sorteio) para o corte cair exatamente no envio da troca.

## O que fica fora

- Devolução e troca no **estresse** de 5 vendedores (dúvida E4; padrão: fica para a 6.12 ou depois).
- Venda a prazo (6.6, depende da 7.3), acerto e pendência do vendedor (7.5), maleta em acerto pelas duas pontas (sem acerto ainda), limite da semana e valor de aprovação (o computador não sabe o valor; o backend já prova), foto da peça devolvida (D10), a virada às 23h59 e 00h00 (já provada no backend e no `garantia.test.ts` do app).
- Testes de tela (6.8 já fez) e o teste de uso com a dona (6.12).

## Dúvidas (com o padrão que sigo se não houver resposta)

| Nº | Pergunta | Padrão |
| --- | --- | --- |
| E1 | Como fazer o servidor achar uma venda "antiga"? (a) máquina do tempo só no banco descartável, desligando o gatilho de conteúdo da venda dentro de uma transação; (b) relógio da API ajustável por variável (mexe em código de produção e mistura o relógio do Java com o `now()` do PostgreSQL); (c) só os casos em que o relógio do computador muda (o servidor nunca acharia fora do prazo, e a §10 fica sem prova) | **(a)** |
| E2 | Perto da meia-noite de Brasília, esperar a virada (até 10 minutos) ou pular o caso? | **Esperar**: nunca pular em silêncio |
| E3 | Entram os casos 18b (a dona devolve pela loja antes da troca chegar) e 19b (a dona transfere o crédito antes do cancelamento chegar)? Não estão na lista da 6.11, mas são as duas pontas mexendo no mesmo dinheiro | **Sim** |
| E4 | Pôr devolução e troca no estresse de 5 vendedores? | **Não** agora (CI mais longo e mais instável); a 6.12 decide |
| E5 | As conferências da 6.10 entram na afirmação final? | **Sim**, se estiverem na `main` quando o código começar; a conferência em SQL próprio entra de qualquer jeito |
| E6 | Defeito achado por um cenário: corrigir aqui ou abrir issue? | Corrigir aqui se for pequeno (um serviço, sem migração), com teste antes e commit `fix` separado; maior, parar e perguntar |
| E7 | Um arquivo novo ou dois? | **Dois** (prazo; troca e crédito): falha mais fácil de achar, mesmo tempo total |
