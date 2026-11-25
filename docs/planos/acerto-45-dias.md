# Plano: acerto da revendedora a cada 45 dias, com a comissão pela meta

Pedido da dona (Lucia), 25/11/2026: "Los días de rendición con los revendedores es de 45 días" ("por el momento": o número
fica configurável). Comissão de **40%** se o vendido chegar a **Gs. 700.000**, **30%** abaixo disso, sobre o total
vendido. **Resposta da P1 (25/11, 15:23–15:25):** o contrato é com a **revendedora**, a pessoa que fica com as peças e
responde por elas. No sistema, ela é o **Cliente** do consignado (§23). Quem usa o Corona Vendas é quem entrega as peças
às revendedoras.

Parte crítica (dinheiro do acerto): este documento é **só o plano**, sem código de produção. Regra proposta na
especificação v1.38 (§23.2, §23.6, §23.7, §23.8, §23.11 e a nova §23.13, marcadas como proposta) e no **ADR-0016, adendo
de 25/11/2026 (proposta)**. O acerto escondido da §7.4 **continua escondido e não muda** (nem a meta "da semana" dele).

## O que já existe e o que muda

| Hoje (§23) | Proposta |
| --- | --- |
| Data do retorno = saída + **45** dias, fixo no código do Corona Vendas (`DIAS_PARA_COBRANCA`), editável na tela. | Saída + **N** dias, N configurável pela dona (padrão 45). Continua editável na nota. |
| Comissão % vem do cadastro do cliente (padrão 30%, de 0% a 40%) e o representante pode mudar no acerto, até 40%. O app manda a porcentagem; o servidor só confere a faixa. | A comissão do acerto da nota é **decidida pela meta**: o VD da nota em Guarani ≥ meta → 40%, senão 30%. O servidor decide; o app mostra a mesma conta e não deixa digitar. |
| Nada em Guarani é gravado no acerto da nota (só `cotacao_id`). | O acerto da nota grava a meta usada, o VD em Guarani e a regra (`META` ou `INFORMADA`). |

O "período do acerto" é **a própria nota**: da saída ao acerto. Não precisa de tabela de período nem de acerto novo.

## Resumo das decisões

| # | Decisão | Por quê |
| --- | --- | --- |
| D1 | Meta **por nota**: `vd_guarani = paraGuarani(valor_vd, cotação do acerto da nota)`, uma conversão só (a nota tem uma cotação só), arredondada como em `ConversaoDeMoeda.paraGuarani`. `vd_guarani ≥ meta` → percentual na meta; senão → percentual abaixo. Base = **VD** (a preço da maleta, sem pendências anteriores). | "Total vendido en el período del acerto" = VD da nota. Uma cotação → uma conversão, sem a soma linha a linha do ADR-0012. |
| D2 | A cotação é a **do acerto da nota** (`acertos_nota.cotacao_id`, a vigente na Dt. Acerto, ADR-0006), não a da saída. | O VD só existe no acerto, e é nesse dia que a revendedora paga. O Relatório do acerto já mostra Guarani por essa cotação, então o número da meta é o mesmo que ela vê no papel. *(Interpretação de "cotação da nota"; a orquestradora confirma.)* |
| D3 | O **servidor decide** o percentual na operação nova (`ACERTO_NOTA` **formato 2**, sem porcentagem). O app calcula o mesmo com a cotação local para mostrar. Se a conta do servidor der diferente (cotação nova da dona que o app ainda não tinha, perto da meta), **vale a do servidor**, a operação é aceita e vira **alerta** pelo `visto` que já existe. | "O servidor recalcula comissões; nunca confia no app" e "o que aconteceu sem internet nunca é recusado em silêncio" (§23.8). |
| D4 | O **formato 1** (app 1.2.0) continua aceito **como hoje**: a porcentagem mandada (0 a 40), regra `INFORMADA`. | O 1.2.0 imprime o Relatório do acerto com a porcentagem dele; mudar no servidor faria o papel da revendedora não bater com o sistema. O formato 1 sai pela regra dos 90 dias da outbox depois que todos os computadores estiverem no 1.3.0. |
| D5 | Configuração **própria do consignado** (quatro chaves novas): `consignado_meta_guarani` (700.000), `consignado_comissao_abaixo_da_meta` (30), `consignado_comissao_na_meta` (40), `consignado_dias_do_retorno` (45). Percentuais de 0 a **40** e abaixo ≤ na meta; dias de 1 a 365; meta de 1 a 1.000.000.000. Auditoria `COMISSAO_ALTERADA`. | As chaves da §7.4 são do acerto escondido (tela e textos "da semana"); reaproveitar amarraria as duas regras. O teto de 40% é o `CHECK` de `acertos_nota` (V051) e da §23.8: não muda. |
| D6 | As quatro chegam ao Corona Vendas nos **`parametros` do pull** (`ParametrosDoVendedor`, lista explícita do ADR-0013 item 4), só acrescentando campos. Sem tabela nova no pull. | É o caminho que já leva prazos e limites para o app sem internet; o 1.2.0 ignora campo novo (a conferir num teste). |
| D7 | Mudar a meta, os percentuais ou os dias **não muda nada gravado**: acerto feito guarda o seu; nota já finalizada guarda a sua Data do retorno. Corrigir acerto continua sendo estornar e fazer de novo (§23.7 item 6), e o acerto novo usa a configuração do momento dele. | Imutabilidade. |
| D8 | Corona Vendas **1.3.0** é necessário: Data do retorno pelos dias da configuração, Cobrança com a comissão pela meta (sem campo para digitar), formato 2, Relatório do acerto com a linha da meta. Sem `parametros` novos (servidor antigo), o app usa 45 / 30 / 40 / 700.000. | O app calcula sem internet; sem a versão nova, a revendedora vê no papel uma porcentagem que não é a do contrato. |
| D9 | O campo **Comissão %** do cadastro do cliente deixa de valer para o acerto **depois da resposta da P2**. Até lá, o cadastro fica como está (a coluna continua no banco em qualquer caso). | Se a dona tem revendedora com comissão especial, a regra precisa de exceção; não apagar antes de saber. |

