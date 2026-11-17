# Revisão geral antes do uso real (17/11/2026)

- **Base revisada:** `main` em `06a4d69` (Fases 0 a 8, Fase 9 mínima, identidade visual do PR 206, dois idiomas dos PRs 214 e 215, menu de relatórios por área do PR 217; ADRs 0001 a 0015).
- **Tipo:** só leitura e testes. Nada foi corrigido; cada achado vira tarefa depois, com aprovação do responsável.
- **Comparação:** com a [auditoria de 11/11/2026](2026-11-11-checkup.md). O que já estava lá aparece só na seção 5 ("ainda aberto da auditoria anterior"), não como achado novo.
- **Regra:** só defeitos com prova. "Confirmado" tem trecho, comando ou teste; "suspeita" não tem prova.

## Resumo

1. **Todas as suítes passaram** (seção 4): backend com 1775 testes, duas pontas (29), E2E (7), os vitest dos 8 workspaces, lint, tipos, formato, o empacotamento do Corona Vendas e a barreira (12 marcadores, limpa). A exceção é o `npm audit --audit-level=high`: **10 avisos altos novos** (ReDoS no `picomatch`, só em ferramentas de desenvolvimento), que vão reprovar a rodada semanal do CI na próxima segunda (M1).
2. **Nenhum achado crítico.** Permissões, vazamento entre vendedores, custo fora do Corona Vendas, dinheiro em `NUMERIC`, Electron e migrações publicadas estão certos.
3. **1 alto:** quando o servidor recusa um dado (`DADOS_INVALIDOS`), as telas sem campo correspondente mostram só "Algunos datos no están correctos" (ou "Alguns dados não estão corretos"), e **o motivo de verdade se perde**, nos dois apps e nos dois idiomas (A1). Exemplo: lançamento de caixa com data no futuro.
4. **4 médios:** CI semanal vai ficar vermelho pelo `npm audit` (M1); avisos da tela inicial e descrições das conferências chegam do servidor **em português na tela em espanhol** (M2); o alerta de "muitos erros de senha num mesmo login" é calculado mas **nunca chega à dona** (M3); decisões do responsável de 11/11 (garantia de 1 ano; troca e reembolso "só registrados") contradizem o que a Fase 6 construiu e a especificação, sem registro de que foram revogadas (M4).
5. **4 baixos:** situações vencidas no `PROGRESSO.md`, especificação sem a decisão dos dois idiomas, buraco na numeração das migrações (V027), e falta de teste do mapa de tradução dos relatórios.
6. **O que mais falta para o uso real:** a **semana** só existe se a dona cadastrar à mão (sem ela não se monta maleta); **não se imprime etiqueta** (o sistema só gera o número); **sem comprovante** para o cliente da venda a prazo; a **VPS, o backup fora dela e o instalador testado de verdade** (Fase 9). O prazo de **72 horas** para o vendedor estornar, decidido em 11/11, nunca foi feito (continua "no mesmo dia").
7. **Decisões pendentes:** 7 do responsável e 6 da dona (seção 6), entre elas o ADR-0015 (Proposto), o ADR-0007 (Proposto) e o **prazo máximo da venda em cuotas** (hoje 24 parcelas mensais fixas no código, primeiro vencimento até 1 ano depois).

## 1. O que está FALTANDO, por fase

Prometido na especificação (§15, §7, §8, §11, §12, §21) ou nos ADRs e que não existe, ou existe só em parte. No fim, o que um uso real na loja vai precisar e ainda não tem.

### Fase 1 (cadastros)

- **Semana criada sozinha: não existe.** A semana só nasce pelo formulário da dona (`ServicoDeSemanas.criar`, `backend/src/main/java/br/com/coronasemijoias/api/cadastros/ServicoDeSemanas.java:54`), com número, primeiro e último dia digitados. A maleta exige semana (`ServicoDeRemessas.java:84-85`, "Escolha a semana da maleta.") e o acerto sem maleta exige a semana de hoje (`CodigoErro.java:562`, "Cadastre a semana antes de iniciar o acerto."). A dona já respondeu que a semana é de segunda a domingo (§17, item 8). Se ela esquecer de cadastrar a semana, não monta maleta. O banco já impede semanas sobrepostas (`V010__cadastros_basicos.sql:81-84`), então criar a semana da data automaticamente é seguro.
- **Impressão de etiqueta: não existe.** A peça sem etiqueta ganha um código da loja sequencial (`CodigosDosProdutos.java:250-256`), e a tela só diz "Imprima a etiqueta com este número." (`apps/owner-app/src/renderer/textos-produtos.ts:108`). A §8 diz que a loja imprime as próprias etiquetas; o formato, a impressora e o tamanho dependem das perguntas 16 a 20 da dona e do ADR-0007, que continua **Proposto**.
- **Importação de cadastros (clientes, vendedores) por planilha: não existe.** Só há importação de produtos e de fotos (`V016__importacoes.sql:10`, `tipo IN ('PLANILHA', 'FOTOS')`). A §17, item 11, pergunta pela planilha atual "para a migração de cadastros"; a carga inicial dos clientes de cada vendedor hoje é manual.

