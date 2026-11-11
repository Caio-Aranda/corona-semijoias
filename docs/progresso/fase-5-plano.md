# Plano da Fase 5: offline e sincronização

Vendedor vende sem internet (§15): SQLite criptografado, outbox, push e pull (inclusive cotações), indicador, bloqueio remoto e testes de sync. O desenho está no [ADR-0013](../decisoes/ADR-0013-sincronizacao-e-offline.md) (Proposto); o banco local no [ADR-0005](../decisoes/ADR-0005-sqlcipher-no-electron.md) (Proposto). Este arquivo é só o plano; o detalhe de cada tarefa feita vai para `fase-5.md`, e a tabela de acompanhamento para `docs/PROGRESSO.md`, quando a orquestradora abrir a fase.

**Antes de começar o código:** a Fase 4 fechada (PRs 68 a 71 ainda abertos em 11/11/2026: rotas do vendedor, telas, conferência com `conferencias` e `itens_conferencia`) e o responsável respondendo às dúvidas R1 e R2. A especificação não muda: tudo cabe nas §6.15, §9, §10, §11, §19 e §20.

## Regras da Fase 5 (valem para todas as tarefas)

- **Modo de plano obrigatório** nas tarefas marcadas: plano escrito e aprovado pela orquestradora antes da primeira linha de código.
- **Testes primeiro**, sempre com PostgreSQL de verdade (Testcontainers) e SQLCipher de verdade, nunca banco em memória. Esforço **alto** em todas, exceto onde o plano diz médio.
- Uma operação do vendedor = registro local + linha da outbox na mesma transação. O app nunca manda preço nem total; o servidor recalcula (ADR-0011).
- O push chama **os mesmos serviços da Fase 4**; nenhuma regra de negócio nova fica só no caminho do sync.
- Tabela sincronizável nova só entra com regra de filtro, expansão e saída (ADR-0013, item 5) e no oráculo de dois vendedores.
- Sem custo, sem dado de outro vendedor; `npm run barreira:varrer` passa com o módulo nativo dentro do pacote.
- Texto de tela novo entra **no glossário antes** de ser usado; nunca "sincronização", "fila" ou "pendente" na tela.
- Migração Flyway nova para cada mudança de banco (próximo número livre na hora); migração local nova para cada mudança do banco do aparelho.

## Tarefas

| Nº | Tarefa | Modo de plano | Depende de |
| --- | --- | --- | --- |
| 5.1 | docs: ADR-0013 e este plano (só documentos) | — | — |
| 5.2 | seller-app: banco local criptografado | **Sim** | R1 (ADR-0005 aceito) |
| 5.3 | backend: aparelhos (`dispositivos`), segundo aparelho, troca de computador e bloqueio | **Sim** | Fase 4 fechada |
| 5.4 | backend: pull do vendedor, filtro, expansão, saídas e conferência por resumo | **Sim** | 5.3 |
| 5.5 | backend: push, `sync_log`, resultado por operação, hora corrigida, cliente campo a campo | **Sim** | 5.3 (e 5.4 já mesclada, pelo número das migrações) |
| 5.6 | seller-app: motor de sincronização no processo principal | **Sim** | 5.2, 5.4, 5.5 |
| 5.7 | seller-app: telas no banco local, indicador e vendas não aceitas | Não (médio-alto) | 5.6 |
| 5.8 | app e backend: prazo offline, abrir sem internet, bloqueio remoto, quarentena e apagar | **Sim** | 5.3, 5.6 |
| 5.9 | seller-app: fotos sem internet | Não | 5.6 |
| 5.10 | owner-app: aparelhos, envios com problema, quarentena, CPF repetido e avisos | Não | 5.3, 5.5, 5.8 |
| 5.11 | backend: métricas e alertas de atraso | Não (médio) | 5.3, 5.4 |
| 5.12 | `testes-sync/`: duas pontas, rede ruim e estresse longo | **Sim** | 5.4, 5.5 (início); 5.6, 5.8 (completo) |
| 5.13 | atualização com envios aguardando e migração entre versões | **Sim** | 5.6 |
| 5.14 | fechamento da Fase 5 (revisão, teste de uso sem internet) | — | todas |

