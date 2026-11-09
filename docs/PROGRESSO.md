# Progresso

Atualizado pela implementadora ao fim de cada tarefa: o que foi feito, o que ficou pendente e as decisões tomadas.

## Fase 0

### Plano da Fase 0

Lista oficial, aprovada pelo responsável. Os ADRs e as tarefas usam estes números e títulos.

| Nº | Tarefa | Situação |
| --- | --- | --- |
| 1 | docs: registrar na especificação as decisões da Fase 0 (PR 1) | Concluída |
| 2 | docs: glossário da interface (PR 2) | Concluída |
| 2.1 | docs: decisões do responsável, especificação v1.3 e glossário (PR 3) | Concluída |
| 3 | docs: ADRs de fundação | Em andamento |
| 4 | chore: esqueleto do monorepo e higiene do repositório (npm workspaces, TS, ESLint, Prettier, .gitignore, .gitattributes, .env.example, modelo de PR, README) | Pendente |
| 5 | chore(infra): Docker Compose com PostgreSQL de desenvolvimento | Pendente |
| 6 | feat(backend): esqueleto Spring Boot 4.1.x com Flyway, OpenAPI e Testcontainers (UTC, erros em português, grupos dona/vendedor no OpenAPI) | Pendente |
| 7 | ci: GitHub Actions e Dependabot | Pendente |
| 8 | feat(backend): convenções do schema e sync_seq seguro (gatilho, pull com limite seguro, utilitário de dinheiro HALF_UP, teste de convenções, testes de concorrência e de estresse) | Pendente |
| 9 | feat(auth): usuários, perfis e login com JWT (perfis extensíveis, rotas por perfil, primeiro admin pelo .env) | Pendente |
| 10 | feat(auth): a dona cria usuários e redefine a senha temporária (troca obrigatória, regras da senha) | Pendente |
| 11 | feat(auth): sessão longa, limite de tentativas e registro de acessos (refresh com rotação, identificador do aparelho, bloqueio 5/15, prazo offline guardado) | Pendente |
| 12 | feat(api): versão mínima e tipo de app (cabeçalhos; o tipo é conveniência, não segurança) | Pendente |
| 13 | feat(api-client): clientes gerados do OpenAPI, um da dona e um do vendedor | Pendente |
| 14 | feat(ui): tema da marca e componentes base em packages/ui | Pendente |
| 15 | feat(owner-app): Corona Gestão com login e tela inicial | Pendente |
| 16 | feat(seller-app): Corona Vendas com login online e barreira contra código da dona | Pendente |
| 17 | chore: VS Code, comando único de setup e roteiro do teste de uso | Pendente |
| 18 | spike: SQLCipher no Electron dentro do monorepo (tempo limitado, gera o ADR-0005) | Pendente |

### Tarefa 1: especificação v1.1 com as decisões da Fase 0

**O que foi feito**
- `docs/ESPECIFICACAO.md` atualizado para a versão 1.1 (08/11/2026) com as 11 mudanças da tarefa: perfis (§3), API (§5), premissa do aparelho por vendedor (§6), arredondamento HALF_UP escala 2 (§7.5 e §13), tempo máximo offline e limite seguro do pull (§9 e §10), segurança (§11), fluxo Git e árvore do repositório (§14), registro de problemas de uso (§19).
- Nova seção 22, "Histórico de mudanças".
- Criado `docs/PROBLEMAS-DE-USO.md` com um modelo de registro.

**O que ficou pendente**
- Nada desta tarefa. Seguem fora do escopo: glossário (tarefa 2), ADRs (tarefa 3), pendências do cliente (§17 e §21).

**Decisões tomadas**
- §3: o texto diz que a API recusa o perfil incompatível com o app no login e que cada rota exige o perfil certo. Não diz "valida o tipo de app por rota", para não contradizer o §11 (o cabeçalho de tipo de app não é segurança). A forma de implementar os perfis fica para o ADR-0004 e a tarefa 9.
- Os termos "issue", "Closes #", "main protegida" e `loja-semijoias` aparecem só na seção 22. Nas seções 14 e 19 o novo fluxo é descrito sem citá-los.
- Os nomes exatos dos cabeçalhos de versão e de tipo de app não foram definidos na especificação.
- A estratégia do limite seguro do pull não foi detalhada; fica no ADR-0002.

### Tarefa 2: glossário da interface e especificação v1.2

