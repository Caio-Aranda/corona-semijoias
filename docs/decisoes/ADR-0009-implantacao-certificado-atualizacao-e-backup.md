# ADR-0009: Implantação na VPS, certificado fixado, atualização automática e backup (Fase 9 mínima)

- **Status:** Proposto (aguardando o responsável)
- **Data:** 10/11/2026
- **Tarefas que implementam:** 9.2 a 9.6 da Fase 9 mínima
- **Relação com a especificação:** a §5 prevê domínio próprio e Let's Encrypt, e a §11 prevê assinatura de código e backup no S3 ou Backblaze. O responsável decidiu, para a entrega de 14/11/2026, **IP sem domínio, certificado autoassinado fixado no app, instalador sem assinatura e backup no Google Drive**. Isto é uma **entrega mínima**: o domínio, o Let's Encrypt e a assinatura continuam sendo o destino (item 9). A especificação não muda agora; a mudança entra com a Fase 9 completa.

## Contexto

O Corona Gestão (app da dona) precisa funcionar na sexta, 14/11/2026, contra uma API real. Só a dona usa o sistema nesta etapa (o Corona Vendas e a sincronização vêm depois). Dados reais entram a partir daí, então o servidor, o canal e o backup precisam ser seguros desde o primeiro dia.

## Decisão

### 1. Arquitetura de implantação

```
Corona Gestão (Windows) ── HTTPS :443 (certificado fixado) ──► proxy Nginx ──► API (contêiner, porta interna 8080)
                                                                   │                       │
                                                                   └─ /atualizacoes/ (arquivos)   PostgreSQL (contêiner, SEM porta publicada)
```

