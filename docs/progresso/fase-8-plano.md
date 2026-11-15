# Plano da Fase 8: painel, relatórios avançados e conciliações

Só plano: nenhuma linha de código nesta tarefa (8.1). A linha da Fase 8 na §15 pede o **gerenciamento semanal** no estilo da tela de referência (§2), a **curva ABC**, o **DRE**, as **conciliações automáticas** e a **auditoria**. As decisões que mudam tabela, fórmula ou número mostrado estão no [ADR-0015](../decisoes/ADR-0015-numeros-gerenciais-e-conferencia-diaria.md), **Proposto**: até o responsável decidir, as tarefas seguem os padrões seguros escritos lá.

O que as fases anteriores deixaram para a Fase 8 e onde entra:

| Deixado por | O quê | Tarefa |
| --- | --- | --- |
| Fase 2 (2.5) | Curva ABC (precisava de vendas) | 8.5 |
| Fases 2, 3 e 4 (ADR-0008, item 9) | Conciliação completa "estoque central + maletas = comprado − vendido − perdido" | 8.3 |
| §13, item 3 | "Saldo da maleta = enviado − vendido − devolvido − perdido ± trocas" para toda maleta, não só a acertada (a C8 só olha a acertada) | 8.3 |
| Fase 7 (7.8) | Rodada diária das conciliações com aviso e o resultado guardado | 8.4 |
| Fase 7 (7.12) | DRE, lucro por produto e por vendedor | 8.6 |
| Fase 7 (7.12) | Comissão "a pagar" prevista para todos os vendedores (hoje a prévia só existe no Corona Vendas) | 8.7 (painel) |
| Fase 7 (7.13) | §7.4: o vendedor pedir o acerto e bipar as sobras no Corona Vendas | 8.10 (decisão abaixo) |
| Fase 7 (7.13) | Tipar a prévia do fechamento no OpenAPI e "quanto falta para a meta" na Gestão | 8.11 |
| Fase 7 (7.3 e 7.13) | Testes que falham sozinhos por causa de `configuracoes` e das cotações deixadas por outras classes | 8.2 |
| Fase 7 (7.13) | Cenário Playwright da dona fazendo o acerto | 8.12 |
| §12 (Vendas) | Relatórios de vendas da dona (por vendedor, praça, cliente, produto, categoria e período; ranking; ticket médio; vendas canceladas): **não existem ainda** (o Corona Gestão não tem o grupo Vendas; só o vendedor tem os dele) | 8.5 |
| §12 (Controle) | Auditoria, sincronizações e computadores, acessos: a tabela `auditoria` existe desde a 1.4 e é gravada por quase todos os serviços, mas **ninguém a lê** | 8.9 |

**Decisão sobre a §7.4 (vendedor pede o acerto e bipa as sobras).** Pelo padrão seguro, **entra na Fase 8** como a tarefa 8.10, **só com internet** e sem regra nova: o texto da §7.4 continua valendo como está, as rotas do vendedor já existem e são testadas desde a 7.4 (`/api/vendedor/acertos`), a contagem é a mesma da dona (ela vê quem leu, quando e de qual computador) e **só a dona fecha e desiste**, como hoje. Sem internet, o botão explica que precisa de internet; nada vai para a outbox (pedir e bipar sem internet mudaria o sync e fica fora). A dona continua podendo começar e contar sozinha.

**Fora da Fase 8** (sugestões da 7.13 que não são painel, relatório nem conciliação; ficam para a orquestradora decidir depois): botão de gerar as contas a pagar de uma compra, estornar recebimento de cliente pela dona, editar ou cancelar parcela, cotação de cada lançamento no extrato do caixa, "Sem limite" × "Limite não definido" no relatório de vendedores, texto antigo da rota "Cadastrar vendedor" no OpenAPI, testes de integração dos 409 `SEM_COTACAO_PARA_O_ACERTO` e `REVISAO_PENDENTE_NO_ACERTO`, refazer parcelamento de compra. Multa, juros e cartão continuam fora do escopo.

