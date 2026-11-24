# Plano: a dona muda a cotação do Dólar e do Guarani durante o dia

Pedido do responsável (23/11/2026): "a dona precisa poder mudar o valor do câmbio, porque ele varia durante o dia na
fronteira" (ela usa a cotação da casa de câmbio, não só a do Banco Central). Parte crítica (financeiro e sync): plano
antes do código, testes primeiro. Regra: especificação v1.33 (§7.5 e §9) e **ADR-0006, item 15** (adendo desta data).
Base: o PR 255 (V058, recebimento do representante em Dólar e Guarani, 409 `COTACAO_DO_RECEBIMENTO_MUDOU`).

Trabalho autônomo: as dúvidas viraram suposições (marcadas **S1…S8**) e perguntas à dona no PR.

## Resumo das decisões

| # | Decisão | Por quê |
| --- | --- | --- |
| D1 | Uma tabela só: `cotacoes` ganha `origem`, `vigente_desde`, `lancada_por` e `guaranis_por_real`. A cotação da dona é uma **linha nova** da mesma tabela. | Todo `cotacao_id` já gravado (vendas, acertos, notas, recibos, recebimentos) aponta para `cotacoes`; o sync e o Corona Vendas já leem essa tabela. Tabela separada obrigaria a mexer em ~15 chaves estrangeiras. |
| D2 | Continua imutável: corrigir = lançar outra. Sem rota de alterar nem de apagar. | ADR-0006 item 3; CLAUDE.md. |
| D3 | `vigente_desde`: BCB do dia D = 00:00 de D em Brasília; dona = momento em que o servidor grava. | Com só BCB, "maior `vigente_desde` ≤ momento" é **exatamente** a regra antiga "maior `data_cotacao` ≤ dia em Brasília": nada muda para quem não digita, e os testes atuais continuam valendo. |
| D4 | A `UNIQUE` de `data_cotacao` vira índice único **parcial** (só `BANCO_CENTRAL`). | A busca do BCB continua "um por dia, nunca relido"; a dona pode lançar várias. |
| D5 | Vale a cotação de **maior `vigente_desde` ≤ momento do lançamento** (desempate: maior `id`, UUID v7). Acerto e o que não é lançamento: a vigente **agora**. | Pedido do responsável ("a vigente no momento"). |
| D6 | O servidor continua escolhendo a cotação do lançamento pela hora dele; o Corona Vendas **não manda** cotação. | ADR-0006 item 13 (o app nunca calcula com cotação própria) e "o servidor recalcula". A conversão é só tela: valor gravado é sempre Real. |
| D7 | Recebimento digitado em Guarani com cotação da dona: `R$ = Gs. ÷ guaranis_por_real`. Todo o resto: a fórmula de sempre, com `pyg_venda`. | É o único caminho em que a taxa vira dinheiro gravado; a taxa de 8 casas (0,00074074) erraria centavos em valores grandes. |
| D8 | O 409 do recebimento passa a comparar o **id** da cotação (não a data); o fechamento do acerto ganha a mesma proteção quando tem recebimento em Dólar ou Guarani. | Com várias por dia, a data não detecta a troca. |

## 1. Modelo de dados (migração V059)

`V059__cotacao_da_dona.sql` (nova; nenhuma publicada é editada):

```sql
ALTER TABLE corona.cotacoes
    ADD COLUMN origem text NOT NULL DEFAULT 'BANCO_CENTRAL'
        CONSTRAINT cotacoes_origem_conhecida CHECK (origem IN ('BANCO_CENTRAL', 'DONA')),
    ADD COLUMN vigente_desde timestamptz,
    ADD COLUMN lancada_por uuid REFERENCES corona.usuarios (id),
    ADD COLUMN guaranis_por_real numeric(18,8)
        CONSTRAINT cotacoes_guaranis_por_real_positivo CHECK (guaranis_por_real > 0);

-- Preenche vigente_desde das linhas do BCB: 00:00 do dia em Brasília. A tabela recusa UPDATE por comando
-- (corona_recusa_update): só esse gatilho é desligado, dentro da migração. O gatilho de controle continua
-- ligado, então version e sync_seq sobem e o Corona Vendas recebe as linhas de novo (com as colunas novas).
ALTER TABLE corona.cotacoes DISABLE TRIGGER corona_recusa_update;
UPDATE corona.cotacoes SET vigente_desde = (data_cotacao::timestamp AT TIME ZONE 'America/Sao_Paulo');
ALTER TABLE corona.cotacoes ENABLE TRIGGER corona_recusa_update;

ALTER TABLE corona.cotacoes
    ALTER COLUMN vigente_desde SET NOT NULL,
    DROP CONSTRAINT cotacoes_data_unica,
    ADD CONSTRAINT cotacoes_origem_completa CHECK (
        (origem = 'BANCO_CENTRAL' AND lancada_por IS NULL AND guaranis_por_real IS NULL)
        OR (origem = 'DONA' AND lancada_por IS NOT NULL AND guaranis_por_real IS NOT NULL
            AND usd_compra = usd_venda AND pyg_compra = pyg_venda
            AND data_cotacao = (vigente_desde AT TIME ZONE 'America/Sao_Paulo')::date));
CREATE UNIQUE INDEX cotacoes_bcb_um_por_dia ON corona.cotacoes (data_cotacao) WHERE origem = 'BANCO_CENTRAL';
CREATE INDEX cotacoes_por_vigencia ON corona.cotacoes (vigente_desde DESC, id DESC);
```