### Fase 4 (vendedor online)

- **Prazo de 72 horas para o vendedor estornar: não feito.** Decidido pelo responsável em 11/11 (`docs/pendencias-do-responsavel.md:27`), mas o código continua "no mesmo dia" (`CodigoErro.java:380`, "Só dá para estornar a venda no mesmo dia."). Está registrado como não feito no ADR-0013 (linha datada da 5.5, item 11), sem tarefa aberta.
- **Paginação de "Minhas vendas":** continua aberta (M3 da auditoria anterior, só a metade feita no PR 210).

### Fase 5 (sem internet)

- **Aviso de pouco espaço na tela: não existe.** O motor calcula `poucoEspaco` ao abrir o banco (`apps/seller-app/src/main/banco/servico-do-banco.ts:122,140,232`), mas nenhuma tela lê o valor (nenhuma ocorrência em `apps/seller-app/src/renderer`). É a pendência A da Fase 5 (`docs/progresso/fase-5.md:271`), prevista para a Fase 6 e não feita.
- **"Computador reiniciou" no servidor: não existe.** Pendência C da Fase 5 (`docs/progresso/fase-5.md:273`), também prevista para a Fase 6.
- **Teste com um vendedor real sem internet e o instalador trocando de versão:** do responsável (pendências 14 e 15). O de amanhã (18/11) cobre parte disso.

### Fase 6 (devolução e troca)

- Código completo. **Falta o teste de uso** e resolver o conflito de decisões do M4 (garantia de 1 ano, troca e reembolso "só registrados").

### Fase 7 (acerto e financeiro)

- Código completo. **Falta o teste de uso** ([roteiro](../roteiro-teste-fase-7.md)).
- **Prazo máximo da venda em cuotas:** existe, mas **fixo no código e sem decisão da dona**: de 1 a 24 parcelas **mensais** (`ServicoDeContasAReceber.java:38`, `MAXIMO_DE_PARCELAS = 24`) e o primeiro vencimento até 12 meses depois da venda (`:39` e `:101-102`), ou seja, a última parcela pode cair quase 3 anos depois. Não há parcela semanal ou quinzenal, e a dona não pode mudar esses limites na configuração. Ver decisão pendente D-R6 e D-D1.

### Fase 8 (painel, relatórios, conciliações)

- Código completo. **Falta** o teste de uso com as planilhas da dona ([roteiro](../roteiro-teste-fase-8.md)) e a decisão do **ADR-0015** (Proposto; valem os padrões seguros).
- **Alerta de login atacado (§11):** existe só a consulta, sem aviso (achado M3).

### Fase 9 (entrega)

O que a §15 promete e ainda não existe (todas dependem do responsável, `docs/pendencias-do-responsavel.md`, itens 1 a 4, 8, 11 e 12):

- VPS contratada, IP fixo, certificado gerado e API no ar (pendência 1); chave SSH (2).
- Backup **fora** da VPS (`rclone crypt` no Google Drive, pendência 3) e o **teste de restauração** a partir dele; aviso de queda por `ntfy` e verificação externa (4).
- **Homologação do leitor com etiquetas reais** (8): o leitor só foi testado "como teclado" nos testes automáticos.
- Assinatura do instalador (11): sem ela, o Windows mostra "editor desconhecido".
- Validação com dados reais e treinamento (§15, Fase 9).

### O que o uso real na loja vai precisar e ainda não tem

