# Fase 5: offline e sincronização (detalhe das tarefas)

O plano está em [fase-5-plano.md](fase-5-plano.md); o desenho, no [ADR-0013](../decisoes/ADR-0013-sincronizacao-e-offline.md) e no [ADR-0005](../decisoes/ADR-0005-sqlcipher-no-electron.md).

## Tarefa 5.2: banco local criptografado (Corona Vendas)

**O que entrou**

- `better-sqlite3-multiple-ciphers` **13.0.3 exata** em `dependencies` do `apps/seller-app` (fora do pacote do Vite pelo `externalizeDeps` padrão). No instalador vai só `lib/`, `package.json` e o binário `win32-x64` (desempacotado do `app.asar`); sem `deps/`, `src/` nem binários de outras plataformas.
- `src/main/banco/` (processo principal, TypeScript): `banco-local.ts` (abre com SQLCipher 4, chave bruta de 256 bits, WAL, `synchronous=FULL`; migrações com hash e `user_version`; cópia antes de migrar, guardando as duas últimas, e volta para a cópia se a migração falhar; recusa de banco mais novo que o app; disco cheio traduzido; `.ilegivel`), `chave-do-banco.ts` (chave embrulhada pela senha com scrypt e AES-256-GCM, 5 erros seguidos bloqueiam até entrar com internet, resgate pelo cofre do Windows quando a dona redefine a senha), `migracoes.ts` (esquema local 1) e `servico-do-banco.ts` (com internet: cria ou abre, reembrulha, guarda à parte o que não abre; sem internet: só pela senha, a tarefa 5.8 usa).
- **Esquema local 1:** cópias das 15 tabelas sincronizáveis do servidor (só as colunas da lista explícita, mais `version`, `deleted_at` e `origem_local`), `outbox` (ordem, `operacao_id` único, tipo, formato, JSON exato, hora local, último contato com o servidor, situação, tentativas, resultado, "Entendi") e `estado_sync`. Dinheiro em texto ("12.30"), nunca ponto flutuante. Nada de custo, estoque da loja ou fornecedor.
- O banco é preparado a cada **entrada com internet** (a senha acabou de ser conferida pelo servidor) e na troca obrigatória de senha. Uma falha do banco não derruba a entrada (as telas ainda usam a internet até a 5.7). A senha nunca é guardada nem registrada.
- Glossário: textos do banco local e da senha conferida sem internet.

**Itens pendentes do spike (ADR-0005), provados:** SQLCipher padrão (Zetetic 4.6.1) abre o arquivo; barreira do pacote limpa com o módulo dentro; o módulo carrega no pacote empacotado com o Node do Electron 45; processo morto no meio do checkpoint do WAL não corrompe; disco cheio desfaz a transação inteira; testes no job `frontend` do CI. **Tempo do `npm ci` no Ubuntu:** medido pelo CI deste PR (ver o PR).

**Testes (Vitest, SQLCipher de verdade, arquivos de verdade):** `banco-local.test.ts` (chave certa e errada, arquivo sem os dados em claro, parâmetros do SQLCipher 4, arquivo estragado, `.ilegivel`, esquema, dinheiro em texto, migração editada, cópia antes de migrar e só as duas últimas, migração que falha volta para a cópia, banco mais novo, disco cheio, processo morto, espaço livre), `chave-do-banco.test.ts` (senha certa, 5 erros, conta zerada, senha redefinida, chave perdida, padrão lento), `servico-do-banco.test.ts` (primeira entrada, sem internet, senha redefinida, chave perdida, banco estragado) e `servico-de-sessao.test.ts` (o banco preparado só com a senha conferida, sem derrubar a entrada).

**Decisões que tomei:** scrypt (N = 2^17, r = 8, p = 1) em vez de Argon2id, porque o Argon2id no Node 24 ainda é experimental e o ADR permite os dois; o próprio GCM é o verificador lento (sem hash separado da senha); o arquivo da chave é `dados/chave.json` (o ADR dizia `chave.bin`, de antes da decisão da senha); a conta dos 5 erros fica no arquivo da chave e só a entrada com internet a zera; banco ilegível só é movido com internet (sem ela, não há como baixar de novo); o banco é preparado na entrada com internet, e a abertura sem internet pedindo a senha fica para a 5.8, que faz a tela.

**Ficou para depois:** a tela de senha ao abrir sem internet e o prazo offline (5.8); avisar o servidor do computador "reiniciado" (5.6); mostrar o aviso de pouco espaço (5.7); queda de energia de verdade (sem como testar automaticamente).