## Regras da Fase 8 (valem para todas as tarefas)

- **Só leitura, salvo a 8.4.** Painel, relatórios e conciliações não gravam nada nem mudam nenhuma regra de venda, maleta, acerto, caixa ou sync. A única tabela nova é a do resultado das rodadas (8.4). Se uma tarefa achar que precisa mudar regra, **para e registra** no PR para a orquestradora.
- **Plano antes do código** (CLAUDE.md) nas tarefas que leem dinheiro para tirar número novo (8.3, 8.4, 8.6, 8.7, 8.11): modo de plano, plano aprovado no PR antes de qualquer migração ou consulta.
- **Testes primeiro** em dinheiro e estoque, com massa conhecida e o resultado feito à mão nos comentários. Pronto = funcionalidade + testes + relatório + conciliações zeradas sobre a massa.
- Dinheiro em `NUMERIC(14,2)`, tudo em Real, **soma no banco**; arredondamento só em `Dinheiro` (HALF_UP, escala 2) e conversão só em `ConversaoDeMoeda`; o `ArredondamentoSoNoUtilitarioTest` continua valendo. Dólar e Guarani só na tela, pela cotação do dia de **cada lançamento**, e o total em Dólar ou Guarani é a soma das linhas já convertidas (§12). Dólar e Guarani **nunca** entram em conferência.
- **Uma fórmula só** (§13, item 2): um número que aparece em dois lugares (painel e relatório de acertos, DRE e lucro, curva ABC e vendas) sai da mesma consulta, e um teste confere que os dois batem.
- Relatórios no padrão da §12: filtros, totais no rodapé, detalhamento até o registro, PDF, Excel e CSV pelo motor existente, três moedas com a data da cotação de cada linha no Excel e no CSV.
- **Custo só para a dona.** Rotas novas em `/api/dona/...`, só ADMIN, 401 sem login e 403 para o vendedor com teste em cada uma. Nada desta fase vai ao Corona Vendas, salvo a 8.10 (que não lê custo); `npm run barreira:varrer` passa quando o Corona Vendas for tocado.
- Telas: textos só nos arquivos de textos, termos do `docs/glossario-interface.md` (acrescentar os novos da fase: painel da semana, enviado, devolvido, vendido, curva ABC, resultado do período, lucro, conferência de hoje), erros em português simples dizendo o que fazer, texto escuro sobre fundo claro, dourado só em títulos grandes.
- Migração nova só na 8.4 (a próxima livre; em 15/11/2026 a última é a V046). Migração publicada não se edita.
- Cada tarefa que regenera o cliente da dona (`npm run api:clientes`) roda **sozinha** no backend e na Gestão (os arquivos gerados conflitam).

## Ordem e dependências

1. **8.2** (arrumação dos testes) e **8.10** (Corona Vendas) podem correr em paralelo: arquivos diferentes, nenhuma regenera o cliente da dona.
2. **8.3** (conciliações novas) depois da 8.2 (usa os testes já isolados).
3. **8.4** (rodada diária) depois da 8.3 (roda as conciliações novas também).
4. **8.5** (vendas e curva ABC) depois da 8.4.
5. **8.6** (DRE e lucro) depois da 8.5 (usa a mesma consulta do vendido líquido) e da **decisão R1** do ADR-0015 (sem resposta, segue o padrão do item 1).
6. **8.7** (painel, consulta) depois da 8.6; **8.8** (painel, tela) depois da 8.7.
7. **8.9** (controle: auditoria, sincronizações, acessos) depois da 8.8 (só por causa do cliente gerado; não depende de regra dela).
8. **8.11** (prévia tipada) depois da 8.9.
9. **8.12** (Playwright da dona) depois da 8.11 (usa as telas do acerto com a prévia tipada).
10. **8.13** por último.