- Comentários (`COMMENT ON`) das colunas novas e da tabela atualizados ("uma linha por dia útil do BCB, mais as da dona").
- `data_cotacao` continua `NOT NULL`: na linha da dona é o dia (Brasília) em que ela lançou. Serve para a linha da
  tela ("de 23/11/2026") e para o Corona Vendas antigo.
- `fonte` da linha da dona: `'Digitada pela dona'`; `buscada_em` = o mesmo instante de `vigente_desde`.
- Paridades da linha da dona, calculadas pelo servidor: `usd_paridade_* = 1`; `pyg_paridade_* = usd_venda ×
  guaranis_por_real` (guaranis por dólar, como o BCB publica), 8 casas, HALF_UP.
- `VerificadorDeConvencoes`: `cotacoes.guaranis_por_real` entra na lista explícita de `numeric(18,8)`.
- Antes de rodar o `UPDATE`, conferir no PR de código que o nome do gatilho é `corona_recusa_update` (V002, função
  `aplicar_convencoes_sincronizavel`). A V003 já desliga gatilhos numa migração (precedente).

**Quem hoje procura "a cotação do dia"** muda para "a vigente no momento" (lista completa na seção 2).
`RepositorioDeCotacoes`:

- `gravar(...)` (BCB): `ON CONFLICT (data_cotacao) WHERE origem = 'BANCO_CENTRAL' DO NOTHING`, grava `vigente_desde`
  = 00:00 do dia em Brasília.
- novo `gravarDaDona(TaxasDoDia, guaranisPorReal, usuarioId, agora)`: um `INSERT`, `vigente_desde = agora`.
- `ateODia(LocalDate)` sai; entra `vigenteEm(Instant)`: `WHERE vigente_desde <= ? ORDER BY vigente_desde DESC, id DESC
  LIMIT 1`.
- `maisRecente()` passa a ser `vigenteEm(agora)` (hoje ordena por `data_cotacao` e pegaria a do BCB de hoje à frente
  da que a dona lançou de manhã).
- `diasComCotacao(de, ate)` (usada pela busca e pelo aviso) passa a contar **só `BANCO_CENTRAL`**: a da dona não
  impede a busca do BCB nem esconde o aviso de busca que falhou.
- novo `doDia(LocalDate)`: todas as cotações do dia, em ordem de `vigente_desde` (histórico da tela).
- `Cotacao` (record) ganha `origem`, `vigenteDesde`, `lancadaPor` (só servidor) e `guaranisPorReal`.

## 2. Qual cotação vale para cada lançamento

Regra: **a de maior `vigente_desde` ≤ momento do lançamento.** O que tem `cotacao_id` gravado nunca muda; nas
leituras, quem tem `cotacao_id` usa o dele (`LEFT JOIN cotacoes ON id = x.cotacao_id`) e só quem não tem cai na regra
pelo momento.

**SQL padrão para trocar** (todos os lugares que hoje fazem `data_cotacao <= dia ORDER BY data_cotacao DESC`):

```sql
-- antes: WHERE c.data_cotacao <= (x.momento AT TIME ZONE 'America/Sao_Paulo')::date ORDER BY c.data_cotacao DESC LIMIT 1
-- depois:
WHERE c.vigente_desde <= x.momento ORDER BY c.vigente_desde DESC, c.id DESC LIMIT 1
```

Criar um utilitário Java único (`CotacaoVigente.SQL_PELO_MOMENTO`, ou método que monta o `LEFT JOIN LATERAL` dado a
expressão do momento) e usá-lo em todos; um teste de arquitetura reprova `data_cotacao <=` fora de `cotacoes/`.

