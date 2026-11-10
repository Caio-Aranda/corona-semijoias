# ADR-0005: SQLCipher no Electron

- **Status:** Proposto
- **Data:** 09/11/2026
- **Tarefas que implementam:** 18 (spike); a integração no Corona Vendas é da Fase 5

## Contexto

A especificação (§10 e §11) manda o app do vendedor gravar tudo num SQLite **criptografado**, porque notebook de vendedor pode ser perdido ou roubado, e cita `better-sqlite3-multiple-ciphers` como candidata a conferir. O ADR-0004 (itens 3 a 5) já decidiu que os segredos do app ficam no processo principal e protegidos pelo `safeStorage`, e que o banco local passa a guardar o identificador do aparelho na Fase 5. O ADR-0001 deixou para esta tarefa os testes que dependem do Node embutido do Electron.

O medo real é um módulo nativo que só compila na máquina do desenvolvedor: a dona e os vendedores não podem precisar de compilador. Por isso o spike mediu tudo no Windows desta máquina, antes da Fase 1 pagar por uma escolha errada.

Todas as medições abaixo foram feitas em 09/11/2026, neste computador (Windows 11, Node 26.13.0 de desenvolvimento, Electron 45.2.3 com Node 24.22.2 embutido e ABI 150). Um só computador: servem de ordem de grandeza, não de garantia.

## Decisão

1. **Biblioteca: `better-sqlite3-multiple-ciphers` 13.0.3, versão exata, sem `^`.** Fica como dependência de execução (`dependencies`, não `devDependencies`) do `apps/seller-app` **na Fase 5**, não antes.
2. **Cifrador: SQLCipher 4** (`cipher='sqlcipher'`, `legacy=4`): AES-256-CBC, HMAC-SHA512, PBKDF2-SHA512 com 256000 voltas e página de 4096 bytes. Valores lidos do próprio banco no spike: `kdf_iter=256000`, `kdf_algorithm=2`, `hmac_algorithm=2`, `page_size=4096`, motor SQLite 3.53.4.
3. **Chave: 256 bits aleatórios** (`crypto.randomBytes(32)`), entregues ao banco no formato bruto `x'<64 hex>'` (a chave já é aleatória, então o PBKDF2 não acrescenta nada). Gerada na primeira abertura, cifrada pelo `safeStorage` (API assíncrona: no Electron 45 a síncrona já avisa que será removida) e gravada num arquivo ao lado do banco. Só o processo principal enxerga a chave; ela nunca vai para a tela nem para o disco em claro.
4. **Modo do banco:** `journal_mode=WAL`, `synchronous=FULL`, `foreign_keys=ON`.
5. **Migrações:** lista ordenada e só-para-frente. Cada migração roda numa transação que também grava a linha em `_migracoes` (versão, hash SHA-256 do SQL, data) e atualiza `PRAGMA user_version`. Migração já aplicada cujo SQL mudou **derruba a abertura** com mensagem clara: é a regra do CLAUDE.md ("publicada nunca se edita") imposta por código.
6. **Se a chave não abre o banco** (arquivo da chave sumiu, cofre trocado, banco corrompido): o app **não apaga nada e não cria chave nova sobre o banco antigo**. Ver "Riscos" abaixo.
7. **Onde mora o código:** o spike fica em `spikes/sqlcipher/`, **fora dos workspaces**, e é descartável. Não virou pacote em `packages/` porque o banco local depende de `safeStorage`, do caminho de dados do app e do esquema da Fase 5, que ainda não existem; um pacote agora seria código sem dono. A Fase 5 reaproveita `app/banco.cjs` (cerca de 100 linhas) dentro do processo principal do `apps/seller-app`.

## Resultados medidos