## Tarefa 5.3: computadores do vendedor (backend)

**O que entrou**

- Migração **V031** (`dispositivos`, só do servidor): situação `ATIVO`, `AGUARDANDO_LIBERACAO`, `SUBSTITUIDO` ou `BLOQUEADO`, primeiro e último contato, versão do app, campos do envio (último pull, último push, último envio completo, contagem de "aguardando") que a 5.4 e a 5.5 preenchem, e liberação, troca e bloqueio. Índice único parcial: **um computador ativo por pessoa** (o banco garante). Os computadores que já tinham entrado (Fase 4) vêm das sessões: o de uso mais recente de cada vendedor fica ativo, os outros esperam a dona.
- **Chave estrangeira** de `origem_dispositivo` (pendência da tarefa 8) e de `dispositivo_id` em `remessas`, `movimentos_remessa` e `conferencias` para `dispositivos`, `NOT VALID` (só linhas novas ou alteradas). `aplicar_convencoes_sincronizavel` passa a criar a mesma chave nas sincronizáveis novas.
- **Login** registra o computador (`ServicoDeAparelhos`): o primeiro do vendedor nasce ativo; os seguintes entram mas esperam a dona. Computador de outra pessoa: 403 `APARELHO_DE_OUTRA_PESSOA`. Computador bloqueado: 403 `APARELHO_BLOQUEADO` com `acao: APAGAR_DADOS`. A renovação guarda o último contato e a versão.
- **Rotas do vendedor** (`/api/vendedor/**`) só para o computador ativo (`InterceptadorDoAparelho`): aguardando liberação dá 403 `APARELHO_NAO_LIBERADO`; trocado, 403 `APARELHO_SUBSTITUIDO` (o push da 5.5 vai ser a exceção, marcada com `@AceitaComputadorTrocado`); bloqueado, 403 com a ordem de apagar. `/api/auth/eu` e a saída continuam para o computador que espera a dona mostrar o aviso.
- **Rotas da dona:** `GET /api/dona/vendedores/{id}/aparelhos`, `POST /api/dona/aparelhos/{id}/liberar` ("Trocar computador": o antigo vira trocado e as maletas abertas dele passam para o novo) e `POST /api/dona/aparelhos/{id}/bloquear` (encerra as sessões com o motivo novo `APARELHO_BLOQUEADO`). Tudo na auditoria. A liberação por maleta da 4.3 saiu.
- **Ordem de apagar** (`acao: APAGAR_DADOS`, ADR-0013 item 14) no token, na renovação e no login do computador bloqueado e do vendedor desativado. O reuso do token, a saída e a senha trocada continuam no 401 de "entre de novo". O vendedor reativado não recebe a ordem.
- **Vender só depois de conferir** (decisão de 11/11/2026): venda de maleta enviada e não conferida é 409 `MALETA_NAO_CONFERIDA`; só a conferência liga a maleta ao computador. No Corona Vendas, a tela Vender só lista maletas conferidas e diz "Confira todas as peças da maleta antes de vender." quando só há maleta por conferir.
- Glossário: textos dos computadores e da venda antes de conferir.

**Testes (escritos antes do código):** `AparelhosTest` (primeiro e segundo computador, troca com as maletas, antigo trocado só envia, 403 cruzado e rotas da dona fechadas ao vendedor, bloqueio com a ordem em toda chamada, token copiado sem ordem, desativado com ordem e a dona sem, reuso só pede para entrar, venda antes de conferir, chave estrangeira da origem). Ajustados: os testes de login, renovação, senha temporária, tentativas e versão do app passam a usar o computador de cada vendedor (`UsuariosDeTeste.aparelhoDe`), porque um computador é de uma pessoa só; o vendedor desativado agora recebe 403 com a ordem; os testes de venda conferem a maleta antes.

**Decisões que tomei:** ver a linha datada da tarefa 5.3 no ADR-0013.

**Ficou para depois:** telas da dona (5.10); preencher último pull/push e "aguardando" (5.4/5.5); a quarentena antes de apagar e o apagar no app (5.8); marcar o computador "reiniciado" quando o banco local fica ilegível (5.6/5.8).

## Tarefa 5.4: pull do vendedor (backend)

**O que entrou**

