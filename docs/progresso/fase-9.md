# Detalhe das tarefas da Fase 9 mínima

Uma seção por tarefa, no mesmo formato de [fase-2.md](fase-2.md). A tabela está em [PROGRESSO.md](../PROGRESSO.md). As decisões estão no [ADR-0009](../decisoes/ADR-0009-implantacao-certificado-atualizacao-e-backup.md).

### Tarefa 9.1: plano e ADR (só documentos)

**Em revisão.** Nenhum código.

**O que foi feito**

- ADR-0009 (Proposto): arquitetura de implantação, certificado autoassinado fixado pela chave pública, atualização com `electron-updater` sobre o canal fixado, backup, aviso de queda e lista de riscos.
- Seção "Fase 9 mínima" em `docs/PROGRESSO.md` com as tarefas 9.1 a 9.6.

**O que não foi feito:** nada de código ou servidor. Falta o responsável aceitar o ADR.

**Dúvidas:** IP fixo, chave SSH, Google Drive e o tipo de aviso de queda (ver o ADR).

### Tarefa 9.2: implantação da API

**Em revisão.** Pasta `deploy/` e perfil `prod`. Nenhuma regra de negócio, migração ou tela mudou.

**O que foi feito**

- `backend/Dockerfile` (Java 21.0.13 fixo, usuário sem privilégio, o jar em `/app/corona-api.jar`, que o comando de redefinir senha já espera) e `application-prod.properties` (Swagger desligado, erros sem detalhe técnico, desligamento ordenado, sem vendedor de demonstração).
- `deploy/docker-compose.yml`: PostgreSQL 18.6 (sem porta publicada, mesmos parâmetros do desenvolvimento), API (somente leitura, sem privilégios, limite de memória) e proxy Nginx (só ele publica a 443). Rede `banco` interna (sem internet) e rede `web` com faixa fixa, que a API usa para confiar no `X-Forwarded-For` só do proxy.
- Proxy: HTTPS 1.2/1.3, limite de 10 logins por minuto por endereço, `/atualizacoes/` só leitura e sem listagem, tudo o mais (Swagger, `/actuator/env`...) responde 404.
- `deploy/certificado/gerar-certificado.sh` (novo, renovar com a mesma chave, reserva, pin) e o `README.md` do certificado (gerar, renovar, trocar a chave, o que acontece se o IP mudar).
- `deploy/servidor/instalar-servidor.sh` (ufw só 22 e 443, SSH só por chave com trava para não se trancar para fora, fail2ban, atualizações automáticas, UTC, Docker) e `verificar-portas.sh`.
- `deploy/monitoramento/`: verificação de saúde a cada 5 minutos (2 falhas seguidas avisam; avisa de novo só a cada 6 horas; avisa disco acima de 85%), aviso por `ntfy` ou e-mail.
- `deploy/.env.example` e `deploy/README.md`.
- Primeiro acesso: o mecanismo que já existe (`ADMIN_*` no `.env`, troca obrigatória da senha).

**Como foi verificado (de verdade)**

Subi a pilha inteira de produção no computador de desenvolvimento (build da imagem, `docker compose up --wait`, certificado gerado pelo script): os três serviços ficaram saudáveis; `/actuator/health` respondeu pelo HTTPS com o certificado fixado e **falhou com uma impressão digital errada**; Swagger, `/v3/api-docs`, `/actuator/env` e `/` responderam 404; `/atualizacoes/teste.txt` serviu e `/atualizacoes/` deu 403; só o proxy publicou porta (o banco e a API não); o login da dona passou pelo proxy e pediu a troca obrigatória de senha; JSON quebrado voltou com mensagem simples. Um defeito encontrado no teste (healthcheck do proxy, que usava um `wget` sem HTTPS) foi corrigido.

**O que não foi feito (precisa da VPS ou do responsável)**

- `instalar-servidor.sh`, `verificar-portas.sh` e os timers do systemd **não rodaram numa VPS Ubuntu** (só tiveram a sintaxe conferida); o responsável entrega o acesso. O firewall real e o fail2ban só se provam lá.
- IP fixo, chave SSH, escolha do aviso de queda (`ntfy` ou e-mail) e a verificação externa (UptimeRobot): do responsável.
- Aviso de queda por verificação externa fica como recomendação documentada (aceita certificado autoassinado, rota sem dados).

**Dúvidas:** a mesma do ADR-0009 (o tipo de aviso).

### Tarefa 9.3: backup e restauração

**Em revisão.** Scripts em `deploy/backup/` e seção "Backup em produção" em `docs/restauracao-de-backup.md`.

**O que foi feito**

- `fazer-backup.sh` (diário, 03:30 UTC): `pg_dump -Fc` do schema `corona` pelo usuário das migrações, conferido com `pg_restore -l`; cópia compactada das fotos e documentos; soma SHA-256; retenção de 14 dias (nunca menos de 3 cópias); falha avisa o responsável.
- `enviar-semanal.sh` (domingo): `rclone` para o Google Drive do responsável, conferido com `rclone check`, retenção de 8 semanas; recusa enviar backup com mais de 2 dias.
- `testar-restauracao.sh`: restaura um backup num PostgreSQL novo e descartável, roda o procedimento do sync e confere, com o tempo de cada etapa.
- Timers do systemd e `instalar-timers.sh`; o envio semanal só liga quando o responsável cria `/etc/corona/backup.env`.
- Roteiro de restauração em produção e registro dos testes em `docs/restauracao-de-backup.md`.