| Pergunta | Resultado |
| --- | --- |
| Binário pronto sem compilador (Windows x64) | **Sim.** O pacote do npm já traz 8 binários (`win32-x64`, `win32-arm64`, `linux-x64/arm64`, `linuxmusl-x64/arm64`, `darwin-x64/arm64`), `gypfile: false` e nenhum script de instalação. `npm install` no spike: 50 pacotes em 3 s, nada baixado fora do registro. O binário `win32-x64.node` tem 2,37 MB |
| ABI do Electron | O módulo é **N-API** (`node-addon-api`): não depende da ABI do Node 26 nem da do Electron (150). Rodou igual no Node 26.13.0 e no Electron 45.2.3 sem `electron-rebuild` |
| Dentro do Electron 45 (processo principal) | **Passou.** `npm run electron` em `spikes/sqlcipher`: cria o banco, migra 2 versões, insere e lê, com a chave guardada no `safeStorage` real |
| Dentro de pacote real (`electron-builder --dir`, `app.asar`) | **Passou.** `npm run empacotar` gera `release/win-unpacked` e roda o `.exe` empacotado (`app.isPackaged: true`) com o mesmo resultado. O electron-builder 26.18.0 já tira o `.node` do asar sozinho (testado **sem** `asarUnpack`: também funciona); deixei `asarUnpack` no exemplo para não depender disso. Pacote de teste: 360 MB, quase todo do próprio Electron |
| Chave certa lê, errada falha | Com a chave errada a abertura falha **na hora** (leitura da primeira página) com um erro nosso em português; a mensagem original do SQLite fica em `cause` |
| Arquivo ilegível no disco | O valor conhecido (`Valor-Conhecido`) **não aparece** nos bytes do arquivo, e o cabeçalho não é `SQLite format 3`. A chave também não aparece em claro no arquivo da chave |
| Abre em SQLCipher padrão? | **Não provado com ferramenta externa.** Os parâmetros lidos batem com os padrões do SQLCipher 4, mas não havia `sqlcipher` instalado para abrir o arquivo de fora. Fica para a Fase 5 |
| Migrações | 2 migrações aplicadas, reaplicar é inofensivo, migração editada é recusada (teste automático) |
| Desempenho | Criar e abrir banco novo: **11,8 ms**. Reabrir: **1,6 ms**. 10 mil inserções em uma transação: **20,9 ms**. 1000 consultas por faixa com índice: **4,6 ms** |
| WAL com cifra | Funciona: `journal_mode` volta `wal` |
| App morto no meio da escrita | Um processo abriu a transação, inseriu 5000 linhas e recebeu `SIGKILL`. Ao reabrir: 1 linha (a anterior) e `integrity_check = ok`. A transação é atômica. **Não** testei queda de energia de verdade |
| Impacto no `npm ci` e no CI (Ubuntu) | O `npm ci` da raiz **não muda**: o spike está fora dos workspaces. Quando o módulo entrar no `apps/seller-app`, o Ubuntu baixa o mesmo pacote (cerca de 34 MB descompactado, com os 8 binários) e **não compila nada**; o `win32-x64.node` já vem nele, então o passo "Barreira do pacote" não precisa baixar mais nada. Esse passo **não foi rodado com o módulo dentro** (não integramos nesta tarefa): conferir na Fase 5 |
| Auditoria | `npm audit --audit-level=high` no spike: 0 vulnerabilidades |

Como rodar: `npm test` em `spikes/sqlcipher` (Vitest, 7 testes, sem Electron); `npm run electron` e `npm run empacotar` na mesma pasta (abrem o Electron de verdade, por isso **não rodam no CI**). Os testes do Vitest também não rodam no CI hoje, porque a pasta não é workspace. Rode `npm install` dentro da pasta antes.

## Riscos e o que fazer

**Chave perdida = banco ilegível.** O `safeStorage` no Windows usa o DPAPI, preso ao usuário do Windows. Perdem a chave: reinstalar o Windows, recriar o perfil do usuário, redefinir a senha do Windows pelo administrador, copiar a pasta de dados para outro computador ou apagar o arquivo da chave. Tudo que já foi sincronizado se baixa de novo pelo pull. **O que se perde é a fila `outbox` ainda não enviada** (vendas e movimentos feitos offline).

Mitigações propostas (nenhuma implementada):