**Ordem sugerida.** Duas trilhas em paralelo: **app** 5.2 e **backend** 5.3 → 5.4 → 5.5 (o backend em sequência, porque as três mexem no pacote `sync`, no `RegistroDeTabelas` e criam migrações). Depois 5.6, e então 5.7, 5.8, 5.9 e 5.11 (podem ir em paralelo se não tocarem os mesmos arquivos), 5.10, 5.13. A 5.12 começa com o simulador de rede e o esqueleto das duas pontas logo depois da 5.5 e fecha com tudo integrado. A 5.14 por último.

### 5.1: docs (esta tarefa)

ADR-0013 (Proposto) e este plano. Nenhum código, nem `PROGRESSO.md` nem o índice dos ADRs (ficam para a orquestradora).

### 5.2: banco local criptografado (modo de plano)

- `better-sqlite3-multiple-ciphers` 13.0.3 exata em `dependencies` do `apps/seller-app`, fora do pacote do Vite, só o binário `win32-x64` e sem `deps/` e `src/` no pacote (ADR-0005).
- O `banco.cjs` do spike vira TypeScript no processo principal: abrir, chave no `safeStorage`, migrações com hash, cópia antes de migrar (duas últimas), `.ilegivel` quando a chave não abre, recusa de banco mais novo que o app, disco cheio.
- Itens pendentes do spike: abrir o arquivo com um SQLCipher padrão e registrar o resultado; barreira do pacote com o módulo dentro; tempo do `npm ci` no Ubuntu; queda de energia (o mais perto possível: processo morto durante o checkpoint do WAL) e disco cheio (`PRAGMA max_page_count`); testes no job `frontend` do CI.
- Schema local inicial: as cópias das tabelas do ADR-0013, item 4 (só as colunas da lista explícita, mais `version`, `deleted_at`, `origem_local`), `outbox`, estado do sync (cursor, contato com o servidor, maior hora local vista) e `_migracoes`.
- **Pronto:** testes do banco (chave certa e errada, arquivo ilegível, migração editada recusada, cópia antes de migrar e volta, banco mais novo, disco cheio, processo morto) verdes no CI; pacote empacotado roda; barreira verde.

### 5.3: aparelhos (modo de plano)

- Migração: `dispositivos` (só do servidor) e chave estrangeira de `origem_dispositivo` para ela nas sincronizáveis (pendência da tarefa 8, só para linhas novas se as antigas não tiverem aparelho cadastrado).
- Login do Corona Vendas registra o aparelho: o primeiro do vendedor `ATIVO`, os seguintes `AGUARDANDO_LIBERACAO`. Rotas da dona: liberar (trocar computador: o antigo vira `SUBSTITUIDO` e as maletas passam para o novo; substitui o `liberar-aparelho` por maleta da 4.3), bloquear (perdido ou roubado), com auditoria.
- Motivos de revogação novos e respostas `APARELHO_NAO_LIBERADO`, `APARELHO_BLOQUEADO` e `USUARIO_DESATIVADO` com a ordem `APAGAR_DADOS`, distintos da revogação por reuso de token (que só pede para entrar de novo).
- **Testes primeiro:** primeiro e segundo aparelho, troca, bloqueio, desativação, cada código de resposta, 403 cruzado, nada de outro vendedor.

### 5.4: pull do vendedor (modo de plano, crítica)

- `GET /api/vendedor/sync/pull` sobre o `ServicoDePull` da Fase 0: `FiltroDoVendedor` do token para cada tabela do ADR-0013 (item 4), expansão (item 5, regra 1), tabela `saidas_do_vendedor` com gatilho em `clientes.vendedor_responsavel_id` (regra 2), bloco `parametros`, `CURSOR_INVALIDO`, limite 500/1000, aparelho precisa estar `ATIVO` ou `SUBSTITUIDO` (este último só recebe a ordem de apagar).
- `GET /api/vendedor/sync/conferencia` (quantidade e SHA-256 de `(id, version)` por tabela).
- `RegistroDeTabelas`: cada `TabelaSincronizavel` passa a declarar filtro, expansão e se a pertença pode mudar; o teste de convenções reprova o que faltar.
- **Testes primeiro:** o **oráculo de dois vendedores** (ADR-0013, item 5, prova 1) com semente impressa; varredura de colunas e marcadores em toda resposta; cada furo conhecido como teste determinístico (itens do rascunho, produto antigo em maleta nova, códigos e fotos antigos, cliente transferido nos dois sentidos, rascunho cancelado nunca aparece); concorrência da Fase 0 com filtro; o estresse de 20 s ganhando mudanças de pertença.