1. **Semana criada sozinha** (segunda a domingo), para a dona não travar na montagem da maleta. (Fase 1, acima.)
2. **Imprimir etiqueta** do código da loja, quando a dona disser impressora e tamanho. (Fase 1, acima.)
3. **Comprovante para o cliente** no Corona Vendas (venda e parcelas a prazo, recebimento): o app não tem nenhum (`apps/seller-app/src` não tem impressão, recibo nem comprovante). O cliente que compra em cuotas sai sem papel com as datas e os valores. A especificação não promete; é o que um balcão costuma pedir.
4. **Motivo da recusa na tela** (achado A1): sem isso, a dona e o vendedor não sabem o que corrigir.
5. **Carga inicial** dos clientes e vendedores (Fase 1, acima), se a loja já tem planilha.
6. **Fase 9 inteira** (acima). Sem backup fora da VPS, um problema na VPS perde tudo.

## 2. O que está INCORRETO

Gravidade como na auditoria anterior: **crítico** (vaza dado, perde ou corrompe dinheiro/estoque, falha explorável), **alto** (regra do `CLAUDE.md` quebrada sem dano imediato, ou teste que falta para regra crítica), **médio**, **baixo**.

| Gravidade | Confirmados | Suspeitas |
| --- | --- | --- |
| Crítico | 0 | 0 |
| Alto | 1 | 0 |
| Médio | 4 | 0 |
| Baixo | 4 | 0 |

### A1 (alto, confirmado): o motivo da recusa do servidor se perde na tela

**Resolvido (PR 220):** os dois apps mostram o motivo dos campos que a tela não mostra, junto da frase geral. Em espanhol o motivo sai como o servidor manda (português); traduzir cada motivo (chave estável por motivo no servidor) fica como tarefa futura.

- **Onde:** `backend/src/main/java/br/com/coronasemijoias/api/cadastros/Validacoes.java:18-20` (toda recusa por campo vira `DADOS_INVALIDOS` com o texto geral e o motivo só em `campos`); `backend/src/main/java/br/com/coronasemijoias/api/comum/erros/ErroDaApi.java:158-161` (o `detail` é sempre "Alguns dados não estão corretos..."); `apps/owner-app/src/renderer/nucleo/chamadas.ts:59-64` (a mensagem da tela é a do código); `apps/owner-app/src/renderer/componentes/AvisoDeErro.tsx:18` (mostra só `erro.mensagem`, nunca `erro.campos`); `apps/owner-app/src/renderer/idioma/erros.ts:12-16` e `apps/seller-app/src/renderer/erros-es.ts:248-251` (em espanhol, o código vira o texto geral).
- **Evidência:** são **405** chamadas de `recusar(...)`/`CampoInvalido` no backend. Só os formulários de cadastro, produto, vendedor, compra e cadastro rápido mostram o texto do campo (`FormularioDeCadastro.tsx:54`, `FichaDoProduto.tsx:162`, `FormularioDeCampos.tsx:83`, `CadastroRapido.tsx:58`). As telas de financeiro (`LancarNoCaixa.tsx:141`, `PagarConta.tsx:99`, `RegistrarRecebimento.tsx:126`, `FecharSemana.tsx:60`), de acerto (`FecharAcerto.tsx:110`) e as demais usam o `AvisoDeErro`. Exemplo: um lançamento de caixa com data no futuro é recusado com "A data do lançamento não pode ser no futuro." (`ServicoDeLancamentosManuais.java:83`), e a dona lê só "Algunos datos no están correctos. Revise lo que completó e intente de nuevo." Em português o efeito é o mesmo: o `detail` é o texto geral. No Corona Vendas, as rotas que ainda passam pelo servidor na hora (pedir acerto, contar as sobras, autorização, recebimento) têm o mesmo caminho. Quebra a regra do `CLAUDE.md` "Erros em português simples dizendo o que fazer".
- **Correção proposta:** quando o erro tem `campos` e a tela não tem o campo, mostrar o texto do primeiro campo junto da mensagem (no `traduzirErro` e no `mensagemDoErro`). Para o espanhol, o servidor precisa mandar também uma chave estável por motivo (por exemplo `motivo: "DATA_NO_FUTURO"`) e cada app traduz a chave; enquanto a chave não existe, mostrar o texto do servidor (em português) é melhor do que nenhum motivo. Teste de tela: recusa com campo desconhecido mostra o motivo.

### M1 (médio, confirmado): o `npm audit --audit-level=high` falha e vai reprovar a rodada semanal do CI