- Ao detectar chave ausente ou ilegível, **mover o banco para `.ilegivel`** (não apagar), mostrar uma mensagem simples ("Não conseguimos abrir os dados deste computador. Conecte à internet para baixar tudo de novo. Se havia vendas ainda não enviadas, avise a dona.") e começar um banco novo. O servidor registra o aparelho como "reinicializado" para a dona saber.
- Enviar a `outbox` **o mais cedo possível**: o gatilho "depois de cada gravação" (§10) já faz isso quando há internet; o indicador de pendentes avisa o vendedor para conectar antes de reinstalar ou trocar de computador.
- Avaliar, na Fase 5, uma **segunda cópia da chave** protegida pela senha do vendedor (derivada com Argon2 ou scrypt), para o caso de o DPAPI falhar mas o vendedor lembrar a senha. É decisão de segurança a revisar com o responsável.
- **Cópia do arquivo antes de cada migração** do banco local, para uma migração com defeito não destruir dados.

**Bloqueio remoto (§11).** Apagar os dados locais é apagar o arquivo do banco e o arquivo da chave: sem a chave, o banco já é ilegível. A Fase 5 deve apagar os dois.

## Consequências

### Boas

- Sem compilador em nenhuma máquina: o binário vem no pacote do npm, em N-API.
- Sem `electron-rebuild`: a troca de major do Electron não obriga a recompilar nada.
- Desempenho folgado para o volume de uma loja: ordem de milissegundos.
- Licença MIT; manutenção ativa (última versão estável em 07/08/2026, beta em 21/09/2026).

### Ruins ou custos

- O pacote do npm pesa cerca de 34 MB por trazer os 8 binários e o código-fonte do SQLite. O pacote do Corona Vendas deve **excluir** o que não é `win32-x64` e as pastas `deps/` e `src/` (exemplo em `spikes/sqlcipher/electron-builder.yml`).
- O cifrador é o SQLite3 Multiple Ciphers, **não** o SQLCipher da Zetetic: compatível no formato quando configurado como acima, mas a compatibilidade ainda não foi provada com ferramenta externa.
- A biblioteca é um projeto pequeno (poucos mantenedores). O arquivo é um SQLite cifrado no formato do SQLCipher 4; se a biblioteca for abandonada, a troca por outra que fale SQLCipher 4 não muda o arquivo (desde que a compatibilidade seja confirmada).
- Mais uma dependência de execução no `apps/seller-app`, que hoje só tem `devDependencies` (o electron-vite empacota tudo). O módulo nativo precisa ficar em `dependencies` e fora do pacote do Vite (`externalizeDeps`), senão não entra no `app.asar`.

## Alternativas consideradas

- **`@journeyapps/sqlcipher` 6.0.0** (BSD-3, publicado em 29/04/2026): SQLCipher de verdade, mas o `package.json` roda `node-gyp rebuild` ao instalar e não traz binário. Exigiria compilador (Visual Studio) em quem instala, ou um passo de CI por plataforma. Descartada pelo critério "sem compilador". Não compilei para medir.
- **`@signalapp/better-sqlite3` 9.0.13** (MIT, última publicação em 03/11/2025): fork usado pelo Signal com SQLCipher. Não testei; a última publicação mais antiga e o foco no uso do Signal pesaram contra.
- **`better-sqlite3` 13.0.3 comum**, cifrando só campos sensíveis na aplicação: o arquivo continuaria legível (tabelas, índices, datas e valores), e a especificação pede o banco inteiro cifrado. Descartada.
- **Cifrar o disco (BitLocker)**: depende da configuração de cada computador e não protege o arquivo copiado. Descartada como única proteção; continua recomendável como camada extra.

## Seções da especificação relacionadas

§5 (stack), §10 (offline e sincronização), §11 (segurança do banco local e bloqueio remoto).

## Dúvidas

- A dona aceita o risco de perder a `outbox` num computador reinstalado sem conexão? A mensagem ao vendedor e a cópia da chave pela senha do vendedor ficam para decidir na Fase 5.
- Itens para a **Fase 5 (sync)**: (a) abrir o arquivo gerado com um SQLCipher padrão (por exemplo o `sqlcipher` de linha de comando) e registrar o resultado; (b) rodar o passo "Barreira do pacote" com o módulo integrado e medir o tempo do `npm ci` no Ubuntu; (c) mover `app/banco.cjs` para o processo principal do `apps/seller-app`, em TypeScript, com o caminho de dados do app; (d) testar queda de energia e disco cheio; (e) decidir a cópia antes da migração e o tratamento do arquivo `.ilegivel`; (f) acrescentar o teste do banco ao job `frontend` quando o código virar workspace.