- `GET /api/vendedor/sync/pull` (cursor, limite 500 por padrão e 1000 no máximo, `tabela` para recarregar uma só): linhas com tabela, id, `version`, lápide e as colunas da lista explícita; cursor; `temMais`; hora do servidor; bloco `parametros`. Cursor de época antiga: 409 `CURSOR_INVALIDO`. Só o computador em uso (5.3); guarda o último pull em `dispositivos`.
- `GET /api/vendedor/sync/conferencia` (quantidade e SHA-256 de `id:version` por tabela) e `POST /api/vendedor/sync/conferencia/diferencas` (alerta de defeito no log).
- `RegraDoVendedor` em cada `TabelaSincronizavel` (`RegistroDeTabelas`): cotações, categorias e semanas para todos; clientes da loja com o diretório para todos e o cadastro só para quem atende; maletas enviadas do vendedor (rascunho nunca); itens e movimentos das maletas dele; peças que já estiveram numa maleta enviada a ele, com códigos e fotos; vendas, itens, pagamentos e conferências dele. `FiltroPorVendedor` aplica a regra com o vendedor do token.
- **Expansão** (ADR-0013, item 5, regra 1) no `ServicoDePull`, na mesma fotografia do banco: a maleta leva os itens; o item leva a peça, os códigos e as fotos. As expandidas passam também pelo filtro e não mexem no cursor.
- Migração **V032**: gatilhos que impedem a pertença de mudar depois de entregue (o que dispensa a tabela de saídas) e índices do filtro.
- Glossário: texto do `CURSOR_INVALIDO`. Clientes da API regerados.

**Testes (escritos antes, PostgreSQL de verdade):**
- `OraculoDeDoisVendedoresTest`: o oráculo de dois vendedores com semente impressa (300 operações por padrão; rodei 600): a dona cria peças, monta, envia e exclui maletas, muda preço, troca foto, cria e exclui código, cadastra e transfere cliente; A e B conferem e vendem; puxam em momentos aleatórios com lotes de 1 a 50. A cópia de cada um fica igual ao oráculo (id, versão, lápide), nada privado do outro, nenhum cadastro de cliente do outro, nenhuma coluna de custo. **Prova de que pega o furo:** com a expansão desligada, o oráculo falha (códigos da peça antiga faltando).
- `PullDoVendedorTest`: peça antiga com código e foto chegando quando entra numa maleta nova; rascunho e rascunho excluído nunca chegam; troca de foto e código excluído; cliente transferido nos dois sentidos; vendas, conferências e maletas do outro nunca chegam; dinheiro como texto; erros da rota (cursor, limite, tabela de fora, dona, sem token, computador aguardando); recarga de uma tabela; conferência por resumo igual ao que o app guardou; gatilhos da V032. Toda resposta passa pela varredura dos marcadores proibidos (mais `custo`, `estoque_atual` e `fornecedor_id`).
- `RegrasDoVendedorTest`: toda sincronizável com regra; toda coluna de pertença garantida no banco; expansões válidas; na dúvida, nada.
- `EstresseDoPullComFiltroTest` (20 s em todo PR; 10 min no semanal, junto do estresse da Fase 0): 4 escritores mudando pertença (peças entrando em maletas de dois vendedores, fotos, códigos, preços, conferências, vendas) enquanto o vendedor puxa sem parar; no fim, a cópia igual ao oráculo (cerca de 6 mil operações em 20 s).

**Decisões que tomei:** ver a linha datada da tarefa 5.4 no ADR-0013.

**Ficou para depois:** o motor do app que aplica o pull (5.6); a métrica do defeito do filtro e da duração do pull (5.11).

## Tarefa 5.5: push do vendedor (backend)

**O que entrou**