### 2.1 Gravam `cotacao_id` na hora do lançamento (mudar a escolha: data → momento)

| Lugar | Hoje | Passa a |
| --- | --- | --- |
| `vendas/ServicoDeVendas.cotacaoDoDia(LocalDate)` (venda online e do push) | dia de `quando` | `vigenteEm(quando)` (`quando` = `vendidaEm` corrigida) |
| `trocas/ServicoDeTrocas` (l. ~473) | dia da troca | `vigenteEm(trocadaEm)` |
| `devolucoes/RegrasDaDevolucao` (l. ~200; devolução e pedido de autorização) | dia | `vigenteEm(momento)` |
| `creditos/ServicoDeCreditos` (l. ~317) | dia | `vigenteEm(geradoEm)` |
| `consignado/ServicoDeNotas` (l. ~436) | dia | `vigenteEm(momento da nota)` |
| `consignado/ServicoDeAcertosDaNota` (l. ~300) | dia | `vigenteEm(momento do acerto da nota)` |
| `consignado/ServicoDeRecibos` (l. ~223) | dia | `vigenteEm(pagoEm)` |
| `contasreceber/ServicoDeRecebimentosDoVendedor.converterPelaCotacaoDe` (avulso em Dólar/Guarani) | `paraLancamento(hora)` + 409 pela data | `vigenteEm(hora)` + 409 pelo **id** (seção 5.3) |
| `acertos/MontagemDoFechamento.montar` (cotação do acerto) | `maisRecente()` por `data_cotacao` | `vigenteEm(agora)` |
| `acertos/MontagemDoFechamento` (venda sem `cotacao_id`, para a meta em Guarani) | `paraLancamento(vendidaEm)` | `vigenteEm(vendidaEm)` (já é pelo instante) |
| recebimento "pagou agora" do acerto | a do acerto | igual (a do acerto); proteção nova no fechar (5.3) |

Todos esses hoje repetem a mesma consulta SQL solta: trocar por `CotacaoVigente.vigenteEm(Instant)` (um lugar só).

### 2.2 Recalculam pela data na leitura (mudar para o momento; quem tem `cotacao_id` usa o dele)

| Lugar | Momento usado |
| --- | --- |
| `contasreceber/ConsultasDeRecebimentosDoVendedor` (l. ~90; já usa `cotacao_id` se houver) | `r.recebido_em` |
| `relatorios/ConsultasFinanceiras` (helper l. ~103, usado por caixa, contas a receber/pagar, comissões, acertos) | a coluna de momento que cada consulta já passa; **onde hoje passa uma `date`** (vencimento, data do caixa manual), usar o fim desse dia em Brasília (`(d + 1)::timestamp AT TIME ZONE 'America/Sao_Paulo' - interval '1 microsecond'`), que é o equivalente exato de "≤ dia" |
| `relatorios/ConsultasDoResultado` (l. ~64) | idem, pela expressão que recebe |
| `relatorios/ConsultasDeDevolucaoETroca` (l. ~103) | `p.pago_em` |
| `remessas/SqlDaMaleta` (l. ~28) e `relatorios/ConsultasDeMaleta` (usa o mesmo SQL) | `r.enviada_em` |
| `vendedor/RelatoriosDoVendedor` (l. ~268) | `p.pago_em` |
| `remessas/NotaDeRemessaController` (l. ~102, `paraLancamento(enviadaEm)`) | `vigenteEm(enviadaEm)` |
| `relatorios/ConsultasDoPainelSemanal` (l. ~227, `maisRecente`) | `vigenteEm(agora)` |
| `consignado/ServicoDoFechamentoDaMaleta` (l. ~309, só `EXISTS`) | sem mudança |
| leituras que só seguem `cotacao_id` (`ConsultasDeVendas`, `ConsultasDeTrocas`, `ConsultasDeDevolucoes`, `ConsultasDeAutorizacoes`, `ConsultasDeCreditos`, `ConsultasDeAcertos`, `ConsultasDeVendasDaDona`, `VendidoLiquido`) | conferir: se houver `coalesce` para a regra por data, trocar pelo momento; senão, sem mudança |
| `cotacoes/ServicoDeCotacoesDaDona.hoje()` e `doDia(data)` | `hoje()` = `vigenteEm(agora)`; `doDia(data)` (data passada) = `vigenteEm(fim do dia em Brasília)` |
| `cotacoes/AvisoDeCotacao` | dias sem cotação: só BCB (`diasComCotacao`); "data da cotação em uso": a vigente agora |