## Tarefas

| Tarefa | O que entrega | Esforço | Crítica / modo de plano |
| --- | --- | --- | --- |
| **8.1** | Este plano e o ADR-0015 Proposto (só documentos) | Médio | — |
| **8.2** | Arrumação: testes isolados de `configuracoes` e das cotações entre classes | Médio | Não |
| **8.3** | Conciliações novas: C11 estoque completo (comprado − vendido − perdido) e C12 saldo de toda maleta por eventos | **Alto** | **Sim** |
| **8.4** | Conferência diária: rodada automática e "Conferir agora", resultado guardado, aviso na tela inicial, histórico na Gestão | **Alto** | **Sim** |
| **8.5** | Relatórios de vendas da dona (§12, Vendas) e curva ABC | Médio | Não |
| **8.6** | DRE simplificado, lucro por produto e por vendedor | **Alto** | **Sim** |
| **8.7** | Painel semanal, consulta: Pedidos e Totais, comissão prevista | **Alto** | **Sim** |
| **8.8** | Painel semanal, tela no Corona Gestão | Médio | Não |
| **8.9** | Controle: auditoria, sincronizações e computadores, acessos | Médio | Não |
| **8.10** | Corona Vendas: pedir o acerto e bipar as sobras (só com internet) | Médio | Não |
| **8.11** | Acerto na Gestão: prévia tipada no OpenAPI e quanto falta para a meta | Médio | **Sim** |
| **8.12** | Cenário Playwright da dona fazendo o acerto | Médio | Não |
| **8.13** | Fechamento da Fase 8: ponta a ponta, roteiro de uso, especificação, ADR-0015 conforme a decisão | Médio | Não |

Esforço "Alto" segue o costume da Fase 7 (fase PLANO com o `implementador-critico`); se o responsável mantiver para a fase toda o esforço médio decidido em 15/11/2026, basta trocar a linha "Modelo e esforço" das issues.

### 8.2: arrumação dos testes

- Os testes `AutorizacaoDeDevolucaoTest.nenhumaRespostaDasRotasDaAutorizacaoTemCustoNemFornecedor` e `TrocaTest.prazoContaEmDiasDeBrasiliaComoNaDevolucao` falham sozinhos e passam na suíte (dependem do que outras classes deixam em `configuracoes` e em `cotacoes`).
- Cada classe que muda `configuracoes` ou grava cotação restaura o que mudou (ou monta o que precisa) no próprio `@BeforeEach`; nenhuma depende da ordem das classes.
- Prova: rodar cada classe afetada **sozinha** (`./mvnw test -Dtest=...`) e a suíte inteira; as duas verdes. Só código de teste.

### 8.3: conciliações novas (C11 e C12)

- **C11 estoque completo** (ADR-0008, item 9; §13): por produto, estoque central + saldo de todas as maletas não acertadas = entradas (compras ativas, ajustes e inventário para mais) − vendido líquido (vendas ativas menos devoluções e trocas que voltaram) − perdas (faltas, avarias, inventário para menos) ± devolução à dona. A conta exata sai dos tipos de `movimentos_estoque` e `movimentos_remessa`; o plano da tarefa lista cada tipo e o sinal.
- **C12 saldo da maleta** (§13): para **toda** maleta, o saldo por peça = enviado − vendido + estorno de venda − devolvido à dona − perdido ± trocas ± devolução do cliente, calculado pelos registros de origem (venda, devolução, troca, acerto), e igual à soma dos `movimentos_remessa`. Peça a peça, nunca negativo.
- Mesma forma das nove da Fase 7 (`ConciliacoesDaFase7` ou uma classe irmã): consulta única, só leitura, num retrato só; entram na rota `GET /api/dona/conciliacoes` (`?chave=C11`) e na tela **Conferência do dinheiro** (o título da tela pode virar "Conferência" se o glossário pedir). Em Real e em peças.
- Testes: massa conhecida (compra, maleta nova e reposição, venda, estorno, devolução, troca, devolução à dona, acerto com falta e defeito, inventário) zerada; um teste que **estraga** cada uma de propósito (por exemplo, `UPDATE` direto num movimento no teste) e confere a diferença com o tipo e o id do registro.
- Sem migração.