- `POST /api/vendedor/sync/push`: até 50 operações ou 512 KB (413 `LOTE_GRANDE_DEMAIS`), um envio por vez por computador (429 `ENVIO_EM_ANDAMENTO`, com `Retry-After`), **uma transação por operação** com a linha do `sync_log`, resultado por operação (`ACEITO`, `JA_RECEBIDO`, `CONFLITO`, `RECUSADO`, `NAO_PROCESSADO`), parada do lote no erro passageiro e no limite de 20 s. O computador trocado ainda envia (e recebe `apagarDados` no fim).
- `OperacoesDoVendedor`: formato 1 de `VENDA`, `ESTORNO_VENDA`, `CLIENTE_NOVO`, `CLIENTE_ALTERADO` e `CONFERENCIA`, a forma canônica do resumo e a aplicação pelos **mesmos serviços da Fase 4** (`ServicoDeVendas`, `ClientesDoVendedor`, `ServicoDeConferencia`).
- **Hora corrigida e limitada** (ADR-0013, item 11): a venda e o estorno recebem a hora do computador corrigida pela diferença do relógio dele; correção acima de 5 minutos marca a venda (`hora_ajustada`); o dia da cotação e o "mesmo dia" do estorno saem dela.
- **Cliente campo a campo** (`ClientesDoVendedor.alterar`): só quem atende; vale a última chegada, e o valor da dona substituído vai para a auditoria (`CLIENTE_VALOR_SUBSTITUIDO`). CPF repetido de cadastros feitos sem internet: `CONFLITO` para a dona.
- **Envios com problema** (`/api/dona/envios-com-problema`): listar, **Tentar de novo** (mesma operação, mesmo id, mesma hora) e **Descartar** (com motivo), tudo auditado. A tela é da 5.10.
- Migração **V033**: `sync_log` (JSON completo por 90 dias, depois só o resumo, por `UPDATE` na agenda diária) e `vendas.hora_ajustada`. `dispositivos` passa a guardar último push, "aguardando" e último envio completo.
- Glossário com os textos novos; clientes da API regerados.

**Testes (escritos antes, PostgreSQL de verdade):** `PushDoVendedorTest` (aceito, já recebido e conflito no reenvio; JSON equivalente em outra ordem; recusa guardada com o JSON e saldo que nunca fica negativo; a dona descarta e tenta de novo depois de corrigir a causa; dependência recusada; maleta cancelada e de outro vendedor; segundo computador, dona e lote grande; relógio 3 dias adiantado e 2 atrasado; hora impossível no limite; estorno no limite do dia em Brasília; dois envios do mesmo computador ao mesmo tempo sem duplicar; cliente campo a campo com o valor substituído; cliente de outro vendedor; CPF repetido em conflito; computador trocado enviando e recebendo a ordem de apagar; retenção de 90 dias) e `ServicoDeEnvioTest` (erro passageiro para o lote ali, sem quebrar a ordem).

**Decisões que tomei:** ver a linha datada da tarefa 5.5 no ADR-0013.

**Ficou para depois:** o motor do app que envia (5.6); a tela dos envios com problema e os avisos (5.10); push abaixo da versão mínima (5.13); métrica da duração do push (5.11).

## Tarefa 5.6: motor de sincronização (Corona Vendas)

Plano aprovado: [plano-5-6-motor-de-sincronizacao.md](plano-5-6-motor-de-sincronizacao.md).

**O que entrou** (tudo no processo principal, `apps/seller-app/src/main/sync/`, com as dependências injetadas)

- `aplicador-do-pull.ts`: cada lote numa transação só, junto com o cursor. `version` maior troca, igual ou menor é ignorada; lápide marca `deleted_at`; provisória com o mesmo id vira a do servidor; linha de outro id nunca toca numa provisória. Tabela ou coluna fora da lista (`colunas.ts`, a mesma do `RegistroDeTabelas`), número quebrado ou objeto recusam o lote inteiro.
- `camada-de-pendentes.ts`: as alterações de cliente ainda aguardando envio são reaplicadas por cima da cópia depois de cada lote que tocou o cliente.
- `operacoes-locais.ts` e `outbox.ts`: venda, estorno, cliente novo, cliente alterado e conferência gravam a cópia provisória e a operação na mesma transação, com o JSON exato do formato 1 da 5.5. Totais e saldo em centavos inteiros (`src/shared/dinheiro.ts`); CPF repetido conferido no diretório local; disco cheio desfaz tudo. Migração local **2** (`provisorias`): de qual operação é cada linha provisória.
- `ciclo.ts`: um ciclo por vez. Saúde (`/actuator/health`, 5 s) → push (até 50 operações ou 512 KB, `dados` byte a byte, 413 pela metade, 429 com `Retry-After`, resultado por operação numa transação) → pull até `temMais = false` (409 `CURSOR_INVALIDO` recarrega só as cópias do servidor) → conferência por resumo uma vez por dia e depois da carga inicial (diferença passageira confere de novo; persistente avisa `/conferencia/diferencas` e recarrega só a tabela).
- `agenda.ts`: abrir, internet voltar, a cada 60 s, 2 s depois de gravar (agrupando); falha com espera 2, 4, 8 s... até 5 min, ±25% sorteado.
- `motor.ts` e a ponte: canal novo `aoMudarASincronizacao` com `{ comInternet, enviando, aguardando, vendasAguardando, recusadas, dadosAte, naoConseguiuEnviar }`, limpo no processo principal e no preload. O motor liga depois de entrar com o banco aberto e desliga ao sair, ao perder a sessão e por ordem do servidor (ponto de extensão da 5.8). A tela é da 5.7.
- Glossário: textos das operações feitas sem internet e o aviso da operação que não consegue ser enviada.