### 5.5: push (modo de plano, crítica)

- `POST /api/vendedor/sync/push`: lote de até 50 operações ou 512 KB, trava por aparelho, uma transação por operação, `sync_log` (migração nova), resultados `ACEITO`, `JA_RECEBIDO`, `CONFLITO`, `RECUSADO`, `NAO_PROCESSADO`, parada do lote no erro passageiro, dependência recusada, resumo canônico.
- Tipos e formato 1: `VENDA`, `ESTORNO_VENDA`, `CLIENTE_NOVO`, `CLIENTE_ALTERADO`, `CONFERENCIA`, despachando para os serviços da Fase 4. Cliente campo a campo com auditoria do valor substituído; CPF repetido aceito e marcado para a revisão da dona.
- Hora corrigida e limitada (ADR-0013, item 11), coluna `hora_ajustada` em `vendas` (migração nova), dia em Brasília para estorno e cotação.
- Recusa vira aviso de "envios com problema"; a dona reprocessa ("Tentar de novo") ou descarta pelo serviço que esta tarefa expõe (a tela é da 5.10).
- Atualiza `dispositivos` (último push, último envio completo, contagem de aguardando).
- **Testes primeiro:** cada resultado; reenvio do lote inteiro e de parte; dois envios do mesmo aparelho ao mesmo tempo; dependência; saldo que ficaria negativo; maleta em outro aparelho e maleta fechada; relógio 3 dias adiantado, 2 atrasado e estorno no limite do dia; JSON equivalente com outra ordem de campos dá `JA_RECEBIDO`; 403 cruzado; nada de outro vendedor nas respostas.

### 5.6: motor de sincronização (modo de plano, crítica)

- No processo principal, um ciclo por vez: push e depois pull, gatilhos (abrir, internet voltar, 60 s, 2 s depois de gravar), espera crescente, lote pela metade em erro de tamanho, renovação em fila única, internet medida pela rota de saúde.
- Aplicação do pull por `version` numa transação com o cursor; lápide; saída apaga a cópia; camada de pendentes de cliente reaplicada por cima; carga inicial e recarga por época mantendo provisórios e outbox; conferência diária por resumo e recarga de uma tabela.
- Operações locais (venda, estorno, cliente novo e alterado, conferência) passam a gravar no banco local e na outbox; saldo e totais de prévia pelo mesmo utilitário de dinheiro do `packages/shared`.
- Ponte para a tela só com o estado (com internet, enviando, quantas aguardam, dados até, recusadas), nunca o banco nem o token.
- **Testes primeiro** (Vitest com SQLCipher real e um servidor falso): aplicação fora de ordem e repetida, cursor e lote atômicos, queda no meio, provisório substituído, recusa desfazendo o saldo, camada de pendentes, recarga por época, conferência com diferença.

### 5.7: telas no banco local e indicador

- Vender, Minha maleta, Meus clientes (com edição de cliente, desenho aprovado pela dona antes, §19) e Meus números leem o banco local e funcionam sem internet; "Dados até" nos relatórios.
- Indicador com os textos da parte 7 do glossário e os novos do ADR-0013, item 18 (no glossário antes); lista de vendas não aceitas com o motivo e "Entendi"; cotação do dia ou a última com a data (ADR-0006).
- **Testes:** cada estado do indicador, venda e estorno sem internet, recusa visível, palavras proibidas, cotação com data.

### 5.8: prazo offline, bloqueio remoto e quarentena (modo de plano)

- App: abrir sem internet com a sessão guardada; prazo de 10 dias pela hora do servidor e pela maior hora local vista; aviso 3 dias antes; esgotado bloqueia o que grava e continua enviando.
- Backend: `POST /api/vendedor/sync/quarentena` e `envios_em_quarentena` (migração nova), autenticado pelo token revogado e pelo aparelho.
- App: ao receber `APAGAR_DADOS`, quarentena e depois apaga (chave primeiro, depois banco, WAL, fotos, token e identificador).
- **Testes primeiro:** relógio adiantado, atrasado, voltado para trás e mudado no meio; prazo no limite; aparelho bloqueado com e sem vendas aguardando; quarentena sem resposta; apagar não deixa arquivo nenhum.

