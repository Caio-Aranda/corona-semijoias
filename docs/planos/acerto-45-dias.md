# Plano: acerto do representante a cada 45 dias, com a meta contada no período

Pedido da dona (Lucia), 25/11/2026: "Los días de rendición con los revendedores es de 45 días" ("por el momento": o número
tem de ser configurável pela dona, como a meta e os percentuais). Comissão: **40%** se o vendido no período chegar a
**Gs. 700.000**, **30%** abaixo disso, sobre o total vendido. O contrato de consignação já redigido
(borrador, fora do repositório) diz o mesmo: acerto "cada 45 días corridos, contados desde
la entrega de la maleta, en la fecha que fije LA EMPRESA"; comissão "sobre el total vendido en el período de cada acerto";
as peças vendidas "se reponen dentro de los 5 días siguientes".

Parte crítica (acerto e financeiro): este documento é **só o plano**, sem código de produção. Regra proposta na
especificação v1.38 (§7.4 e §23.1, marcadas como proposta) e no **ADR-0012, adendo de 25/11/2026 (proposta)**.

## O que achei antes de planejar (muda o tamanho da tarefa)

1. **Há dois "acertos" no sistema, e o da meta está desligado.** O acerto da §7.4 (representante com a loja, comissão por
   meta 30%/40% em Guarani, repasse em dinheiro, PIX ou boleto) está **escondido** desde 18/11 (§23.1 item 2; ADR-0016
   item 15; configuração `acerto_do_vendedor_visivel = 0`). O acerto que está em uso é o da §23.7: **por nota, entre o
   representante e o cliente dele**, com comissão do cadastro do cliente (padrão 30%, até 40%) e **Data do retorno = saída
   + 45 dias** (já existe).
2. **O acerto antigo soma `corona.vendas`, que o Corona Vendas novo não grava mais.** O fechamento da §7.4
   (`MontagemDoFechamento`, `acerto_eventos`, `comissoes`) só conhece `VENDA`, `ESTORNO_VENDA` e `DEVOLUCAO` com
   `venda_id NOT NULL`. Desde a 9.2 o app grava **notas do consignado** (`notas_consignado`, `acertos_nota`, `valor_vd`).
   Religar a §7.4 como está daria **vendido zero** para todo representante que usa o app 1.2.0.
3. **A "semana" da §7.4 já não manda na meta.** A prévia e o fechamento somam "todo evento do vendedor **ainda sem
   acerto**" (ADR-0012 item 4). O que é semanal é só: o índice único `acertos (vendedor_id, semana_id)`, a trava "semana
   fechada" (caixa, maleta, acerto) e os textos. Ou seja: a meta "no período do acerto" é a regra que **já existe**; falta
   o período ter começo, duração e aviso.
4. **A `semana` da dona (`corona.semanas`) é global** (uma numeração para todos, sem sobreposição), usada também pelo
   caixa e pelo **Fechar semana** (V046, travas `FOR SHARE`). A "Semana" do Corona Vendas (`semanas_vendedor`, §23.2) é
   outra coisa: o ciclo do representante na praça, que numera notas e recibos.
5. O contrato aceita **tarjeta**; a §7.4 recusa cartão no repasse. Fica como dúvida (P7).

**Leitura do pedido (suposição S1, a confirmar com P1):** o contrato é entre a **Empresa** e a **Representante** (quem usa
o Corona Vendas e recebe a maleta). As "revendedoras" da frase da dona seriam as representantes. Se a dona quis dizer as
**clientes** (§23), o plano é outro e muito menor: ver o Apêndice B.

## Resumo das decisões