**Como foi verificado:** com a pilha de produção local, o backup gerou banco (208 KB), arquivos e soma; o teste de restauração num banco limpo voltou 38 tabelas, 1 administradora e 20 migrações, época do sync 2, em 4 s. Defeito achado e corrigido: ler o `.env` com `source` quebra com o nome da dona (tem espaço).

**O que não foi feito**

- **Teste de restauração na VPS, com backup real**: pendente (precisa da VPS) e **obrigatório antes de a dona lançar dado real**. A linha "(a fazer)" do registro fica aberta.
- Envio ao Google Drive: não testado (o responsável autoriza o `rclone`). Snapshot da Hostinger: o responsável agenda no painel.
- Os timers do systemd só tiveram a sintaxe conferida.

**Dúvidas:** criptografar a cópia do Drive com `rclone crypt` (recomendado, mas exige guardar a senha do `crypt` fora da VPS)?

### Tarefa 9.4: instalador do Corona Gestão e certificado fixado

**Em revisão.** Só empacotamento e configuração de conexão em `apps/owner-app`, mais um script na raiz. Telas, cadastros e estoque não mudaram; o Corona Vendas e a barreira não foram tocados.

**O que foi feito**

- `src/main/conexao-fixada.ts`: o app só confia no certificado do servidor pela **impressão digital da chave pública** (a atual e uma reserva). A exceção vale só para o endereço do servidor; todo outro endereço segue a verificação normal. A verificação nunca é desligada de forma geral. Testes (`conexao-fixada.test.ts`) cobrem chave certa, chave errada, reserva, outro endereço e lista vazia.
- `configuracao.ts`: leitura e validação das impressões, e `validarConfiguracaoDeProducao` (recusa endereço local, `http` e impressão faltando). Sem impressão, o app não conecta fora deste computador.
- `index.ts`: as chamadas à API passam a usar a rede do Chromium (`session.defaultSession.fetch`), que é a que consulta o verificador.
- `electron-builder.yml` (NSIS por usuário, dois cliques, sem administrador, atalhos, "Corona Gestão", ícone provisório de `docs/marca`) e `npm run owner:empacotar` (`scripts/empacotar-app.mjs`): lê `apps/owner-app/.env.producao` (ignorado pelo Git; `.env.producao.example` versionado), confere, compila, empacota e mostra o SHA-512.
- `electron-builder` 26.18.0 (a versão do ADR-0001) como dependência de desenvolvimento do app da dona.

**Defeito encontrado e corrigido:** o endereço da API era lido de `process.env`, que o build **não** preenche. O app empacotado ficaria sempre com `localhost:8080`. Agora vem de `import.meta.env`, embutido na compilação. (O Corona Vendas lê o endereço do mesmo jeito; fica avisado para a Fase 9 completa, não foi mexido.)

**Como foi verificado (de verdade)**

- No **Electron 45 real**, com um servidor HTTPS de certificado autoassinado: sem pin, **recusado** (`ERR_CERT_AUTHORITY_INVALID`); pin errado, **recusado**; pin certo (com a reserva ao lado), **conectou**; pin configurado para outro endereço, **recusado**. Cada cenário num processo novo.
- Instalador gerado (122 MB). O pacote tem endereço e impressão embutidos e o app abre e fica rodando.
- Instalação silenciosa e desinstalação no Windows deste computador: instala em `%LocalAppData%\Programs\corona-gestao`, cria o atalho "Corona Gestão" e o desinstalador remove tudo. Defeito achado: o instalador por usuário usava o nome do pacote npm como pasta (`@coronaowner-app`); corrigido (`extraMetadata.name`).
- `lint`, `typecheck`, `format:check`, testes do app (todos) e `npm audit --audit-level=high` verdes.

**O que não foi feito**

- Instalação **pela dona, com o assistente na tela** e o aviso "editor desconhecido" do SmartScreen: só se vê no computador dela (o roteiro explica o passo).
- Ícone definitivo: o logo provisório (`docs/marca`) está em uso.
- Conexão real do app instalado com a VPS: depende da VPS.
- Atualização automática: tarefa 9.5.

**Dúvidas:** troca do logo provisório pelo definitivo.

### Tarefa 9.5: atualização automática

**Em revisão.** Só atualização em `apps/owner-app`, mais `scripts/publicar-app.mjs`. Decisão no [ADR-0009](../decisoes/ADR-0009-implantacao-certificado-atualizacao-e-backup.md), item 3 (com a correção do resultado da prova).

**O que foi feito**