- **Onde:** `.github/workflows/ci.yml:186` (`npm audit --audit-level=high`, rodado na rodada semanal da `main` e em todo PR que mexe em `package*.json`).
- **Evidência:** `npm audit --audit-level=high` sai com código 1: **22 avisos (12 moderados, 10 altos)**. Os 10 altos são o mesmo problema: `picomatch` <= 4.0.7, "ReDoS through single-branch repeated extglobs", que chega por `vite`, `vitest`, `@vitejs/plugin-react`, `electron-builder`/`app-builder-lib`/`@electron/rebuild` e `typescript-eslint`. Tudo ferramenta de desenvolvimento e empacotamento: nada disso vai no pacote nem roda com dado de usuário. O conserto sugerido pelo `npm audit` é trocar versão maior (`vite` 6.2.7, `vitest` 3.1.4, `electron-builder` 25.1.0), o que é **voltar** versões (hoje `vite` 8.3, `vitest` 5.1, `electron-builder` 26.18), então não serve. A última rodada semanal do CI na `main` (16/11) passou; o aviso é novo.
- **Correção proposta:** esperar ou forçar (`overrides` no `package.json`) a versão corrigida do `picomatch` e conferir com `npm audit`; se não houver versão corrigida, registrar a exceção com prazo e trocar o passo do CI por uma lista de avisos aceitos. Precisa ser feito antes da próxima segunda para o CI semanal não ficar vermelho.

### M2 (médio, confirmado): textos do servidor em português na tela em espanhol

- **Onde:** avisos de "O que precisa da sua atenção": `apps/owner-app/src/renderer/telas/TelaInicial.tsx:222-223` mostra `a.titulo` e `a.texto` como vieram; os textos nascem em português nas 8 fontes de aviso do servidor (por exemplo `backend/src/main/java/br/com/coronasemijoias/api/caixa/FonteDeAvisosDoCaixa.java:36-37`, "O caixa está negativo em ..."). Descrições das diferenças das conferências: `apps/owner-app/src/renderer/telas/conciliacoes/TelaConferenciaDiaria.tsx:151` e `TelaConferenciaDoDinheiro.tsx:113` mostram `descricao` do servidor (por exemplo `ConciliacoesDaFase7.java:169`, "O líquido do acerto não bate com ...").
- **Evidência:** o PR 215 traduziu os erros por código (`erros-es.ts`, 190 dos 191 códigos; o que falta, `ACESSO_BLOQUEADO`, tem tratamento próprio) e os relatórios (carreguei as definições de relatório em espanhol num teste temporário: nenhum texto em português sobrou), mas os avisos e as diferenças não têm código traduzível na tela: o aviso tem `tipo`, mas a tela usa o título e o texto do servidor. A tela inicial é a primeira que a dona vê. Além disso, o botão "ver a lista" do aviso de estoque depende do título em português (`TelaInicial.tsx:229`, `/acabando/i.test(a.titulo)`), então traduzir o título no servidor quebraria esse botão.
- **Correção proposta:** a tela monta o texto do aviso pelo `tipo` e pelos números que o servidor manda (como já faz com os erros); o botão do estoque passa a usar o `tipo`. Nas conferências, traduzir pela chave da regra. Teste: com o idioma em espanhol, nenhum aviso conhecido aparece com o texto do servidor.

### M3 (médio, confirmado): o alerta de "muitos erros de senha num login" nunca chega à dona

- **Onde:** `backend/src/main/java/br/com/coronasemijoias/api/acessos/AlertaDeErrosPorLogin.java:35` (`loginsComMuitosErros`).
- **Evidência:** a §11 diz que muitos erros num mesmo login vindos de IPs diferentes "geram só um **alerta** à dona" (ADR-0004, item 7). A consulta existe e tem teste (`AlertaDeErrosPorLoginTest`), mas **ninguém a chama** fora do teste: não há fonte de aviso para ela entre as 8 que existem (caixa, conferência, cotação, créditos, devoluções, estoque, computadores e envios). O próprio comentário da classe (`:16`) diz que o aviso "vai chamar ... quando existir". O relatório de Acessos (8.9) mostra cada tentativa, mas a dona só vê se abrir o relatório.
- **Correção proposta:** uma `FonteDeAvisos` com os logins acima do limite na última hora ("Muitas senhas erradas para o usuário X"), com teste.