| # | Decisão | Por quê |
| --- | --- | --- |
| D1 | **Não** renomear `semanas` nem criar um "ciclo" global. O período é **do representante**: começa no envio da primeira maleta dele que ainda não entrou num acerto (ou no dia seguinte ao último acerto registrado) e vai até o acerto. A data prevista é o início + N dias corridos (configuração, padrão 45). | O contrato conta "desde la entrega de la maleta", e cada representante recebe a maleta num dia diferente; `semanas` é global, tem travas do caixa e é sincronizável: renomear ou mudar a duração mexe em V010, V021, V043, V044 e V046 sem ganho. |
| D2 | A data prevista é **aviso, não trava**. O acerto pode ser registrado antes ou depois; passou do dia, a tela avisa "atrasado". | "En la fecha que fije LA EMPRESA". Trava de data quebraria o acerto feito no dia que a dona marcou. |
| D3 | O período é **calculado**, não gravado, enquanto está aberto; mudar N muda a data prevista dos períodos abertos. O acerto registrado grava início, fim, N usado e a data prevista. | Nada fechado muda (o fechado guarda o seu); o aberto não tem nada gravado para ficar inconsistente. |
| D4 | O "vendido para a meta" passa a vir do **consignado**: o `valor_vd` de cada `acertos_nota` do representante **ainda sem acerto do representante**, não estornado, convertido para Guarani pela **cotação gravada no próprio acerto de nota** (`acertos_nota.cotacao_id`, a da Dt. Acerto; ADR-0006), cada um arredondado sozinho; a soma é a dos convertidos. | Mesma regra do ADR-0012 item 5, trocando "venda" por "acerto de nota" (é onde o vendido existe hoje). O vínculo "ainda sem acerto" (e não uma janela de datas) mantém a regra de corte do item 4: acerto de nota que chega atrasado sem internet cai no **próximo** período, nunca muda um fechado. |
| D5 | Comissão **por acerto de nota** (uma linha por acerto de nota, arredondada uma vez por linha, percentual gravado na linha). Estorno de acerto de nota que já entrou num acerto fechado gera linha `ESTORNO` no **próximo** acerto, com o **percentual da linha original**; não entra na soma da meta do próximo. | Igual ao item 5 de hoje; permite o estorno proporcional sem reabrir nada. |
| D6 | Tabelas **novas** para o acerto do representante sobre o consignado (`acertos_representante`, `acertos_representante_eventos`, `comissoes_representante`), imutáveis. A `acertos`/`acerto_eventos`/`comissoes` da §7.4 ficam como estão (dados antigos, rotas antigas). | `acerto_eventos.venda_id` e `comissoes.venda_id` são `NOT NULL` com FK para `vendas`; afrouxar isso numa migração nova mexe nas conferências do `COMMIT` do 7.5/7.6. Tabela nova não toca nada gravado. |
| D7 | Configuração nova `acerto_dias_do_periodo` (inteiro, 1 a 365, padrão 45), na mesma tela e rota da meta (`/api/dona/configuracoes/comissao` ganha o campo; auditoria `COMISSAO_ALTERADA`). | Pedido "por el momento": a dona muda sozinha, como a meta. |
| D8 | **Nada muda no Corona Vendas 1.2.0 nem no sync** na primeira leva: sem tabela nova no pull, sem tipo novo na outbox, sem campo novo obrigatório. A prévia para o representante (segunda leva) é **online**, pela rota que já existe (`GET /api/vendedor/comissao/previa`, campos só acrescentados). | O 1.2.0 está em campo; o pull de tabela desconhecida e a outbox são o ponto de maior risco (ADR-0013). A prévia já é "precisa de internet" hoje. |
| D9 | Os textos trocam "semana" por "**período do acerto**" / "**acerto**" onde falam da meta: "Meta do acerto", "Vendido no período", "Próximo acerto: DD/MM (faltam X dias)". A "Semana" do Corona Vendas (§23.4) e a semana do caixa **não** mudam de nome. | Linguagem da loja; só muda o que deixou de ser semanal. |

## 1. Modelo

### 1.1 Período do representante (calculado)