**Testes (escritos antes, Vitest, SQLCipher de verdade e servidor falso em `node:http`):** `aplicador-do-pull.test.ts` (fora de ordem, repetida, igual e menor, lápide, provisória do mesmo id, provisória de outro id intocada, lote recusado por tabela, coluna, custo, número quebrado; cursor junto; falha no meio; processo morto), `colunas.test.ts` (nenhuma coluna de custo, fornecedor ou estoque; lista igual ao esquema), `camada-de-pendentes.test.ts`, `operacoes-locais.test.ts` (mesma transação com falha injetada, JSON exato, ordem, disco cheio, saldo, estorno, CPF, desfazer idempotente, limpeza só das enviadas), `ciclo.test.ts` (cada resultado; RECUSADO desfaz na mesma transação e é idempotente; NAO_PROCESSADO não pula; resposta perdida dá JA_RECEBIDO sem duplicar; 413; 429; 50 e 512 KB; push antes do pull; sem internet; um ciclo por vez; sessão renovada uma vez; época; conferência), `agenda.test.ts`, `motor.test.ts` (só contadores e datas: varredura sem token, caminho, id ou dado de cliente), mais a ponte e a sessão.

**Decisões que tomei:** ver a linha datada da tarefa 5.6 no ADR-0013.

**Ficou para depois:** telas e "Entendi" (5.7); prazo offline, quarentena, apagar os dados e abrir sem internet ligando o motor (5.8); fotos (5.9); duas pontas reais e rede ruim (5.12); atualização com envios aguardando (5.13).

## Tarefa 5.11: métricas e alertas do sync (backend)

**O que entrou:** `MetricasDoSync` (Micrometer, sem etiqueta alguma, então sem dado de vendedor): `corona.sync.xmin.idade.segundos` (idade da transação de escrita mais antiga em `pg_stat_activity`, só as com identificador, que são as que seguram o limite seguro), `corona.sync.pull.duracao`, `corona.sync.push.duracao`, `corona.sync.conferencia.defeitos` (conta as tabelas avisadas em `/conferencia/diferencas`) e `corona.sync.computadores.sem.enviar`. `AlertaDeAtrasoDoSync` confere a cada 15 s: passou de 30 s, ERRO no log (uma vez por episódio, e avisa quando volta ao normal); computador parado, AVISO no log uma vez por computador. `SaudeDoSync` (indicador `sync` do painel de saúde, sempre `UP`: atraso é alerta, não queda; números nos detalhes). `ComputadoresParados` e `FonteDeAvisosDosComputadores` levam à tela da dona "O computador de X está há N dias sem enviar" e "X entrou num computador novo" (textos no glossário).

**Quando um computador está parado:** `ATIVO`, de vendedor ativo, e (sem falar com o servidor há 3 dias ou mais) ou (disse ter operações aguardando e o último envio dele tem 3 dias ou mais). Prazo em `corona.sync.aviso-sem-enviar-dias` (dúvida D4).

**Testes (escritos antes, PostgreSQL de verdade):** `MetricasDoSyncTest` (transação aberta faz a métrica subir e o alerta sair, uma vez só, e some quando ela fecha; computador parado, em dia, trocado, bloqueado e de vendedor desligado; envio aguardando com contato recente; computador novo; pull, push e defeito viram métrica; nenhuma etiqueta).

**Decisões que tomei:** (1) as métricas ficam registradas no Micrometer, mas o `/actuator/metrics` **não** foi exposto (hoje só `health` está; expor é decisão de segurança da VPS) e a leitura de cada uma está no log e no painel de saúde; (2) o indicador de saúde não derruba o `/health`, para o monitor da VPS não reiniciar a API por causa de uma venda lenta; (3) a conexão da API só enxerga as próprias transações em `pg_stat_activity`, e as da migração e do administrador não contam (não escrevem durante o uso); (4) o aviso de "envios com problema" já existe na 5.5/5.10 e não foi repetido aqui.

**Ficou para depois:** a tela da dona mostrar os dois tipos novos de aviso com o botão certo (5.10); expor as métricas se a VPS ganhar um coletor.