### 8.4: conferência diária

- ADR-0015, item 6. Migração com `rodadas_conferencia` e `diferencas_conferencia`, só do servidor, nunca sincronizadas, imutáveis (gatilho que recusa `UPDATE` e `DELETE`).
- Rodada automática às 03:00 de Brasília (`@Scheduled` com zona, como a limpeza técnica) e "Conferir agora" (`POST /api/dona/conferencias`, UUID do comando, 201/200/409); uma rodada por vez por trava consultiva do PostgreSQL.
- Roda todas: conferência do estoque (ADR-0008), conferência da devolução e troca (6.10), C1 a C10 sem a C6, C11 e C12. Uma conferência que dá erro não derruba as outras: a rodada fica `FALHOU` com a chave que falhou guardada.
- Aviso calculado na hora sobre a última rodada (`FonteDeAvisosDaConferencia`): diferenças, falha, ou nenhuma rodada nas últimas 26 horas. O aviso leva à tela.
- Gestão: a tela da conferência mostra a última rodada e o histórico (data, situação, quantas diferenças, detalhamento), com o botão "Conferir agora".
- Nada é corrigido sozinho.

### 8.5: relatórios de vendas e curva ABC

- Vendas (§12): por vendedor, praça, cliente, produto, categoria e período; ranking (vendedores e peças); ticket médio (vendido líquido ÷ número de vendas, com `Dinheiro`); vendas canceladas (estornadas, com quem estornou e o motivo). Filtros da §12; venda estornada fora dos totais e marcada.
- Uma consulta só para o **vendido líquido** por linha (venda menos devolução e troca), reaproveitada pela curva ABC, pelo DRE (8.6) e pelo painel (8.7).
- Curva ABC (ADR-0015, item 4) no grupo Estoque: por produto, pelo vendido líquido, faixas 80/95, com foto da peça (CLAUDE.md: toda leitura de produto mostra a foto; a falta de foto não trava nada).
- Gestão: grupo **Vendas** no menu de relatórios; telas no padrão da 7.12.
- **Sem custo** nestes relatórios (o lucro é da 8.6).

### 8.6: DRE e lucro

- ADR-0015, itens 1 a 3 (ou a decisão R1 do responsável, se vier antes).
- `GET /api/dona/relatorios/financeiro/resultado` (DRE do período) e `.../lucro-por-produto`, `.../lucro-por-vendedor`; cada linha do DRE com detalhamento até o registro.
- Testes: massa com duas compras do mesmo produto a custos diferentes, venda antes e depois da segunda compra (custo de cada uma pela data), produto sem histórico ("custo do cadastro"), devolução de peça boa (custo volta), falta cobrada e falta que a loja assume, avaria, comissão estornada em acerto seguinte, lançamento manual de saída e de entrada, pagamento a fornecedor e recebimento do acerto (os dois fora do DRE). Soma do lucro por produto = receita líquida − custo do DRE; soma do lucro por vendedor = a mesma menos as comissões.
- Gestão: telas no grupo Relatórios financeiros; o custo só aparece aqui e só na Gestão.

### 8.7: painel semanal (consulta)