**O que foi feito**
- Criado `docs/glossario-interface.md` com as 10 partes pedidas: termos da loja, lista de palavras proibidas em dois blocos com marcadores (A, sem diferenciar maiúsculas; B, só a forma exata em maiúsculas), situações na tela, botões e posição, modelo de mensagem, mensagens padrão da Fase 0, estado de conexão do app do vendedor, formatos, termos a confirmar e dúvidas para a dona.
- `docs/ESPECIFICACAO.md` na versão 1.2 (09/11/2026): §14 passa a dizer que o prefixo da branch é o tipo do Conventional Commits; §22 com a entrada da v1.2. Nenhuma outra linha mudou.

**O que ficou pendente**
- Verificador automático das palavras proibidas (fora de escopo; o glossário só entrega as listas).
- As 12 dúvidas da parte 10 do glossário, que dependem da dona ou do responsável.

**Decisões tomadas**
- "Cancelar" segue a §19.5: o botão genérico é "desistir sem salvar"; cancelar venda, pagamento ou devolução é ação nomeada com o objeto, com confirmação dizendo que nada é apagado. "Anular" não foi adotado e ficou só como dúvida para a dona.
- Posição dos botões (Voltar no alto à esquerda; Cancelar e depois o botão principal no rodapé, à direita) é proposta deste glossário, pois a especificação só pede posição igual.
- Regras da senha nova e `{minutos}` do bloqueio são valores propostos, a fixar na decisão de autenticação.
- Conflito não resolvido na especificação (registrado como dúvida 12, sem alterar a especificação): a §12 usa "Remessas", "Movimentações" e "sincronização" em nomes de relatório e no aviso de dados, o que conflita com §19.1 e §19.11.

### Tarefa 2.1: decisões do responsável (especificação v1.3 e glossário)

**O que foi feito**
- `docs/ESPECIFICACAO.md` na versão 1.3 (09/11/2026): nomes Corona Gestão e Corona Vendas (§18); "leia o código de novo" no lugar de "bipe novamente" (§8); senha nova, senha da própria dona, prazo offline esgotado e bloqueio de 15 minutos após 5 erros (§11); `configuracoes` com tentativas de login e tempo de bloqueio (§9); novo aviso de dados e nota sobre nomes de relatório (§12); pendência 12, "Entrada" (§17); regras de "vendedor", "ler o código", "produto" e "peça" (§19.1); "envios com problema" (§19.3); entrada v1.3 na §22.
- `docs/glossario-interface.md`: seção "Decisões tomadas"; "bipar" e "bipe" no bloco A de palavras proibidas; nomes dos aplicativos nos termos e nas mensagens de aplicativo errado; senha, bloqueio e prazo offline com os valores definitivos; textos de "itens" e "vendas" aguardando envio; "Entrada" como a confirmar; parte 10 só com a dúvida da "Entrada".

**O que ficou pendente**
- Pendência 12 da §17 ("Entrada"): precisa de resposta do cliente antes da Fase 3. A regra de negócio não foi definida.
- Nome de tela de cada relatório: definido na tarefa daquele relatório.

**Decisões tomadas**
- Acréscimo autorizado pelo responsável: a §9 `configuracoes` passou a listar "tentativas de login antes do bloqueio (padrão 5)" e "tempo de bloqueio (padrão 15 minutos)", coerente com a §11.
- A §7, a §8 e a §20 continuam com "bipar" como descrição técnica, como a decisão permite.
- A §12 e a §22 da especificação não alteram nenhuma outra regra.

### Tarefa 3: ADRs de fundação

**O que foi feito**
- Criado `docs/decisoes/` com `README.md` (índice e modelo de ADR) e quatro ADRs, todos com status Proposto, data 09/11/2026:
  - ADR-0001, stack e versões: 16 itens com versão exata, fonte oficial, data da consulta e prazo de suporte (ou "sem data publicada"); regra de atualização (Dependabot; major exige ADR novo; exceção proposta para o Electron).
  - ADR-0002, convenções de dados e `sync_seq` seguro: UUID v7, tipos, arredondamento HALF_UP por valor com exemplos, dinheiro como texto decimal no JSON, campos de controle e quem preenche cada um, tabelas sincronizáveis e só do servidor, exclusão lógica no pull, pull seguro por `(sync_xid, sync_seq)` com prova, procedimento de restauração de backup, transações longas, testes de concorrência e de estresse da tarefa 8, idempotência.
  - ADR-0003, separação entre o Corona Gestão e o Corona Vendas: grupos de rotas, DTOs, clientes gerados, barreira em três camadas, dois builds.
  - ADR-0004, autenticação e sessão: JWT HS256 de 15 minutos, sessão longa com rotação e janela de tolerância, tokens no `safeStorage`, aparelho, prazo offline, BCrypt, tentativas, redefinição da senha da dona, perfis extensíveis, cabeçalhos `X-App-Versao` e `X-App-Tipo`.
