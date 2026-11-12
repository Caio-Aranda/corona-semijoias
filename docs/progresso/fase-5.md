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