- ADR-0015, item 5. `GET /api/dona/painel-semanal?semana=...&vendedor=&praca=&situacao=` (filtros da referência: semana, vendedor, praça, situação) com as duas partes: **Pedidos** (linhas) e **Totais** (blocos).
- A comissão e o líquido das linhas sem acerto fechado vêm da **prévia** pelo mesmo cálculo do fechamento (o mesmo serviço da prévia da dona; nada gravado), marcados como prévia. Não há "entrada", "cheque" nem "remarcadas".
- Testes: massa de uma semana com um vendedor acertado acima da meta, outro abaixo, um com maleta em aberto (prévia) e uma reposição; cada número feito à mão; as linhas acertadas batem com o relatório de acertos da 7.12 (mesma fonte); enviado = vendido + devolvido + falta em peças em cada maleta acertada; Total recebido = recebimentos por forma da 7.12 na semana.

### 8.8: painel semanal (tela)

- Na tela inicial da dona, botão grande **Painel da semana**; tela com barra de atalhos no topo (Nova maleta, Acerto, Reposição, Troca, Vendedores, Estoque), filtros (semana, vendedor, praça, situação) e as abas **Maletas** e **Totais**, na organização da referência (§2).
- Detalhamento: clicar na linha abre o acerto ou a maleta; clicar num total abre os registros. Três moedas. Exportação PDF, Excel e CSV.
- A tela só mostra o que a API devolve.

### 8.9: controle

- Relatórios da §12 (Controle), só leitura, só a dona: **Auditoria** (quem, quando, o quê, antes e depois, filtros por período, pessoa, ação e registro), **Sincronizações e computadores** (computadores de cada vendedor, último envio e último recebimento, envios com problema e em quarentena), **Acessos** (entradas, tentativas erradas, bloqueios).
- Conferir que as ações da §11 (preço, estoque, desconto, cancelamento, reembolso, autorização de exceção e liberação de restrição) são registradas na auditoria; a que faltar ganha o registro no **mesmo serviço e na mesma transação**, sem mudar regra (lista no PR).
- Retenção: guardar tudo (padrão; a limpeza técnica de hoje continua só com o que já apaga).
- Antes e depois em texto da loja (nome do campo do glossário), sem mostrar senha nem token.

### 8.10: Corona Vendas: pedir o acerto e bipar as sobras

- Decisão acima. Em "Meu acerto e minha comissão": botão **Pedir acerto** (com internet; as condições da 7.4 valem e o erro do servidor aparece em português simples) e, com o acerto em andamento, **Contar as sobras** com o leitor (UUID por leitura gerado no computador, desfazer leitura, contagem por peça e estado). Só o vendedor dono; o vendedor não fecha nem desiste.
- Sem rota nova, sem migração, sem outbox. Se faltar algo na rota do vendedor, a tarefa **para** e registra no PR.
- Testes do Corona Vendas e um cenário de duas pontas (pedir, bipar, a dona fecha); `npm run barreira:varrer` passa.

### 8.11: acerto na Gestão: prévia tipada

- A prévia do fechamento (`GET /api/dona/acertos/{id}/fechamento`) passa de mapa sem tipo para um registro com tipo no OpenAPI, **sem mudar o JSON** (teste que compara a resposta antes e depois na mesma massa) e com `faltaParaMetaGuarani` e o equivalente em Real (mesma conta da prévia do vendedor).
- A tela de conferência do acerto mostra quanto falta para a meta. Cliente da dona regenerado; o Corona Vendas não muda.

### 8.12: Playwright da dona

- Um cenário em `testes-e2e/`: massa criada pela API (vendedor, maleta, venda), a dona abre o Corona Gestão, começa o acerto, conta, confere e fecha, e vê a ficha. Roda à mão e no workflow semanal; **não** entra no CI de cada PR.

### 8.13: fechamento da Fase 8

- Teste de ponta a ponta: uma semana completa cujo painel, DRE, curva ABC e relatórios de vendas batem entre si, e a rodada da conferência termina `SEM_DIFERENCA`; depois um dado estragado de propósito faz a rodada seguinte avisar.
- Roteiro do teste de uso (comparar o painel e o DRE com as planilhas da dona numa semana real, §13 item 7), especificação atualizada (§2 com as definições de Enviado, Devolvido e Vendido, §12, §13, §15), ADR-0015 conforme a decisão do responsável, `fase-8.md` e `PROGRESSO.md`.