As colunas dos relatórios "Data da cotação" passam a ser **data e hora** quando a origem é a dona (seção 5.4).

### 2.3 Corona Vendas (só tela)

`main/local/consultas-locais.ts` (`cotacaoAte`, `cotacaoDaVenda`, `cotacaoDeHoje`), `main/local/consultas-da-fase-6.ts`
(`cotacaoAte`), `main/local/relatorios-do-consignado.ts` (`cotacaoAte`), `main/sync/consignado-comum.ts`
(`cotacaoAteODia`), `main/local/consultas-do-consignado.ts` (l. ~737): todos fazem `data_cotacao <= dia`. Trocar por
**uma** função `cotacaoVigenteEm(db, instante)` em `consignado-comum.ts`: `WHERE vigente_desde <= ? ORDER BY
vigente_desde DESC, id DESC LIMIT 1` (texto ISO-8601 UTC: compara certo como texto se o servidor sempre manda o mesmo
formato com `Z`; conferir e, se preciso, normalizar ao gravar no pull). Onde hoje passa um dia, passar o instante; o
"de hoje" passa o agora. Preferir o `cotacao_id` do lançamento quando houver (já é assim em `cotacaoDaVenda`).

## 3. Como a dona digita

- **Dólar:** "1 dólar = R$ ___" (ex.: 5,80) → `usd_compra = usd_venda = 5,80000000`.
- **Guarani:** "1 real = Gs. ___" (ex.: 1.350) → `guaranis_por_real = 1350`; `pyg_compra = pyg_venda =
  arredondar(1 ÷ 1350, 8 casas, HALF_UP) = 0,00074074`.
- **Uma taxa por moeda, para compra e venda (S1).** É o que a tela já usa (a de venda) e o que ela fala. Pergunta à
  dona no PR.
- **As duas moedas juntas, sempre (S2).** A tela abre com as duas preenchidas pela cotação vigente; ela muda só a que
  quiser. Uma linha nova leva as duas (não existe cotação parcial, ADR-0006 item 4).
- **Validação no servidor (rota da dona):** Dólar entre R$ 0,01 e R$ 999,99, até 4 casas; Guarani inteiro ou com até 2
  casas, entre 1 e 99.999. Fora disso: 400 com mensagem simples.
- **Comparação com o Banco Central:** a tela mostra ao lado "Banco Central de hoje: 1 dólar = R$ 5,50 · 1 real = Gs.
  1.117" (a do BCB do dia, ou a última publicada, com a data). Se a digitada difere mais de **15%** da do BCB, aviso
  amarelo, sem travar: "Este valor está bem diferente do Banco Central (R$ 5,50). Confira antes de gravar." Ela grava
  mesmo assim. Sem nenhuma do BCB, sem comparação. A conta dos 15% é feita no servidor e devolvida na prévia (a tela não
  calcula regra), e repetida na resposta de gravar (`avisoDeDiferenca: true`), que mesmo assim grava.
- **Precisão do Guarani (D7):** a conversão das telas (R$ → Gs.) continua `R$ ÷ pyg_venda`; com 0,00074074 o erro é de
  0,0001% (R$ 100 → Gs. 135.000, igual à conta dela). No **recebimento digitado em Guarani**, que vira Real gravado,
  usa-se `guaranis_por_real` quando existe: `R$ = arredondar(Gs. ÷ 1350, 2)` (Gs. 50.000.000 → R$ 37.037,04; pela taxa de
  8 casas daria 37.037,00). Muda só `ConversaoDeMoeda.guaraniParaReais` (Java) e o espelho em `packages/shared/src/moedas.ts`.
- **Quem pode:** só a dona (perfil da dona na API; `@PreAuthorize` igual às outras rotas `/api/dona`). Auditoria:
  `COTACAO_LANCADA_PELA_DONA` com as duas taxas, a do BCB do momento e o aviso de diferença.

## 4. Sync com o Corona Vendas

- **Como chega:** a linha nova é um `INSERT` em tabela sincronizável; o `sync_seq` sobe e o próximo pull entrega a todos
  os vendedores (a regra da tabela é `daLoja()`, igual para todos). Nada novo no servidor além das colunas.
- **Colunas no pull:** `RegistroDeTabelas` (cotacoes) ganha `origem` e `vigente_desde`. **`guaranis_por_real` também
  vai** (o app mostra "1 real = Gs. 1.350" como a dona digitou). **`lancada_por` não vai** (id de usuário da dona: não é
  dado do vendedor).