## 1. Migrações (duas pequenas, uma por issue; nenhuma publicada é editada)

`V060__configuracao_da_comissao_do_consignado.sql` (issue 2): `INSERT` das quatro chaves em `configuracoes`, com os
valores padrão acima.

`V061__meta_no_acerto_da_nota.sql` (issue 3):

1. `ALTER TABLE corona.acertos_nota ADD COLUMN regra_da_comissao text NOT NULL DEFAULT 'INFORMADA'` com
   `CHECK (regra_da_comissao IN ('INFORMADA', 'META'))`, `ADD COLUMN meta_guarani bigint` e
   `ADD COLUMN vd_guarani bigint`.
2. `CHECK`: com `META`, `meta_guarani` e `vd_guarani` preenchidos e não negativos, e `comissao_percentual` coerente com
   o lado da meta (com `vd_guarani >= meta_guarani`, o maior dos dois percentuais; o banco não sabe quais eram os
   percentuais do dia, então essa parte fica na conciliação). Com `INFORMADA`, os dois vazios.
3. Os gatilhos de imutabilidade de `acertos_nota` passam a proteger também as três colunas novas (conferir o gatilho da
   V051 e recriar a função se ele lista colunas).
4. Nenhum `UPDATE` em dado existente: os acertos de nota já gravados ficam `INFORMADA`, com o Guarani vazio.

Se a V060 ou a V061 já estiverem tomadas quando a issue começar, use o próximo número livre.

**Dados existentes:** notas em aberto mantêm a Data do retorno que já têm. Acertos de nota já feitos não mudam. Uma nota
em aberto hoje, acertada depois da troca de versão, já entra na regra da meta.

## 2. Efeitos

- **Meta (ADR-0006):** uma conversão do VD pela cotação do acerto. Sem cotação nenhuma (servidor novo, sem BCB e sem a
  dona): fica `30%` (abaixo da meta), com alerta "Acerto sem cotação: comissão abaixo da meta". *(Padrão seguro; o
  servidor de teste sempre tem cotação.)*
- **Estorno do acerto da nota:** igual a hoje (desfaz retornos, recibo e aplicações). O acerto refeito decide a meta de
  novo, com a configuração e a cotação do momento dele.
- **Reposição:** na semana atual, as peças entram na mesma nota e contam para a meta dela; em semana anterior, nasce uma
  nota nova, e cada nota tem a sua meta (dúvida P3).
- **Remarcado:** só muda a Data do retorno; não toca na meta.
- **Sync e Corona Vendas 1.2.0:** o 1.2.0 continua funcionando como hoje (formato 1, D4). O servidor ganha o formato 2
  de `ACERTO_NOTA` e os campos novos em `parametros`; nada sai do pull.