## Testes obrigatórios

**Dinheiro e arredondamento**

- Soma em `NUMERIC` no banco; HALF_UP só em `Dinheiro`; o teste de guarda continua passando com o código novo.
- Ticket médio: R$ 100,00 em 3 vendas = 33,33; percentuais da curva ABC com `Dinheiro`.
- Custo da linha = custo unitário × quantidade, exato; custo pela data da venda (antes e depois de uma compra nova); "custo do cadastro" marcado.
- Nenhum valor convertido gravado; Dólar e Guarani pela cotação do dia de cada lançamento, total = soma das linhas convertidas.

**Uma fórmula só**

- Painel × relatório de acertos (7.12): mesmas linhas, mesmos valores.
- DRE × lucro por produto × lucro por vendedor: somas iguais no mesmo período.
- Curva ABC × relatório de vendas por produto: mesmo vendido líquido.
- Comissão prevista no painel × prévia do vendedor (`/api/vendedor/comissao/previa`) na mesma massa: mesmo valor.

**Conciliações e conferência diária**

- C11 e C12 zeradas na massa completa e acusando cada dado estragado de propósito.
- Rodada: sem diferença, com diferença (dado estragado), uma conferência com erro forçado (`FALHOU`, as outras rodam), duas rodadas pedidas ao mesmo tempo (uma só roda), "Conferir agora" idempotente (201/200/409).
- `rodadas_conferencia` e `diferencas_conferencia` recusam `UPDATE` e `DELETE` no banco.
- Aviso: aparece com diferença, com falha e sem rodada há mais de 26 horas (relógio avançado no teste); some quando a última rodada não tem diferença.

**Painel**

- Números feitos à mão para acertado acima da meta, abaixo da meta, maleta em aberto (prévia) e reposição; enviado = vendido + devolvido + falta em peças nas maletas acertadas; nenhuma "entrada", "cheque" ou "remarcada" na resposta nem na tela.

**Permissões e barreira**

- 401 e 403 cruzado em todas as rotas novas; nenhuma resposta desta fase vai ao vendedor, salvo a 8.10.
- O vendedor nunca recebe custo, lucro, DRE, auditoria nem dados de outro vendedor; `npm run barreira:varrer` passa na 8.10.

**Corona Vendas (8.10)**

- Pedir e contar só com internet; sem internet, a mensagem explica; nada vai à outbox; a leitura repetida com o mesmo UUID não conta duas vezes.

## Dúvidas para a dona

**Nenhuma pergunta nova.** A dona pediu para não receber mais perguntas, e nada desta fase precisa dela para andar: os números novos são leitura do que já está gravado, com os padrões seguros do [ADR-0015](../decisoes/ADR-0015-numeros-gerenciais-e-conferencia-diaria.md). A confirmação de que o painel e o DRE batem com o que ela usava é feita no **teste de uso**, comparando com as planilhas dela numa semana real (§13, item 7), sem pergunta: diferença achada vira tarefa.

As dúvidas antigas da Fase 7 continuam com o padrão seguro do [plano da Fase 7](fase-7-plano.md#dúvidas-para-a-dona).

**Para o responsável** (cada uma com o padrão que vale até a resposta):

- **R1 (ADR-0015, item 1):** custo da peça vendida pelo último custo de compra até a data da venda, calculado na leitura (padrão), ou tabela só do servidor com o custo congelado na venda (mexe no caminho da venda e do push).
- **R2 (ADR-0015, item 6):** rodada às 03:00 de Brasília e aviso depois de 26 horas sem rodada (padrão).
- **R3 (§7.4):** pedir o acerto e bipar as sobras no Corona Vendas só com internet (padrão, tarefa 8.10), ou mudar o texto da §7.4 para "a dona conta" e não fazer a 8.10.