- **Início** = o maior entre (a) o dia seguinte ao `fim` do último `acertos_representante` não estornado do representante
  e (b) o dia do envio da maleta mais antiga do representante ainda `ENVIADA`/`CONFERIDA` (Brasília). Sem acerto anterior:
  só (b). Sem maleta e sem acerto: sem período ("Sem maleta com o representante").
  - Dúvida P5/P6: se a maleta **continua** com o representante depois do acerto (o contrato fala em repor o vendido), (b)
    deixa de ser útil depois do primeiro acerto, e vale (a). A regra acima já cobre os dois casos.
- **Data prevista** = início + `acerto_dias_do_periodo` dias corridos. **Dias que faltam** = prevista − hoje (Brasília);
  negativo = atrasado.
- **Vendido no período (Gs)** = soma da D4. **Percentual que valeria hoje** = 40 se soma ≥ meta, senão 30 (valores de
  `configuracoes` no instante). **Comissão prevista (R$)** = soma por linha da D5.

### 1.2 Acerto do representante (registrado, segunda parte da primeira leva)

O acerto em dinheiro continua **fora do sistema** até a segunda leva (§23.1 item 2). Para ter histórico, comprovante e
começo do próximo período, a dona **registra** o acerto do período:

- `acertos_representante`: `id` (UUID do app), `vendedor_id`, `inicio`, `fim` (o dia do registro ou um dia passado,
  nunca futuro, nunca antes do início), `dias_do_periodo`, `data_prevista`, `meta_guarani`, `vendido_guarani`,
  `percentual`, `vendido` (R$, soma do `valor_vd`), `comissao` (R$), `cotacao_id` (a mais recente no registro, só para
  mostrar), `registrado_por`, `registrado_em`, e o estorno (`estornado_em`, `estorno_id`, `motivo_estorno`). Imutável;
  corrigir = estornar (com motivo) e registrar de novo. Um não estornado por (vendedor, fim). Um período não se sobrepõe a
  outro não estornado do mesmo representante.
- `acertos_representante_eventos`: `origem_tipo` (`ACERTO_NOTA`, `ESTORNO_ACERTO_NOTA`), `origem_id` (o acerto de nota),
  `valor` com sinal, `cotacao_id`, `valor_guarani`; `UNIQUE (origem_tipo, origem_id)` entre os não estornados (um acerto
  de nota entra em um acerto do representante só).
- `comissoes_representante`: uma linha `COMISSAO` por `ACERTO_NOTA` e uma `ESTORNO` por `ESTORNO_ACERTO_NOTA`
  (`estorna_id`, percentual da linha original), `base`, `percentual`, `valor`.
- Conferido pelo banco no `COMMIT` (como no 7.5): `vendido` = soma dos eventos; `comissao` = soma das linhas;
  `vendido_guarani` = soma dos `valor_guarani` das linhas `ACERTO_NOTA`; percentual coerente com a meta gravada.
- **Estornar o acerto do representante** libera os acertos de nota dele para o próximo registro (os eventos do estornado
  deixam de contar no único).
- Não muda maleta, estoque, caixa, contas a receber nem recebimentos. Não é sincronizado.

### 1.3 O que não muda

`semanas`, `fechamentos_semana`, `acertos` da §7.4 e as travas do caixa; `semanas_vendedor`, notas, acertos de nota,
recibos e a comissão do cliente (§23.7/§23.8); o Fechamento da maleta (§23.10); o pull e a outbox.

## 2. Migração Flyway nova (V060; nenhuma publicada é editada)

`V060__acerto_do_representante_por_periodo.sql`:

1. `INSERT` em `configuracoes` da chave `acerto_dias_do_periodo` (`valor_inteiro = 45`), com `CHECK` 1..365 na rota (e
   no banco, se o padrão das outras chaves inteiras tiver).
2. As três tabelas da 1.2, com `aplicar_convencoes_do_servidor(..., editavel => false)` (só do servidor, imutáveis, fora
   do sync), as conferências no `COMMIT` (gatilhos `DEFERRABLE INITIALLY DEFERRED`, como V044/V045) e o
   `REVOKE ALL ... FROM PUBLIC`.