- Seção "Plano da Fase 0" acima, com a lista oficial das tarefas 1 a 18.
- O desenho do `sync_seq` foi provado à parte num PostgreSQL 18.6 (pull fora de ordem, savepoint, rollback, lápide e restauração de backup). O script não foi versionado.

**O que ficou pendente**
- Revisão e merge do PR 4 pela orquestradora.
- Dúvidas que continuam abertas: base da comissão (§17.6, ADR-0002); desconto em porcentagem por linha (confirmar na Fase 4); retenção de `sessoes` e `tentativas_login` (ADR futuro).
- ADR-0005 (SQLCipher) fica para a tarefa 18.

**Decisões tomadas**
- UUID v7; arredondamento por valor gravado (total = soma dos valores arredondados); desconto em porcentagem por linha (padrão proposto, a confirmar na Fase 4); comissão descrita nos dois casos, sem escolher a base.
- Dinheiro como texto decimal no JSON e centavos inteiros só para conta no TypeScript.
- `sync_xid` (`xid8`) ao lado do `sync_seq`, cursor com época, reescrita do `sync_xid` na restauração, limites de transação só para o usuário da aplicação.
- JWT HS256 com `kid`, 15 minutos; refresh com SHA-256, 30 dias, rotação, janela de 2 minutos com sucessor derivado; BCrypt custo 12; token restrito na senha temporária; bloqueio 5/15 por login + IP e limite separado de 50 erros por IP; versão mínima na configuração da aplicação; resposta 426 com exceção para a saúde do servidor e a documentação.
- Na primeira entrega, nenhum arquivo da especificação, do glossário ou do CLAUDE.md foi alterado (a especificação mudou na segunda, abaixo).

**Decisões aprovadas pelo responsável (09/11/2026)**
- Os ADRs 0001 a 0004 passam a **Aceito**.
- Custo do produto: `produtos_custos` (só do servidor, 1 para 1 com `produtos`, gravada na mesma transação, nunca sincronizada). ADR-0002 e ADR-0003 atualizados.
- Spring Boot: política "Spring Boot 4, a versão menor mais recente com suporte; versão exata no ADR-0001". Hoje é a 4.1.2; a 4.2 entra pelo Dependabot com o CI verde, sem ADR novo.
- Versões aceitas: TypeScript 6.0.3, @hey-api/openapi-ts 0.99.0 (versão fixa), Flyway 12.4.0, Node 26.13.0 com npm 11.20.0, electron-builder 26.18.0 e electron-updater 6.8.11. Exceção do Electron aceita (versões novas sem ADR novo, sempre entre as 3 com suporte).
- Parâmetros técnicos de autenticação ficam na configuração do servidor, sem tela; `configuracoes` mantém só as chaves da §9.
- Senha temporária vale 7 dias (a tarefa 10 implementa e acrescenta ao glossário a mensagem "senha temporária vencida"); a senha definitiva não expira.
- Muitos erros num mesmo login vindos de IPs diferentes: sem bloqueio; mais de 20 erros em 1 hora geram aviso em "O que precisa da sua atenção" (limite no servidor).
- ADR-0002: a época viaja dentro do cursor `época:xid:seq`; o valor atual fica na tabela do servidor `sync_estado` (escolha da implementadora, porque `configuracoes` só guarda as chaves da §9); na Fase 4 a situação da venda é calculada a partir dos lançamentos, não gravada por cima.
- **Especificação v1.4 (09/11/2026):** cabeçalho; §5 (Spring Boot 4, versão menor mais recente com suporte); §9 (`produtos` sem `preco_custo`, nova `produtos_custos`); §11 (senha temporária de 7 dias, senha definitiva sem expiração, alerta sem bloqueio para muitos erros num login); §22 com a entrada v1.4. Nenhuma outra regra mudou.