### M4 (médio, confirmado): decisões do responsável de 11/11 contradizem a especificação e o que foi construído

- **Onde:** `docs/pendencias-do-responsavel.md:29` ("Garantia: **1 ano**") e `:30` ("a loja **não faz** essas transações pelo sistema, só **registra** ... sem regra de crédito automático nem limite semanal de reembolso"); `docs/perguntas-para-a-dona.md:212` repete as duas como decididas.
- **Evidência:** a §7.3 da especificação e o ADR-0014 (Aceito em 12/11) dizem o contrário: prazos de **45 dias** (arrependimento e defeito, `V040__prazos_de_45_dias.sql`), crédito automático do cliente na troca e limite semanal de reembolso, e é isso que está no código e nos testes de duas pontas (casos 17 a 20). Nenhum dos dois documentos registra que as decisões de 11/11 foram revogadas. Quem ler as pendências do responsável vai achar que a garantia é de 1 ano e que não há crédito.
- **Correção proposta:** o responsável confirma qual vale (decisão D-R3); depois, uma linha datada nas pendências e nas notas das perguntas dizendo que a Fase 6 (ADR-0014) substituiu os itens 6 e 7.

### B1 (baixo, confirmado): situações vencidas no `PROGRESSO.md`

- **Onde:** `docs/PROGRESSO.md:242` ("pronto para revisão, PR 217"), `:254` ("pronto para revisão, PR 214") e `:258` ("PR 209 aguardando revisão"). Os três estão na `main` (`git log`: `06a4d69`, `9bddd1c`, `303a8d3`).
- **Correção proposta:** trocar por "mesclado".

### B2 (baixo, confirmado): a especificação não registra a decisão dos dois idiomas

- **Onde:** `docs/ESPECIFICACAO.md:578` (§17, item 14): "os dois apps ficam em espanhol; mais adiante, o app do vendedor terá espanhol e português ..., e o da dona só espanhol".
- **Evidência:** o responsável decidiu os dois apps bilíngues, espanhol padrão (`docs/PROGRESSO.md:246`, PRs 214 e 215), e isso não chegou à especificação (o histórico da §22 também não tem). O `CLAUDE.md` pede "se uma regra mudar, atualize o documento primeiro".
- **Correção proposta:** atualizar a §17, item 14, a §19 e o histórico.

### B3 (baixo, confirmado): buraco na numeração das migrações (V027)

- **Onde:** `backend/src/main/resources/db/migration/`: depois de `V026__limite_de_consignacao_obrigatorio.sql` vem `V028__limpeza_de_dados_tecnicos.sql`.
- **Evidência:** `git log --all` não tem nenhum arquivo `V027__*`: nunca existiu. O Flyway aceita o buraco, mas se alguém criar uma `V027` agora, os bancos que já estão na V048 a recusam (fora de ordem). O job `migracoes` do CI não confere a sequência.
- **Correção proposta:** registrar o buraco num comentário da `V028` não pode (migração publicada); registrar no `docs/ci.md` ou no `verificar-migracoes.sh` que a próxima é sempre a maior + 1 e que V027 não pode ser usada.

### B4 (baixo, confirmado): o mapa de tradução dos relatórios não tem teste

- **Onde:** `apps/owner-app/src/renderer/idioma/es/relatorios-mapa.ts:698-711` (`traducirDefinicion`): a tradução é **pelo texto em português** (`RELATORIOS_EM_ESPANHOL[def] ?? def`).
- **Evidência:** nenhum teste usa `RELATORIOS_EM_ESPANHOL` nem `traducirDefinicion`. Hoje está completo (conferi com um teste temporário que carrega as sete definições em espanhol: nenhuma palavra de português sobrou), mas qualquer mudança de vírgula num texto de relatório em português faz a tela em espanhol voltar ao português sem aviso.
- **Correção proposta:** um teste que carregue as definições em espanhol e reprove texto que ficou igual ao português (fora nomes próprios e siglas).

### Suspeitas

Nenhuma. Não registrei o que não consegui provar.

### O que foi conferido e está certo (17/11)