3. Nenhum `UPDATE` em dado existente.

**Dados existentes:** os acertos da §7.4 (`acertos`), fechados ou não, **não mudam**; continuam lidos pelas telas antigas
(escondidas). Os acertos de nota já gravados entram no **primeiro** acerto do representante que a dona registrar (são
"ainda sem acerto"). Se a dona não quer que o histórico de antes de hoje conte na primeira meta, ela registra um acerto do
período "de abertura" com fim ontem (dúvida P8). **Acertos `EM_ACERTO` da §7.4:** conferir no servidor de teste antes do
deploy (`SELECT count(*) FROM corona.acertos WHERE status = 'EM_ACERTO'`); se houver, a dona desiste pela rota antiga.
Não há dado real em produção da §7.4 (escondida desde 18/11).

## 3. Efeitos

- **Meta (ADR-0006):** Guarani de cada acerto de nota pela cotação dele (`pyg_venda`), cada um arredondado
  (`ConversaoDeMoeda.paraGuarani`); acerto de nota sem `cotacao_id` usa a que valia no dia da Dt. Acerto (regra do
  ADR-0006); a soma é dos convertidos. "Falta para a meta" em Guarani e em Real pela cotação mais recente, só para
  mostrar (como na 8.11). Mudar a meta, os percentuais ou N depois não muda o registrado.
- **Estornos:** estorno de acerto de nota (§23.7 item 6) ainda sem acerto do representante → os dois saem juntos (não
  entram); já dentro de um acerto registrado → `ESTORNO` no próximo, percentual da linha original. Estorno do próprio
  acerto do representante → nada some, a linha nova desfaz.
- **Sync / Corona Vendas 1.2.0:** nada muda (D8). Na segunda leva, a tela de prévia do representante sai numa versão nova
  (1.3.0, `vendas:publicar`); o 1.2.0 continua funcionando sem ela. A rota da prévia só **ganha** campos
  (`inicioDoPeriodo`, `dataPrevista`, `diasQueFaltam`, `diasDoPeriodo`); os nomes atuais continuam com o mesmo sentido.