- **Telas do Corona Vendas (1.3.0):**
  - **Novo Pedido:** Data do retorno = hoje + N (a ajuda diz "{N} dias depois da saída").
  - **Cobrança, aba Forma de Pagamento:** **Comissão %** só leitura, com a linha "Vendido nesta nota: Gs. X de Gs. 700.000
    (meta). Bateu a meta: comissão de 40%." ou "Ainda não bateu a meta: comissão de 30%. Faltam Gs. Y." A conta atualiza
    a cada peça bipada. Sem cotação no computador: "Sem cotação: a comissão é de 30% até a cotação chegar."
  - Alerta para a dona, quando o servidor decidir outra porcentagem: "A comissão do acerto da nota {n} ficou em {p}%
    (o computador mostrou {q}%), por causa da cotação."
- **Comprovante (Relatório do acerto, §23.11):** ganha, logo abaixo de Comissão, a linha **"Vendido em Guarani: Gs. X
  (cotação de DD/MM/AAAA). Meta: Gs. 700.000. Comissão: 40% (bateu a meta)."** Acerto feito pelo 1.2.0 ou de antes
  (regra `INFORMADA`): sem a linha.
- **Corona Gestão:**
  - Tela nova no grupo do consignado: **Comissão e meta das {revendedoras}** (meta em Guarani, comissão abaixo da meta,
    comissão na meta, dias para o retorno; ajuda: "Vale para os próximos acertos e notas. O que já foi feito não muda.").
  - Ficha da nota e lista de notas: coluna **Meta** ("Bateu", "Não bateu" ou vazio para `INFORMADA`) e o VD em Guarani.
  - "Semanas dos representantes" e o Fechamento da maleta: sem mudança de conta (a comissão já é a gravada); a coluna
    Comis. % continua.
- **Relatórios do Corona Vendas:** Situação do cliente e Peças vendidas por cliente sem mudança de conta; Totais da semana
  ganham **Notas que bateram a meta: X de Y**.
- **Conciliações (§13), novas:** (1) acerto `META`: `vd_guarani` = conversão do `valor_vd` pela `cotacao_id` da linha;
  (2) `comissao_percentual` igual ao percentual da configuração vigente no `registrado_em` (pela auditoria
  `COMISSAO_ALTERADA`) para o lado da meta em que `vd_guarani` caiu; (3) `INFORMADA` só de operação formato 1. As antigas não mudam.
- **Permissões:** só a dona muda a configuração. O representante lê os `parametros` (só os dele já vêm no pull).

## 3. "Revendedora" nas telas?

Hoje a tela diz **Cliente** (quem pega as peças para revender) e **Representante** (quem usa o Corona Vendas), decisão de
18/11 (§23.1 item 4). A dona chama a cliente de **revendedora** e diz que **representante** é ela mesma. Isso afeta os dois
apps, os dois idiomas, os relatórios impressos e o glossário, mas **só os textos** (o código e o banco continuam
`cliente` e `vendedor`).

Recomendação: trocar **Cliente → Revendedora** nas telas e nos papéis (inclusive na Nota de saída e no Relatório do
acerto, que a revendedora assina), e perguntar à dona o nome de quem usa o Corona Vendas antes de mexer em
"Representante". Fica como pergunta (P5 e P6) e como issue separada, por último, para não misturar com o dinheiro.

## 4. Issues para a conta 2 (em ordem; testes primeiro nas regras de dinheiro)

Todas com a etiqueta `conta-2`, PR em rascunho até passar localmente, `Closes #issue`. **Modelo e esforço: implementador
(Sonnet médio)** em todas.

1. **Comissão pela meta no cálculo compartilhado (TS e Java), testes primeiro.** Casos no arquivo de exemplos
   `packages/shared/exemplos/consignado-acerto.json`, lidos pelos dois lados: VD que dá exatamente Gs. 700.000 → 40%;
   Gs. 699.999 → 30%; o arredondamento do Guarani decidindo a meta; sem cotação → 30%; os percentuais e a meta vindos
   de parâmetros (não constantes). A nota `0000064-21` continua valendo para a regra `INFORMADA`; um caso novo, com a
   cotação fixa, para a regra `META`. Sem tela, sem migração. *Pré-requisito:* plano aprovado.
2. **Configuração da meta e dos dias do consignado (API).** V060 parte 1 (as quatro chaves), `ChaveDeConfiguracao`,
   `GET/PUT /api/dona/configuracoes/consignado` com as validações e a auditoria, e os quatro campos em
   `ParametrosDoVendedor`. Testes primeiro: limites recusados (percentual acima de 40, abaixo maior que na meta, dias
   0 e 366), auditoria, só a dona muda, vendedor recebe nos `parametros`. *Pré-requisito:* plano aprovado (pode correr
   junto com a 1).