### 5.9: fotos sem internet

- Fila de download separada (miniatura de tudo, depois média; grande sob demanda e cache de 200 MB), arquivo temporário e renomear, conferência do SHA-256 por tamanho, troca de foto, limpeza após acerto ou cancelamento, "Baixando fotos: 45 de 120", "sem foto" nunca trava, menos de 100 ms na leitura.
- **Testes:** troca de foto sem internet, queda no meio do download e retomada, arquivo corrompido, venda sem foto, 500 produtos com 5 fotos (medir disco e tempo).

### 5.10: telas da dona

- Aparelhos do vendedor (situação, último contato, aguardando, último envio completo; trocar computador e bloquear com confirmação clara, §19.5), envios com problema (Tentar de novo e Descartar), vendas de computador bloqueado (aprovar ou descartar), clientes com CPF repetido para revisão, valor substituído de cliente.
- Avisos em "O que precisa da sua atenção": envios com problema, computador sem enviar há N dias, computador aguardando liberação.
- Desenho aprovado pela dona antes; textos no glossário antes.

### 5.11: métricas e alertas

- Idade da transação mais antiga (`corona.sync.xmin.idade.segundos`, alerta acima de 30 s), duração de push e pull, defeito da conferência por resumo, computador sem enviar. Painel de saúde e log.
- **Testes:** transação aberta faz a métrica subir e o alerta sair; aparelho parado gera o aviso.

### 5.12: duas pontas, rede ruim e estresse (modo de plano)

- Pasta `testes-sync/` (workspace novo): o motor real do app rodando em Node contra a API real em Docker (mesmo jeito do `testes-e2e`), vários aparelhos, e um **proxy de rede ruim** com semente (atraso, queda, corte depois de o servidor gravar e antes da resposta, 502, banda baixa).
- Cenários da §10 e §20 que cabem na Fase 5 (ADR-0013, item 20). Estresse curto (cerca de 1 min) em todo PR; longo (10 min) semanal e sob demanda, como o `estresse-sync.yml`, com afirmação final pelo oráculo e pela conciliação.

### 5.13: atualização e migração entre versões (modo de plano)

- Bancos de exemplo de cada versão publicada do schema local, com operações aguardando, versionados no repositório; teste que migra e envia.
- Servidor aceita cada formato por 180 dias; push aceito abaixo da versão mínima quando o formato ainda vale; pull e venda nova recebem 426.
- Antes de instalar a atualização: parar o motor, esperar o envio, tentar mais um, fechar o banco.
- **Testes:** formato 1 depois de existir o 2; app abaixo da mínima com vendas aguardando; atualização no meio de um envio.

### 5.14: fechamento

Revisão da fase, teste de uso com um **vendedor real sem internet** (§19), estresse longo verde, conciliação passando, `PROGRESSO.md` atualizado.

## Dúvidas

Cada uma tem um **padrão seguro**, que vale enquanto não houver resposta.

### Para o responsável