- **Telas da dona (Corona Gestão):**
  - Comissão e meta → **Acerto, comissão e meta**: campo novo **Dias entre um acerto e outro** (padrão 45). Ajuda: "Vale
    para os períodos em aberto e os próximos. Acerto já registrado não muda."
  - Ficha do representante e lista de representantes: **Próximo acerto: DD/MM/AAAA (faltam X dias)** / **Acerto atrasado
    há X dias**; **Vendido no período: Gs. X de Gs. 700.000**; **Comissão que valeria hoje: 30%/40% (R$ Y)**; botão
    **Registrar acerto do período** (confirmação: "Registrar o acerto de {representante} de DD/MM a DD/MM? Depois de
    registrado, não muda: para corrigir, estorne."), **Estornar acerto** (com motivo).
  - Painel da semana: coluna **Próximo acerto** por representante (só leitura). O painel continua semanal (é das maletas).
  - Os textos da meta de hoje ("Meta da semana", "Vendido na semana", "Minha comissão desta semana") passam a "Meta do
    acerto", "Vendido no período", "Minha comissão deste período", nas telas antigas também (mesmo escondidas).
  - Espanhol (padrão da dona): "Próxima rendición / Acerto: DD/MM (faltan X días)", "Vendido en el período", "Meta del
    acerto", "Días entre un acerto y otro", "Registrar acerto del período". Usar "acerto" como já está no glossário ES.
- **Relatórios:** "Acertos por vendedor e semana" e "Comissões por vendedor e semana" continuam para os dados antigos;
  novo **Acertos dos representantes** (um registrado por linha: período, dias, vendido R$ e Gs, meta, percentual,
  comissão, situação), em PDF, Excel e CSV, nos dois idiomas, e o **Comprovante do acerto** (PDF, para assinar; o
  contrato fala em "comprobante del acerto emitido por el sistema"): representante, período, notas acertadas no período
  (nota, cliente, VD), vendido em R$/US$/Gs, meta, percentual e comissão. O "quanto a representante paga" entra só depois
  da P3.
- **Glossário (`docs/glossario-interface.md`):** entram **período do acerto**, **próximo acerto**, **acerto do
  representante**; a linha "semana — maleta, cobrança e acerto são semanais" passa a dizer que o acerto não é mais
  semanal.
- **Conciliações (§13):** novas, no relatório de conferência: (1) cada acerto de nota não estornado está em no máximo um
  acerto do representante não estornado; (2) `comissao` do acerto = soma das linhas; (3) `vendido` = soma dos `valor_vd`
  dos eventos; (4) a prévia de hoje = soma dos acertos de nota "ainda sem acerto" (mesmo cálculo do registro). As
  conciliações antigas não mudam.
- **Permissões:** registrar e estornar, só a dona (ADMIN), com UUID do comando (idempotente: 201/200/409 como o fechar
  semana). O representante só lê a prévia dele (segunda leva), nunca a de outro.

## 4. Tarefas, em ordem (testes primeiro nas regras de dinheiro)

**Leva 1A — não depende das respostas P2–P6 (só da P1).**

1. **Testes primeiro** (`PeriodoDoRepresentanteTest`, `MetaDoPeriodoTest`): início pela maleta e pelo último acerto;
   data prevista com N = 45 e N mudado; atrasado; soma em Gs com três acertos de nota de cotações diferentes (cada um
   arredondado, a soma dos convertidos ≠ conversão da soma); exatamente 700.000 → 40%; 699.999 → 30%; acerto de nota
   estornado sai; acerto de nota de outro representante não entra.
2. V060 parte 1 (configuração) + `ChaveDeConfiguracao.ACERTO_DIAS_DO_PERIODO` + a rota da comissão com o campo (1..365,
   mensagem "Use de 1 a 365 dias.") + auditoria.
3. Serviço do período e da prévia sobre o consignado; rota da dona `GET /api/dona/representantes/{id}/periodo-do-acerto`
   e o campo na lista. A rota do vendedor ganha os campos novos (só acrescentar).
4. Corona Gestão: campo na tela de comissão, ficha/lista do representante, coluna do painel, textos PT/ES e glossário.

**Leva 1B — depois de P2, P4, P5 e P8.**

5. **Testes primeiro** (`AcertoDoRepresentanteTest`): exemplo numérico fixo (vira arquivo em `packages/shared/exemplos/`,
   como o `consignado-acerto.json`); registro idempotente (mesmo id duas vezes, duas threads → um registro); acerto de nota
   que chega atrasado depois do registro cai no próximo; estorno de acerto de nota já registrado vira `ESTORNO` no
   próximo com o percentual original; estornar o acerto do representante libera os acertos de nota; período sobreposto e
   fim no futuro recusados; conferências do `COMMIT` pegando cada soma errada.
6. V060 parte 2 (tabelas), serviço de registrar/estornar, rotas da dona, conciliações novas.
7. Corona Gestão: Registrar/Estornar, relatório **Acertos dos representantes** e **Comprovante do acerto** (PT/ES).
8. `npm run api:test`, `lint`, `typecheck`, `format:check`, e o roteiro de teste à mão no servidor de teste.

**Leva 2 — dinheiro no sistema (precisa de P3, P7 e de um plano próprio, parte crítica).**

- O repasse do representante à loja dentro do acerto registrado (recebimentos, caixa, o que ficou devendo, crédito),
  reaproveitando o que a 7.6 fez para `acertos` (recebimentos ligados a `acertos_representante`), e o cartão se a P7
  disser sim.
- Contagem da maleta no acerto (esperado × contado) sem fechar a maleta, se a P5 disser que a maleta continua; falta =
  pendência do representante.
- Corona Vendas 1.3.0: **Meu acerto** com a prévia (online), próximo acerto e histórico; textos PT/ES.
- Decidir o destino da §7.4 escondida (apagar do menu de vez ou deixar como está).
- "Acertos seguidos sem meta" (cláusula quarta do contrato) como aviso na ficha, se a dona quiser.

## 5. Dúvidas que só a dona responde (para mandar em espanhol simples)

- **P1.** El acerto cada 45 días y la comisión de 30%/40% ¿son para la **representante** (la que usa Corona Vendas y tiene
  clientes) o para las **clientes** que revenden? *(Padrão: representante.)*
- **P2.** La comisión de la representante ¿es sobre lo **vendido a precio de etiqueta** por todas sus clientes en el
  período? *(Padrão: sí, el VD.)*
- **P3.** La comisión de las clientes (30%) ¿la paga la **representante** de su parte o la **empresa**? O sea: en el
  acerto, ¿la representante le paga a usted lo vendido menos el 30%/40% de ella, o menos también la comisión de las
  clientes?
- **P4.** Las piezas que están **con clientes** el día del acerto (todavía no volvieron) ¿quedan para el próximo acerto?
  *(Padrão: sí.)*
- **P5.** Después del acerto ¿la **maleta sigue** con la representante y usted repone lo vendido, o la maleta vuelve y se
  arma una nueva? *(Padrão: sigue y se repone, como dice el contrato.)*
- **P6.** Si una representante tiene **dos maletas** ¿es un solo acerto para las dos, contando desde la primera?
  *(Padrão: sí, uno solo.)*
- **P7.** El contrato acepta **tarjeta**. ¿La representante le puede pagar a usted con tarjeta en el acerto?
- **P8.** Las ventas de antes de hoy ¿cuentan para la meta del **primer** acerto de 45 días, o empezamos a contar desde
  hoy? *(Padrão: desde la maleta que ella tiene ahora.)*
- **P9.** Si el acerto se hace antes o después de los 45 días ¿la meta sigue siendo Gs. 700.000? *(Padrão: sí.)*

## Apêndice A. Alternativas descartadas

- **Renomear `semanas` para "período" com duração configurável.** É global (um calendário para todos), sincronizável, e
  carrega as travas do caixa e do Fechar semana; não tem como cada representante ter o seu dia de início. Risco alto,
  ganho nenhum para a meta, que já é "ainda sem acerto".
- **Gravar o prazo na maleta (envio + 45).** A reposição cria outra maleta (ADR-0012, F1) e o contrato fala em um acerto
  para a representante; o período ficaria com vários finais. Usar a maleta só para achar o início (D1) resolve sem coluna
  nova.
- **Religar a §7.4 trocando o índice semanal.** O vendido dela vem de `vendas`, que o app não grava mais (achado 2).
- **Janela de datas pela Dt. Acerto** (em vez de "ainda sem acerto"). Um acerto de nota sem internet que chegasse
  depois mudaria um acerto já registrado; quebra a imutabilidade e a regra de corte.

## Apêndice B. Se a P1 disser "as clientes"

O período já é o da nota (saída → acerto, Data do retorno = saída + 45). Bastaria: (1) a configuração de dias também
alimentar a Data do retorno sugerida (hoje fixa em 45 no app: o pull levaria a chave, formato novo, e o 1.2.0 seguiria com
45); (2) a Comissão % do acerto da nota passar a ser decidida pela meta (VD da nota em Guarani pela cotação da Dt. Acerto
≥ meta → 40%, senão 30%) no `packages/shared/consignado` (offline) e no servidor, que recalcula; o 1.2.0, que manda a
porcentagem do cadastro, geraria **alerta de divergência** (nunca recusa, §23.8) até trocar para a versão nova. Seria
outro plano, curto, com o exemplo da nota `0000064-21` refeito.