- `src/main/atualizacao.ts` (`ServicoDeAtualizacao`) sobre o `electron-updater` 6.8.11: confere 20 segundos depois de abrir e a cada 2 a 6 horas; baixa em segundo plano; só versão **maior** e final; sem atualização diferencial; instala quando a pessoa **fecha** o programa. Erros (rede, certificado, hash) não aparecem para a dona e a conferência continua agendada. Só no app instalado.
- O certificado fixado também na partição `electron-updater` (a que a biblioteca usa para baixar), com teste que confere o nome contra a biblioteca.
- Ponte e tela: faixa "Há uma versão nova. Ela será instalada quando você fechar o programa." (sem botão), que também aparece para quem abriu a janela depois do download. Glossário atualizado.
- `electron-builder.yml` gera o `latest.yml` (com SHA-512) e `npm run owner:publicar` (`scripts/publicar-app.mjs`): recusa versão igual ou menor que a do servidor, envia o instalador e só depois o `latest.yml`, confere o hash no servidor antes de anunciar, mantém os 3 instaladores mais novos. Peças puras testadas (`npm run scripts:test`, agora também no CI).
- `owner:empacotar` apaga instaladores antigos antes e `--para-teste` permite apontar para este computador (com aviso).

**Como foi verificado (de verdade)**

Pilha de produção local (nginx real, certificado autoassinado fixado). Versão 1.0.0 instalada no Windows, 1.0.1 publicada com `owner:publicar`; o app abriu, baixou o instalador, conferiu o hash e, ao ser fechado, se instalou sozinho: a versão instalada passou de 1.0.0 para 1.0.1. A 1.0.1 abre normalmente. Publicar a mesma versão de novo é recusado. A primeira tentativa **falhou** (`ERR_CERT_AUTHORITY_INVALID`): o `electron-updater` usa uma sessão própria; corrigido e provado de novo (ver ADR).

**O que não foi feito**

- **A publicação pela rede (`ssh` e `scp`) não foi testada**: só o modo de pasta local (`CORONA_PUBLICAR_LOCAL`); precisa da VPS e da chave SSH.
- O aviso com o programa realmente aberto na tela da dona (só a lógica e a faixa foram testadas); o computador dela pode ter antivírus que segure o instalador sem assinatura.
- Atualização do Corona Vendas (Fase 9 completa).

**Dúvidas:** nenhuma nova.

### Tarefa 9.7: Corona Vendas, instalador e atualização reaproveitáveis (extra)

**Em revisão.** Mesmo mecanismo do Corona Gestão para o Corona Vendas, sem mexer em venda, sincronização, banco local nem bloqueio de aparelho (Fase 5). Roteiro: [entrega-corona-vendas.md](../entrega-corona-vendas.md).

**O que foi feito**

- Pacote **neutro** `packages/conexao-segura`: certificado fixado, endereço, versão, validação de produção e o serviço de atualização, extraídos do Corona Gestão (que passa a usá-los) e usados pelo Corona Vendas. Sem nenhuma palavra de gestão (a varredura confere; entrou nos pacotes varridos do CI).
- Corona Vendas: instalador NSIS (`electron-builder.yml`, nome "Corona Vendas", ícone provisório), `extraMetadata.name`, endereço e impressões digitais como configuração de build, certificado fixado nas duas sessões do Chromium, atualização automática no canal `/atualizacoes/vendas/`, faixa "Há uma versão nova..." e ponte. **Corrigido** o endereço da API lido de `process.env` (o build não preenche): agora `import.meta.env`.
- Scripts dos dois aplicativos unificados (`scripts/empacotar-app.mjs`, `scripts/publicar-app.mjs`): `vendas:empacotar` e `vendas:publicar`.

**Verificado de verdade:** ciclo completo no computador (pilha de produção local): Corona Vendas 1.0.0 instalado, 1.0.1 publicada em `/atualizacoes/vendas/`, o app abriu, baixou e se instalou sozinho ao ser fechado. `npm run barreira:varrer` limpa (inclui o pacote novo), lint, typecheck, testes (Gestão e Vendas), `scripts:test`.

**O que não foi feito:** nada de venda, banco local criptografado nem bloqueio do segundo aparelho (Fase 5); ícone definitivo; publicação pela rede (`ssh`) sem VPS.

### Tarefa 9.6: roteiro de entrega

**Em revisão.** Só documentação: [docs/entrega-corona-gestao.md](../entrega-corona-gestao.md).

**O que tem:** o que só o responsável faz; preparar a VPS; configurar e subir; certificado e reserva; backup e **teste de restauração obrigatório**; aviso de queda; gerar o instalador e publicar a primeira versão; instalar no computador da dona (inclusive o aviso "editor desconhecido" do Windows); primeiro acesso, senha temporária e importação dos produtos pela planilha; lista de conferência antes do dado real; o que fazer se algo falhar (sem conexão, IP mudou, certificado, senha esquecida, atualização que não chega, disco cheio); como restaurar; como publicar uma atualização.

**O que não foi feito:** o roteiro não foi seguido por outra pessoa numa VPS de verdade (só os pedaços foram testados no computador de desenvolvimento).

**Dúvidas:** nenhuma nova.