- **Uma VPS Ubuntu** (Hostinger, ~4 GB, 2 vCPU, 50 GB) com **Docker Compose de produção** (`deploy/`): `proxy` (Nginx), `api` (Spring Boot, perfil `prod`, usuário sem privilégio, sistema de arquivos só de leitura onde der) e `postgres` (mesma imagem e mesmos parâmetros do desenvolvimento: `timezone=UTC`, `max_prepared_transactions=0`; ADR-0002).
- **Rede:** só o `proxy` publica porta (443). A API e o banco ficam numa rede interna do Compose. **O banco nunca tem porta publicada** (§11); acesso de manutenção é por `docker compose exec`.
- **Firewall (`ufw`):** liberadas só a 22 (SSH) e a 443. A 80 fica fechada (não há Let's Encrypt). O Docker contorna o `ufw` quando publica porta; por isso só o `proxy` publica porta, e o script confere isso depois de subir.
- **SSH só por chave** (senha desligada, `root` sem senha), `fail2ban` no SSH, atualizações de segurança automáticas (`unattended-upgrades`), fuso UTC.
- **Arquivos enviados** (fotos e documentos) ficam num volume do servidor (`ARQUIVOS_PASTA`), nunca no banco, e **entram no backup**.
- **Segredos** só em `deploy/.env` no servidor (permissão 600, fora do Git); `deploy/.env.example` é versionado com valores falsos.
- **Primeiro acesso:** o mecanismo que já existe (`ADMIN_NOME`, `ADMIN_LOGIN`, `ADMIN_SENHA` no `.env` do servidor; troca obrigatória da senha na primeira entrada; ADR-0004). Depois de criado, a senha sai do `.env`.
- **Saúde:** `/actuator/health` (já exposto, sem detalhes), usado pelo `healthcheck` do Compose e por uma verificação externa gratuita (item 8).
- **Perfil `prod` do Spring:** Swagger e OpenAPI desligados, `server.error.include-*=never`, sem rastro técnico nas respostas, log sem valores. Segredos só por variável de ambiente.

### 2. Certificado autoassinado fixado (sem domínio)

- A conexão é por `https://<IP>`. O certificado é **autoassinado, com o IP no campo "nome alternativo" (SAN)**, gerado por script (`deploy/certificado/`) **no servidor**; a chave privada nunca sai de lá e nunca vai ao Git.
- **O que é fixado é a chave pública, não o arquivo do certificado** (pin do SPKI, SHA-256 em Base64). Assim **renovar o certificado mantendo a mesma chave não exige nova versão do app**.
- O app aceita **até duas impressões digitais**: a **atual** e uma **reserva** (de uma chave nova gerada de antemão e guardada fora do servidor). Trocar a chave sem quebrar o app: publicar uma versão do app que já traga a reserva, depois trocar a chave no servidor.
- Como é feito no Electron: o processo principal usa `net.fetch` (rede do Chromium) e instala um `setCertificateVerifyProc` na sessão **que só decide para o endereço do servidor configurado**: aceita se a impressão digital da chave do certificado estiver na lista fixada; para qualquer outro endereço devolve "usar a verificação normal do sistema". **A verificação de certificado nunca é desligada de forma geral**, nem se aceita "qualquer certificado". Sem impressão digital configurada, o app **não** abre conexão HTTPS fora deste computador.
- O endereço e as impressões digitais entram **como configuração de build** (`MAIN_VITE_CORONA_API_URL`, `MAIN_VITE_CORONA_API_PINS`, em `apps/owner-app/.env.producao`, ignorado pelo Git; `.env.producao.example` mostra o formato). Nenhum valor real fica no código versionado.
- **Se o IP mudar:** o certificado atual deixa de valer para o novo endereço e o app, que também busca as atualizações no endereço antigo, **não consegue se conectar nem se atualizar**. Saída: gerar certificado novo (a mesma chave serve) para o IP novo, gerar um instalador novo com o endereço novo e **reinstalar uma vez** no computador da dona. Prevenção: pedir à Hostinger IP fixo (o IP da VPS não muda enquanto ela existir) e nunca recriar a VPS sem avisar. Recomendação para depois: domínio próprio e Let's Encrypt (item 9), que acaba com este risco.
- Documentar em `deploy/certificado/README.md` (gerar, renovar, trocar a chave, o que acontece se o IP mudar).

### 3. Atualização automática: `electron-updater` com o canal fixado (opção b)

A pergunta era: o `electron-updater` padrão não aceita certificado autoassinado, então fazemos um verificador próprio (a) ou outra solução segura (b)?

**Escolha: (b), usar o `electron-updater` (6.8.11, já aprovado no ADR-0001) sobre o mesmo canal fixado.** Razões:

- No Windows, o `electron-updater` baixa pela rede do Chromium (`net` do Electron) da sessão padrão. O verificador de certificado do item 2 vale para ele também, **sem nenhuma exceção a mais**: o endereço de atualização é o mesmo servidor fixado, e só esse endereço tem a exceção.
- Ele já faz o difícil e perigoso: conferir o **SHA-512** do instalador publicado em `latest.yml`, baixar para uma pasta própria, esperar o app fechar, rodar o instalador silencioso (NSIS por usuário, sem pedir administrador), reabrir o app e limpar. Escrever isso à mão (opção a) repetiria código crítico de instalação sem ganho de segurança.
- A integridade vem de duas camadas: (1) o canal só é aceito com a chave fixada; (2) o hash SHA-512 do instalador vem no `latest.yml` e é conferido antes de instalar. Atualização com hash diferente é descartada.
- **Configurações de segurança:** só o canal `latest`, sem pré-lançamentos, sem rebaixar versão (`allowDowngrade = false`), **sem atualização diferencial** (menos peças), baixar sem instalar na hora (`autoInstallOnAppQuit = true`: instala quando a dona fecha o programa).
- **Condição de validade desta escolha:** a tarefa 9.5 prova por teste local (servidor HTTPS com certificado autoassinado) que o `electron-updater` só consegue baixar com a chave fixada e falha com outra. **Se a prova falhar, vale a opção (a)**, um verificador próprio sobre o mesmo canal fixado, e este ADR é corrigido.
- **Resultado da prova (10 e 11/11/2026), com uma correção a este texto:** o `electron-updater` **não** baixa pela sessão padrão do Chromium, como se supunha acima: ele usa uma sessão própria (partição `electron-updater`, sem cache). A primeira tentativa, com o verificador só na sessão padrão, falhou com `ERR_CERT_AUTHORITY_INVALID` (a verificação normal continuou valendo, como deve). A opção (b) vale **depois de instalar o mesmo verificador fixado nessa partição** (`PARTICAO_DO_ATUALIZADOR` em `atualizacao.ts`; um teste confere que o nome é o que a biblioteca usa, para uma troca de versão dela não passar despercebida). Com isso, o teste ponta a ponta passou: versão 1.0.0 instalada, 1.0.1 publicada em `/atualizacoes/` pelo nginx real com certificado autoassinado, o app abriu, baixou, conferiu o SHA-512 e se instalou sozinho ao ser fechado (versão instalada passou a 1.0.1). A recusa com chave errada foi provada no Electron real (tarefa 9.4, mesma função de decisão).
- **Limite aceito:** sem assinatura do instalador, quem controlar a VPS controla o que a dona instala. A defesa é a segurança da VPS (item 1). Reforço previsto: assinar o `latest.yml` com uma chave que fica fora do servidor, ou assinar o instalador (item 9).
- **Publicação** (`npm run owner:publicar`): gera o instalador, calcula o hash, e envia por `scp`/`rsync` para a pasta `atualizacoes/` da VPS, primeiro o `.exe` e **só depois** o `latest.yml` (o arquivo que anuncia a versão). Nginx serve essa pasta só de leitura, em `/atualizacoes/`, e nada mais.
- **Versão mínima** (ADR-0004, item 10): continua valendo na API; subir `APP_VERSAO_MINIMA_GESTAO` só depois que a dona já estiver na versão nova.
- **Texto para a dona:** "Há uma versão nova. Ela será instalada quando você fechar o programa."

### 4. Instalador

`electron-builder` com NSIS **por usuário** (não exige administrador, grava em `%LocalAppData%`), nome "Corona Gestão", atalho no menu e na área de trabalho, ícone próprio, **sem assinatura** (o Windows mostra "editor desconhecido" na primeira instalação; o roteiro de entrega explica o passo). O Corona Vendas não é alterado e a barreira `npm run barreira:varrer` segue valendo para ele.

### 5. Backup

- **Diário:** `pg_dump -Fc` do schema `corona` pelo usuário das migrações (ADR-0002, item 5 e `docs/restauracao-de-backup.md`), mais um arquivo compactado da pasta de arquivos enviados, guardados em `/var/backups/corona/` na VPS com **retenção de 14 dias**. Agendado por `systemd timer` (registra no `journal`; falha vira aviso, item 8).
- **Semanal, fora da VPS:** a cópia de domingo é enviada ao **Google Drive do responsável por `rclone`**, com criptografia do próprio `rclone` (`crypt`) quando o responsável quiser. A autorização do Drive é feita pelo responsável e fica só no servidor (`rclone.conf`, permissão 600); nada vai ao Git.
- **Segunda rede de segurança:** snapshot da Hostinger (agendado no painel pelo responsável).
- **Teste de restauração:** executado de verdade num banco limpo, com o tempo e o resultado registrados em `docs/restauracao-de-backup.md`, **antes de a dona lançar dado real**.
- **Limite aceito:** perda de até 24 horas de dados (do último backup diário). Uma cópia por Google Drive por semana significa que, se a VPS e o snapshot se perderem juntos, a perda pode chegar a 7 dias. Aceitável nesta etapa; o envio diário fica para a Fase 9 completa.

### 6. Aviso de queda da API

Verificação externa gratuita (por exemplo UptimeRobot, que o responsável configura) chamando `https://<IP>/actuator/health`. Atenção: serviços externos **não conseguem validar um certificado autoassinado**; por isso a verificação precisa aceitar certificado não confiável e fica restrita à rota `/actuator/health`, que não carrega dado nenhum. Como reforço, o servidor tem um `systemd timer` que testa a saúde localmente e avisa por e-mail (`msmtp`) ou `ntfy`, a escolha do responsável.

### 7. O que não muda

Regras de negócio, migrações publicadas e o código de cadastros e estoque. No backend só entram o perfil `prod` e o que a implantação exigir (a imagem Docker). O Corona Vendas e a barreira ficam como estão.

### 8. Riscos

| Risco | Efeito | Tratamento |
| --- | --- | --- |
| IP da VPS muda | app não conecta nem se atualiza | item 2: IP fixo, aviso no roteiro, reinstalação única |
| Chave privada do certificado vazada ou perdida | quem tem a chave se passa pelo servidor | chave só no servidor, permissão 600; trocar pela chave reserva (item 2) |
| Certificado vence | `net` do Chromium continua aceitando pelo pin, mas deve ser renovado | renovar a cada 12 meses com a mesma chave (aviso no roteiro) |
| VPS invadida | atualização maliciosa para a dona | item 1 (ufw, chave, fail2ban), SHA-512, assinatura do `latest.yml` e do instalador na Fase 9 completa |
| Instalador sem assinatura | aviso do Windows na primeira instalação | passo no roteiro; assinatura na Fase 9 completa |
| Disco da VPS cheio | API e backup param | retenção de 14 dias, aviso de disco no timer de saúde |
| Backup nunca restaurado | descobrir que não serve no pior dia | teste de restauração obrigatório antes do dado real |
| Perda do Google Drive ou do token | cópia externa para | verificação semanal do envio no roteiro (conferir a data do arquivo) |
| Atualização ruim publicada | a dona fica com app quebrado | publicar primeiro em teste, manter o instalador anterior na pasta, `allowDowngrade = false` (corrigir sempre com versão maior) |
| Docker contornando o firewall | porta do banco exposta | só o proxy publica porta; verificação no script de instalação |
| Uma instância só da API | queda = indisponível | aceito nesta etapa (ADR-0004, item 11 já pede ADR para mais de uma) |

### 9. Destino (fora da Fase 9 mínima)

Domínio próprio com Let's Encrypt, assinatura de código do instalador, backup diário fora da VPS (S3 ou Backblaze), Corona Vendas no mesmo canal de atualização, e monitoramento melhor. Cada um vira tarefa da Fase 9 completa.

## Dúvidas para o responsável

1. Confirmar este ADR (Proposto para Aceito).
2. IP fixo da VPS, chave SSH e a conta do Google Drive para o `rclone`: só o responsável cria e entrega.
3. Aviso de queda: e-mail ou `ntfy`?