- **App antigo recusa coluna desconhecida** (`aplicador-do-pull.ts`, `conferirLinhas`: "coluna fora da lista" recusa o
  lote todo). Por isso, como na 6.7 (ADR-0013, linha de 13/11/2026, item 5): **migração local 13** do Corona Vendas
  (colunas novas em `cotacoes`, índice `(vigente_desde)`, e **recarga dirigida** de `cotacoes`, sem apagar antes) e
  **versão mínima** sobe no deploy da API que levar a V059, **depois** de publicado o app novo. O app velho continua
  enviando e recebe 426 no pull até atualizar. Ordem de publicação no passo 8.
- **Offline:** o vendedor vê a **última que recebeu**, com a linha da cotação dela ("Cotação da loja de 23/11/2026 às
  10:15, a última que este computador recebeu"). É só tela.
- **Lançamento feito offline com cotação mais velha:** o app não manda cotação; o servidor grava a **vigente no momento
  do lançamento** (a hora que veio, corrigida pela regra da hora do ADR-0013). **Decisão: recalcular, não aceitar a do
  app.** Motivos: (1) o app nunca calcula com cotação própria (ADR-0006, item 13) e hoje nem manda `cotacao_id`;
  (2) o valor gravado é Real, então a diferença só aparece na conversão de tela; (3) aceitar a do app abriria um caminho
  de dado vindo do cliente que o servidor precisaria validar ("existia e era vigente naquele momento"), e a resposta
  dessa validação é exatamente a vigente que o servidor já acha. Consequência conhecida: o vendedor sem internet pode ter
  mostrado ao cliente um Gs. pela cotação antiga; a venda vale em Real (mesmo valor) e o relatório mostra o Gs. pela
  cotação da hora da venda. Registrar no glossário como texto de ajuda, sem aviso na tela.
- **Meta da comissão em Guarani** (`vendido_para_meta_guarani`, soma de cada venda pela `pyg_venda` do seu
  `cotacao_id`): passa a refletir a cotação da dona na hora de cada venda. É o comportamento pedido ("vale a vigente no
  momento do lançamento"). Registrado como dúvida S6 (a dona pode preferir a meta sempre pelo BCB).
- **Conciliação:** todas as conciliações e totais são em Real (ADR-0006, item 9; ADR-0012, item 17); nenhuma usa taxa.
  Nada muda. O teste de conciliação roda no fim com cotações da dona no meio do período.
- **Conferência por resumo do pull:** conta linhas por tabela que passam no filtro. A V059 sobe a `version` de todas as
  cotações (o `UPDATE` do preenchimento); a contagem não muda (nenhuma linha some nem nasce). As linhas da dona são
  linhas normais da tabela: entram na contagem dos dois lados. Nada muda na conferência; o teste de duas pontas confirma.
- **Push:** sem tipo novo nem formato novo; o resumo canônico das operações não muda.

## 5. Telas (Corona Gestão; PT e ES)

Termos novos para o `docs/glossario-interface.md` (passo 1), com o espanhol:

| PT | ES | Uso |
| --- | --- | --- |
| Cotação da loja | Cotización de la tienda | a que a dona lançou |
| Cotação do Banco Central | Cotización del Banco Central | a buscada (já existe) |
| Mudar a cotação | Cambiar la cotización | botão |
| Cotações de hoje | Cotizaciones de hoy | histórico do dia |
| Vale desde | Vale desde | hora de início |

### 5.1 Onde ela vê e muda

- **Cartão "Cotação de hoje"** da tela inicial (já existe, `renderer/nucleo/cotacao.tsx`) ganha o botão **Mudar a
  cotação**. Também em **Financeiro → Cotação** (item novo do menu), para quem procura pelo menu.
- **Janela "Mudar a cotação"**: dois campos grandes, "1 dólar = R$ [5,80]" e "1 real = Gs. [1.350]", já preenchidos
  com a vigente; ao lado, a do Banco Central do dia; aviso dos 15% se for o caso; texto de ajuda: "A cotação nova vale a
  partir de agora. O que já foi lançado não muda." Botões **Gravar a cotação** e **Voltar**. Confirmação curta:
  "Cotação da loja gravada. Vale desde 10:15."
- **Linha da cotação** em todas as telas: dona → "Cotação da loja de 23/11/2026 às 10:15"; BCB → como hoje.
  Corona Vendas idem (com e sem internet).

### 5.2 Histórico do dia

Na mesma janela (ou em Financeiro → Cotação), lista **Cotações de hoje**: hora em que passou a valer, 1 dólar = R$, 1
real = Gs., origem (Banco Central / Loja, com o nome de quem lançou), e a que vale agora marcada **Vale agora**. Filtro
de data para ver outro dia. Nada se apaga nem se edita: "Para corrigir, grave uma cotação nova."
Rota: `GET /api/dona/cotacoes?data=AAAA-MM-DD` (lista do dia) e `POST /api/dona/cotacoes` (grava; corpo `{usd: "5.80",
guaranisPorReal: "1350"}`; o id é gerado pelo servidor, UUID v7, porque a cotação nasce só no servidor como hoje) e
`POST /api/dona/cotacoes/previa` (devolve a conversão para a tela e o aviso dos 15%, sem gravar).

> Nota: o CLAUDE.md pede "IDs gerados no cliente", mas `cotacoes` sempre teve id do servidor (ADR-0006, item 4). Para
> o reenvio não duplicar (queda de rede), o `POST` aceita um `id` opcional gerado pelo Corona Gestão e é idempotente por
> ele (mesmo id e mesmas taxas → 200 com a mesma linha; mesmo id e outras taxas → 409). Decisão do plano, sem dúvida
> para a dona.

### 5.3 Os 409 de "a cotação mudou"

- **Recebimento avulso:** o pedido passa a mandar `cotacaoId` (o id que a tela mostrou). O servidor compara com o id
  da vigente; diferente → 409 `COTACAO_DO_RECEBIMENTO_MUDOU`. `cotacaoData` continua aceito por compatibilidade (Corona
  Gestão antigo) e compara como hoje. Texto novo (PT/ES):
  - PT: "A cotação mudou enquanto você digitava. Confira o valor em reais e grave de novo." A tela **recarrega a
    cotação e recalcula sozinha** o "Fica assim", sem fechar a janela, e deixa o botão pronto (a dona só confere e clica).
  - ES: "La cotización cambió mientras usted escribía. Revise el valor en reales y guarde de nuevo."
- **Fechamento do acerto (novo):** o fechar manda o `cotacaoId` da prévia. Se diferente **e** o fechamento tem
  recebimento em Dólar ou Guarani, 409 `COTACAO_DO_ACERTO_MUDOU` ("A cotação mudou. Confira os valores do acerto e
  feche de novo." / "La cotización cambió. Revise los valores del ajuste y cierre de nuevo." — o termo ES de "acerto"
  segue o que já está em `idioma/es/acertos.ts`). Sem recebimento em outra moeda, fecha normal com a vigente (só muda o
  que é mostrado em Dólar e Guarani).

### 5.4 Relatórios e PDF

Cabeçalho: "Cotação da loja de 23/11/2026 às 10:15" quando a vigente é da dona. Excel/CSV: coluna **Data da cotação**
passa a "23/11/2026 10:15" na linha cuja cotação é da dona (BCB continua só data). Ninguém soma pela cotação: nada muda
nos totais.

## 6. Testes a escrever primeiro

Java (Testcontainers, `backend/`):

1. **`TabelaCotacoesTest`** (estender): a V059 preenche `vigente_desde` = 00:00 de Brasília de cada dia; recusa
   `UPDATE`/`DELETE` depois da migração (o gatilho voltou); dois BCB no mesmo dia → recusa; dois DONA no mesmo dia →
   aceita; DONA sem `lancada_por` ou com compra ≠ venda → recusa; DONA com `data_cotacao` diferente do dia de
   `vigente_desde` → recusa.
2. **`CotacaoVigenteTest`** (estender; tabela de casos): só BCB → mesmos resultados da tabela atual do ADR item 14
   (regressão: a regra nova é igual à velha); BCB D-1 + dona D 10:00 + BCB D (vigente 00:00, gravada às 13:00): às 09:59
   → BCB D; às 10:00 → dona; às 15:00 → dona; D+1 às 09:00 sem BCB D+1 → dona; D+1 depois da busca → BCB D+1. Sexta
   23:30 Brasília (02:30 UTC sábado) → a da sexta. Desempate de mesmo `vigente_desde` pelo id.
3. **`ConversaoDeMoedaTest`**: `guaraniParaReais` com `guaranis_por_real` (Gs. 50.000.000 ÷ 1350 = R$ 37.037,04; Gs. 1 ÷
   1350 = R$ 0,00 → recusa valor zero como hoje); sem ele, a fórmula antiga (exemplos atuais intactos). `1 ÷ 1350` em 8
   casas = 0,00074074; paridade PYG = 5,80 × 1350 = 7830,00000000. Mesmos casos no TS (`packages/shared`).
4. **`CotacoesDaDonaTest`** (rota): só a dona grava (vendedor → 403); validação dos limites; aviso dos 15% (5,80 vs
   BCB 5,50 → 5,45%, sem aviso; 6,40 → 16,3%, com aviso, e grava); sem BCB nenhum → sem comparação; idempotência pelo
   `id`; auditoria; histórico do dia em ordem; a busca do BCB do dia continua gravando depois da dona (o
   `diasComCotacao` ignora a dela) e o aviso de "busca falhou" não some por causa da dona.
5. **Lançamentos (um teste por lugar da seção 2.1):** com BCB de manhã e dona às 10:00, o lançamento das 09:00 grava o
   id do BCB e o das 11:00 grava o da dona; o que já estava gravado não muda depois de uma cotação nova. Venda do push
   com `vendidaEm` às 09:00 que chega às 12:00 → BCB (não a da dona).
6. **Recebimento:** 409 por id diferente; 409 por data (compatibilidade); recebimento em Guarani com a cotação da dona
   grava `cotacao_id` dela e o Real pela divisão; estorno copia.
7. **Acerto:** fechamento grava a vigente agora (dona, não a do BCB do dia); 409 `COTACAO_DO_ACERTO_MUDOU` só com
   recebimento em outra moeda; meta em Guarani soma cada venda pela cotação gravada.
8. **Relatórios (seção 2.2):** para cada consulta, um caso com duas cotações no mesmo dia mostra cada linha pela da sua
   hora, e o total em Gs. é a soma das linhas (ADR item 10). `ArredondamentoEmSqlTest` continua passando.
9. **Teste de arquitetura:** `data_cotacao <=` só dentro de `cotacoes/` (Java) e de `consignado-comum.ts` (TS).
10. **`VerificadorDeConvencoes`/`ConvencoesDoSchemaTest`:** `guaranis_por_real` aceito; outra coluna `numeric(18,8)`
    continua violação.
11. **Conciliações (ADR-0012, item 17):** as nove passam com cotações da dona no meio do período.

TypeScript (Vitest):

12. **Corona Vendas:** `cotacaoVigenteEm` com a mesma tabela de casos do item 2; migração local 13 num banco v12 com
    cotações (colunas novas, nada perdido, marca de recarga); `conferirLinhas` aceita `origem`, `vigente_desde`,
    `guaranis_por_real` e recusa `lancada_por`; linha da cotação "Cotação da loja de ... às ..." (PT/ES).
13. **Corona Gestão:** janela "Mudar a cotação" (preenche com a vigente, aviso de 15% vindo do servidor, grava,
    histórico); recebimento recalcula sozinho no 409.

Duas pontas (`testes-sync`, o sync muda: colunas novas e recarga dirigida):

14. **Cenário novo "cotação da dona chega ao vendedor":** dona lança às 10:00; o vendedor online recebe no próximo pull
    e a tela mostra a nova; outro vendedor offline vende às 10:30 vendo a antiga; volta a rede às 12:00: a venda grava o
    id da cotação da dona (vigente às 10:30), a conferência por resumo do pull fecha (sem diferença), nada recusado.
15. **App v12 → v13:** banco com cotações antigas, atualiza, a recarga dirigida traz `vigente_desde` de todas, a
    conferência fecha. API nova com app velho → 426 no pull, push continua.
16. O **estresse curto** roda como sempre (`npm run test:duas-pontas`), com uma cotação da dona lançada no meio.

## 7. Passos (uma sessão de código em Sonnet, nesta ordem)

Cada passo: testes primeiro, depois o código, `./mvnw verify` / `npm run typecheck lint` verdes, commit Conventional.

1. **Glossário e textos.** `docs/glossario-interface.md` (termos da seção 5, linha da cotação da loja, textos dos 409,
   ajuda do vendedor offline); chaves PT e ES em `apps/owner-app/src/renderer/idioma/` e
   `apps/seller-app/src/renderer/textos-{pt,es}.ts`. `docs(glossario): ...`.
2. **V059 + repositório.** Testes 1, 2 e 10; migração; `Cotacao` com os campos novos; `RepositorioDeCotacoes`
   (`gravar` com `ON CONFLICT` parcial, `gravarDaDona`, `vigenteEm`, `doDia`, `diasComCotacao` só BCB);
   `CotacaoVigente.vigenteEm(Instant)` e o SQL padrão (`paraLancamento*`/`maisRecente` viram atalhos dela e depois saem).
   Conferir `BuscaDeCotacoesTest` e `AvisoDeCotacaoTest` verdes sem mexer na expectativa. `feat(cotacoes): ...`.
3. **Conversão.** Teste 3; `ConversaoDeMoeda.guaraniParaReais(valor, Cotacao)` com `guaranis_por_real`; espelho em
   `packages/shared/src/moedas.ts`. `feat(moedas): ...`.
4. **Rotas da dona.** Teste 4; `POST /api/dona/cotacoes`, `/previa`, `GET ?data=`; `CodigoErro` novos; auditoria;
   OpenAPI e `npm run api:clientes`. `feat(cotacoes): ...`.
5. **Lançamentos e leituras do servidor.** Testes 5 a 9 e 11; trocar cada lugar das tabelas 2.1 e 2.2 (um commit por
   pacote se ficar grande); o 409 por id no recebimento e o 409 novo do acerto. `feat: ...`/`fix: ...`.
6. **Corona Gestão.** Teste 13; cartão, menu, janela, histórico, linha da cotação, recebimento e fechar mandando
   `cotacaoId`, relatórios/PDF/Excel com hora. `feat(gestao): ...`.
7. **Corona Vendas.** Teste 12; migração local 13 com recarga dirigida; `colunas.ts`; `cotacaoVigenteEm` no lugar das
   cinco consultas; linha da cotação; `RegistroDeTabelas` (servidor) com `origem`, `vigente_desde`,
   `guaranis_por_real`. `feat(vendas): ...`.
8. **Duas pontas e entrega.** Testes 14 a 16; `npm run e2e` do recebimento; atualizar `docs/PROGRESSO.md`; no PR,
   a **ordem de publicação**: (a) publicar o Corona Vendas novo (aceita as colunas, ainda não as recebe); (b) publicar o
   Corona Gestão novo; (c) deploy da API com a V059 e a versão mínima do Corona Vendas subida. Entre (a) e (c) nada
   quebra: a API velha não manda as colunas novas.

## 8. Suposições (viram perguntas à dona no PR)

- **S1.** Um valor por moeda, que vale para compra e venda.
- **S2.** Ela muda as duas moedas na mesma janela (uma pode ficar igual).
- **S3.** A cotação dela vale até ela lançar outra ou até chegar a do Banco Central do dia seguinte (a do BCB não passa
  por cima da dela no mesmo dia).
- **S4.** Não lança cotação para o passado nem para depois: vale a partir da hora em que grava.
- **S5.** Aviso a partir de 15% de diferença do Banco Central, sem travar.
- **S6.** A meta da comissão em Guarani usa a cotação da hora de cada venda (inclusive a da loja).
- **S7.** Os vendedores veem a cotação da loja no Corona Vendas (é a mesma da loja para todos).
- **S8.** Só a dona muda a cotação (não há outro perfil hoje).

## 9. O que fica de fora

- Cotação diferente para compra e venda (S1), cotação por vendedor ou por praça, agendar cotação.
- Mudar a cotação de um lançamento já gravado (nunca: imutável).
- Buscar a cotação de uma casa de câmbio pela internet.

## 10. Como ficou (ajustes feitos na implementação)

- **Comparação no Corona Vendas por `julianday`, não por texto.** O servidor pode mandar `vigente_desde` com frações de segundo (`...00Z` e `...00.123Z` se ordenam errado como texto). `cotacaoVigenteEm` compara por `julianday(...)`; linha sem `vigente_desde` (banco anterior à migração 13) vale desde `data_cotacao` + `T03:00:00.000Z` (00:00 de Brasília). A migração 13 já preenche as linhas antigas desse jeito.
- **A prévia devolve `moedaDoAviso`** (`DOLAR` ou `GUARANI`), para a tela escrever o aviso nos dois idiomas sem refazer a conta dos 15%.
- **Resposta de gravar:** `{ cotacao, avisoDeDiferenca, aviso }`; reenvio com o mesmo `id` e os mesmos valores, 200; com outros valores, 409 `COTACAO_JA_GRAVADA_DIFERENTE`.
- **Leituras com só a data** (vencimento, data do caixa manual, resultado do período): usam o **fim do dia em Brasília**, o equivalente exato do antigo "cotação até o dia".
- **Testes que dependiam de cotações de 2285 a 2291**: o acerto usa a vigente agora; a cotação do exemplo do ADR-0012 (2291-06-01) é gravada nos testes como vigente desde ontem (`CotacoesDaMassa`), e os testes que precisam de "nenhuma cotação" fixam o relógio antes de qualquer uma.
- **Não feito neste PR:** data e hora da cotação nos relatórios, PDFs e Excel (seção 5.4); `npm run e2e` do recebimento.