| Nº | Pergunta | Padrão seguro |
| --- | --- | --- |
| R1 | Aceita o **ADR-0005** (SQLCipher com `better-sqlite3-multiple-ciphers` 13.0.3)? | Aceitar, com a 5.2 obrigada a provar os itens pendentes do spike; se um falhar, a 5.2 para e volta ao ADR. Sem o aceite, a 5.2 não começa (o backend pode seguir) |
| R2 | Aceita o **ADR-0013**? | Nada da Fase 5 começa sem ele |
| R3 | Segunda cópia da chave do banco local protegida pela senha do vendedor? | **Não** (vira alvo de força bruta; o caso que salva é raro) |
| R4 | Aceitar o envio de app abaixo da versão mínima, só para as vendas guardadas? | **Sim**, só o push e só formato ainda aceito; o resto recebe 426 |
| R5 | Por quanto tempo o servidor aceita um formato antigo de operação? | **90 dias** depois de publicar o novo (decidido em 11/11/2026; era 180) |
| R6 | O app grava sempre pela outbox, mesmo com internet? | **Sim** (um caminho só); as rotas da Fase 4 ficam para testes e para a dona |
| R7 | Abrir o Corona Vendas sem internet sem pedir senha? | **Não (decidido em 11/11/2026): o app pede a senha do vendedor ao abrir, também sem internet**, com verificador lento local (Argon2id ou scrypt), 5 erros bloqueiam até conectar, chave do banco embrulhada pela senha e DPAPI só para resgate; ver ADR-0005 e ADR-0013 |
| R8 | As rotas ficam em `/api/vendedor/sync/...` (a §10 diz `/sync/push` e `/sync/pull`)? | Sim, pelo ADR-0003; a §10 não precisa mudar (fala do nome curto) |
| R9 | `configuracoes` continua só do servidor e os valores vão no bloco `parametros`? | Sim (o ADR-0002 citava "parte de `configuracoes`" como sincronizável) |
| R10 | A trava de um envio por aparelho na memória (uma instância da API) é aceitável? | Sim, como a trava por IP da tarefa 11; duas instâncias pedem ADR |
| R11 | O custo da expansão (reenviar itens e produtos a cada mudança de estado da maleta) é aceitável? | Sim, até haver medida que mostre o contrário |

### Para a dona

| Nº | Pergunta | Padrão seguro |
| --- | --- | --- |
| D1 | Se a senhora e o vendedor mudarem **o mesmo dado** do mesmo cliente enquanto ele está sem internet, qual vale? | O que **chegou por último** ao sistema (§10), e o valor anterior fica guardado para a senhora ver e voltar |
| D2 | Quando o vendedor cadastra um cliente com CPF que já existe, ele deve ser avisado? | **Não** avisar; o cadastro entra e vai para a sua revisão (assim ele não fica sabendo de cliente de outro vendedor) |
| D3 | O prazo de 10 dias sem internet continua? Avisar o vendedor quantos dias antes? | 10 dias; aviso 3 dias antes |
| D4 | Depois de quantos dias sem enviar a senhora quer ser avisada sobre o computador de um vendedor? | 3 dias |
| D5 | Na troca de computador, quem libera o novo? E as vendas que ficaram no antigo? | A senhora libera; o antigo ainda pode enviar o que tem e depois é apagado |
| D6 | Vendas que chegarem de um computador **bloqueado** (perdido ou roubado): entram direto ou a senhora aprova? | A senhora **aprova uma a uma** |
| D7 | Os notebooks dos vendedores têm senha do Windows? Dá para ligar a proteção do disco (BitLocker)? | Exigir senha do Windows; BitLocker recomendado (o sistema não consegue forçar) |
| D8 | Quando acaba o prazo sem internet, o vendedor também não pode cadastrar cliente? | Bloquear **tudo** o que grava (venda, estorno, conferência, cliente); ele continua vendo os dados e enviando |
| D9 | Venda que o sistema não aceitou (por exemplo, a peça já não estava na maleta): como resolver? | O vendedor vê o aviso; a senhora vê em "envios com problema" e escolhe tentar de novo ou descartar; nada é apagado |
| D10 | A foto grande só aparece com internet, e a conferência da maleta não espera as fotos baixarem. Pode ser? | Sim |
| D11 | O vendedor vê todas as vendas antigas dele mesmo sem internet? | Sim (ficam no computador dele) |
| D12 | Venda cuja hora foi corrigida porque o relógio do computador estava errado: a senhora quer ver uma marca? | Sim, marca "hora ajustada" na venda |

**Mudanças de 11/11/2026 que entram nas tarefas da Fase 5 (ADR-0013, linhas datadas):** (1) **clientes da loja**: o pull manda a todo vendedor o diretório de clientes ativos (nome, CPF, telefone e cidade), a tarefa 5.4 não faz a saída por gatilho de `clientes`, e na 5.5 o CPF repetido é barrado (409 online, conflito para a dona offline); (2) **senha ao abrir o Corona Vendas, também sem internet**, e chave do banco embrulhada pela senha (tarefa 5.2/5.3, banco local e sessão); (3) formatos antigos da outbox por **90 dias**.