3. **Servidor decide a comissão do acerto da nota (formato 2) e grava a meta.** V061 (colunas e `CHECK` da seção 1, a
   imutabilidade), `ACERTO_NOTA` formato 2 sem porcentagem, formato 1 como hoje, alerta de diferença pelo `visto`,
   conciliações novas. Testes primeiro: formato 2 nas duas pontas da meta; formato 1 inalterado; mesma operação duas
   vezes (sequencial e em duas threads) → um aceito e um `JA_RECEBIDO`; cotação da dona lançada depois da saída muda a
   decisão e vira alerta; estorno e acerto refeito com outra configuração. *Pré-requisito:* 1 e 2.
4. **Corona Gestão: tela Comissão e meta do consignado e coluna Meta nas notas.** Textos PT/ES, glossário. Testes de
   tela. *Pré-requisito:* 2 (a coluna, também a 3).
5. **Corona Vendas 1.3.0: dias do retorno e comissão pela meta na Cobrança.** Lê os `parametros` novos (com o padrão
   quando faltam), Novo Pedido com hoje + N, Cobrança com a comissão só leitura e a linha da meta, envia o formato 2.
   Testes primeiro no cálculo local e na outbox (formato 2, nenhum campo de porcentagem). *Pré-requisito:* 1, 2 e 3.
6. **Comprovante e relatórios do acerto da nota.** Relatório do acerto com a linha da meta (só regra `META`), Totais da
   semana com "Notas que bateram a meta", PT/ES. *Pré-requisito:* 5.
7. **Cadastro do cliente sem Comissão %** (ou com a exceção que a dona pedir). *Pré-requisito:* resposta da P2 e a 5.
8. **Nomes: Revendedora (e o nome de quem usa o Corona Vendas).** Só textos dos dois apps, relatórios e glossário.
   *Pré-requisito:* respostas das P5 e P6; depois da 6, para não brigar pelos mesmos arquivos de texto.

Ao fim da 6: roteiro de teste à mão no servidor de teste (uma nota abaixo e uma acima da meta, sem internet e com
internet, cotação da dona mudada no meio) e a publicação do 1.3.0 (`vendas:publicar`), feita pela orquestradora.

**Segunda leva (fora deste plano):** a meta somando várias notas da mesma revendedora (se a P3 pedir); "acertos
seguidos sem meta" como aviso na ficha (cláusula quarta do contrato); desligar o formato 1 de `ACERTO_NOTA` depois dos
90 dias.

## 5. Dúvidas para a dona (em espanhol simples)

- **P2.** ¿Hay alguna revendedora con una comisión distinta de 30%/40%? ¿O la regla es igual para todas? *(Padrão: igual
  para todas.)*
- **P3.** Si una revendedora tiene **dos notas** (por una reposición de otra semana) y hace el acerto el mismo día, ¿se
  suman las dos para llegar a los Gs. 700.000? *(Padrão: no, cada nota por separado.)*
- **P4.** Los Gs. 700.000 ¿son sobre lo **vendido a precio de etiqueta**, antes de descontar la comisión? *(Padrão: sí.)*
- **P5.** En las pantallas, ¿llamamos **"Revendedora"** a la persona que lleva las piezas (hoy dice "Cliente")?
- **P6.** ¿Cómo llamamos a la persona que usa Corona Vendas y entrega las piezas a las revendedoras? (hoy dice
  "Representante")
- **P7.** Si la revendedora devuelve antes o después de los 45 días, ¿la meta sigue siendo Gs. 700.000? *(Padrão: sí.)*

## Apêndice. Alternativas descartadas

- **Período do representante com tabelas de acerto novas** (a primeira versão deste plano): a P1 disse que o contrato é
  com a revendedora; o período já é a nota.
- **Servidor aplicar a meta também ao formato 1:** o papel impresso pelo 1.2.0 não bateria com o sistema (D4).
- **Reaproveitar as chaves da meta da §7.4:** são do acerto escondido; mexer numa mudaria a outra (D5).
- **Cotação da saída da nota:** é conhecida antes, mas o VD só existe no acerto e o Relatório do acerto mostra Guarani
  pela cotação do acerto (D2).
- **Deixar o representante digitar a porcentagem e só avisar:** o contrato fixa 30%/40%; a tela que deixa digitar é a
  porta do erro.