- **Permissões e vazamento:** as 14 classes de rota do vendedor (Fases 4 a 8) passam o `vendedorId` do token a toda consulta e comando com id na rota (acertos, comissão, contas a receber, recebimentos, créditos, autorizações, devoluções, trocas, quarentena, sync, vendas, clientes, maletas); a leitura do acerto recusa acerto de outro vendedor com 404 (`ServicoDeAcertos.registrarLeitura`). O `TodasAsRotasTest` passa com as rotas novas.
- **SQL:** as concatenações novas (conciliações C1 a C12, curva ABC, devolução e troca, estoque) só juntam constantes do código; a busca por texto vai por parâmetro (`:texto`).
- **Dinheiro:** comissão por meta (`CalculoDaComissao.java:64-81`): cada venda convertida para Guarani pela própria cotação, `>=` na meta, percentual sobre o total, comissão por venda; nenhuma conta de dinheiro com `number` nos apps (só comparações e formatação).
- **Electron:** `contextIsolation`, `sandbox`, `nodeIntegration` desligado e `webSecurity` nos dois apps; nenhum `openExternal` novo.
- **Barreira:** pacote do Corona Vendas sem nenhum dos 12 marcadores.
- **Visual:** o dourado (`--cor-ouro-escuro`) só aparece em título grande, marca e ícone (`componentes.css:219,268`, `estilos.css:36,139,743` do Gestão e `:36` do Vendas); texto de dados é escuro sobre claro.
- **Dois idiomas:** os erros por código cobrem 190 de 191 códigos no Gestão; as recusas locais do motor do Corona Vendas têm espanhol; o teste do glossário varre as palavras proibidas em espanhol (`PROIBIDAS-ES`) nos dois apps; datas e números em `es-PY`.
- **Migrações:** V024 a V048, cada uma num commit só (nenhuma editada depois de publicada).
- **Documentação:** índice dos ADRs igual aos arquivos (0007 e 0015 Propostos, o resto Aceito); a seção Comandos do `CLAUDE.md` bate com o `package.json`.

## 3. Regras de negócio conferidas (troca, devolução, acerto, comissão, conferência diária, painel)

Conferidas pela leitura do código contra a §7.3, §7.4, §12 e §13 e pelos testes de duas pontas e do backend, que passaram: troca pelo crédito (casos 17 a 20 das duas pontas), devolução fora do prazo e relógio errado (14 a 16), pedir acerto e contar sobras (23), comissão por meta, fechamento e conciliações (`FechamentoDaSemanaTest` e as classes do acerto, no backend). Não achei regra quebrada além do que está na seção 2 e do prazo de 72 horas da seção 1.

## 4. Resultado de cada suíte

Rodadas numa worktree limpa da `main` (`06a4d69`), Windows 11, Docker aberto, sem tocar na API (8080), no banco (5433) nem nos apps (5173/5174) abertos pelo responsável.

| Suíte | Resultado | Tempo |
| --- | --- | --- |
| `npm ci --ignore-scripts` + binário do Electron + `@electron/rebuild` do `better-sqlite3-multiple-ciphers` | ok | 9 s + ~1 min |
| `npm run lint` | ok, sem avisos | 29 s |
| `npm run typecheck` | ok | 20 s |
| `npm run format:check` | ok | 10 s |
| `npm test -w @corona/owner-app` | 916 de 916 (74 arquivos) | 30 s |
| `npm test -w @corona/seller-app` | 773 de 773, 1 pulado (60 arquivos; o pulado é `gerar-exemplos.test.ts`, gerador manual) | 18 s |
| `npm test` de `api-client-dona`, `api-client-vendedor`, `conexao-segura`, `shared`, `ui`, `testes-sync` | 4, 5, 36, 30, 48 e 27, todos ok | 1 a 3 s cada |
| `npm run setup:test` e `npm run scripts:test` | 4 e 5, ok | < 1 s |
| `npm run api:test` (`./mvnw verify`, Testcontainers) | **1775 testes, 0 falhas, 0 erros, 0 pulados**, BUILD SUCCESS (as 7 mensagens "APPLICATION FAILED TO START" no log são dos testes que provam a recusa de subir sem administrador) | 9 min 47 s |
| `npm run test:duas-pontas -w @corona/testes-sync` | 29 de 29 (9 arquivos: fumaça, envio e recebimento, vários aparelhos, prazo/relógio/versão/fotos, devolução fora do prazo, troca e crédito, pedir acerto, estresse curto) | 5 min 42 s |
| `npm run e2e` (Playwright + Electron, banco 5499 e API 8099 descartáveis) | 7 de 7 (só o Corona Gestão; o Corona Vendas não tem E2E de tela) | 43 s (testes: 41,8 s) |
| `npm run seller:build` | ok | < 5 s |
| `npm run seller:empacotar` | ok (`release/win-unpacked`, sem assinatura) | 9 s |
| `npm run barreira:varrer -- apps/seller-app/release/win-unpacked/resources/app.asar packages/shared packages/ui packages/conexao-segura` | "Varredura limpa: nenhum dos 12 marcadores" | < 1 s |
| `npm audit --audit-level=high` | **falha**: 22 avisos (12 moderados, 10 altos, todos de ferramenta de desenvolvimento); ver M1 | 2 s |

**Instabilidade:** nenhuma nas suítes rodadas uma vez. Não repeti as suítes longas.

## 5. Comparação com a auditoria de 11/11/2026

| Achado de 11/11 | Situação em 17/11 |
| --- | --- |
| A1, A2, A3 (alto) | resolvidos (PRs 76, 78, 77) |
| M1, M2, M4, M5, M6, M7 | resolvidos (PRs 83, 81, 210, 210, 210, 80) |
| M3 (Minhas vendas sem limite) | **em parte**: as consultas por venda viraram lote (PR 210); **falta** limite e paginação |
| M8 (`barreira:varrer` sozinho) | documentação resolvida; o script ainda **não** acha o pacote sozinho |
| B1, B2, B4, B5, B6, B7 | resolvidos (PRs 210, 210, 81, 79, 210, 82) |
| B3 (`npm audit` moderados) | **piorou**: agora há 10 altos (M1 desta revisão) |
| S1 / D1 / D2 | resolvidos (PRs 85 e 84) |

Pendências técnicas ainda abertas fora da auditoria, já registradas: pendências A e C da Fase 5 (seção 1); "desativado" nas mensagens do vendedor (`docs/PROGRESSO.md:262`).

## 6. Decisões pendentes

### Do responsável

- **D-R1. ADR-0015** (Proposto): R1 custo da peça lido na hora ou congelado na venda; R2 horário da conferência diária e as 26 horas; R3 pedir acerto só com internet. Hoje valem os padrões seguros.
- **D-R2. ADR-0007** (Proposto): códigos das etiquetas. Destrava a impressão de etiquetas (seção 1, Fase 1).
- **D-R3. Garantia e troca (M4):** confirmar que valem os 45 dias e o crédito automático da Fase 6 (ADR-0014) no lugar da "garantia de 1 ano" e do "só registrar" de 11/11.
- **D-R4. Estorno pelo vendedor em 72 horas:** fazer a tarefa (mexe na venda e no sync: crítica) ou voltar a decisão para "no mesmo dia", que é o que está no código.
- **D-R5. Semana criada sozinha:** criar a semana de segunda a domingo automaticamente quando a data cair fora de toda semana cadastrada? E a numeração (sequencial, ou o número da semana do ano)?
- **D-R6. `npm audit` (M1):** forçar a versão do `picomatch` por `overrides` ou aceitar a exceção com prazo no CI.
- **D-R7. Fase 9:** contratar a VPS, chave SSH, `rclone` no Drive e `ntfy` (pendências 1 a 4); assinatura do instalador e domínio (11 e 12).

### Da dona

- **D-D1. Venda em cuotas:** qual o prazo máximo (hoje até 24 parcelas mensais, com a primeira até 1 ano depois)? Existem cuotas semanais ou quinzenais?
- **D-D2. Etiquetas** (perguntas 16 a 20): o número que o leitor lê, a impressora, o tamanho, o código das peças novas.
- **D-D3. Prazo para peça com defeito** (§17, item 4): hoje 45 dias, igual ao de arrependimento.
- **D-D4. Desconto e reembolso** (pergunta 6 e §17, item 5): máximo de desconto, limite de reembolso por semana e valor que pede aprovação. A resposta de 13/11 sobre a diferença da troca ("una vez que se quita la etiqueta ya es como vendido") ficou ambígua: confirmar se a diferença a favor do cliente vira crédito (padrão) ou se não há devolução.
- **D-D5. Comprovante para o cliente:** quer um papel (ou mensagem) com a venda e as cuotas?
- **D-D6. Telas** (perguntas 26 a 29) e nota da maleta com foto (5): aprovar as telas como estão.
