# Entrega do Corona Gestão (Fase 9 mínima)

Roteiro para o **responsável** colocar o Corona Gestão no ar e entregar à dona. Decisões e riscos:
[ADR-0009](decisoes/ADR-0009-implantacao-certificado-atualizacao-e-backup.md). Scripts e detalhes do
servidor: [deploy/README.md](../deploy/README.md) e [deploy/certificado/README.md](../deploy/certificado/README.md).

Faça na ordem. **Só marque a entrega como concluída com a lista da seção 10 toda marcada.**

## 0. O que só o responsável faz

| O quê | Quando |
| --- | --- |
| Contratar a VPS na Hostinger (Ubuntu 24.04, ~4 GB, 2 processadores, 50 GB) e criar a conta | antes de tudo |
| Cadastrar sua **chave SSH pública** na VPS e confirmar que entra | antes da seção 1 |
| Anotar o **IP da VPS** (ele precisa ser fixo; ver seção 11) | antes da seção 2 |
| Ligar o **backup automático (snapshot)** da Hostinger no painel | seção 5 |
| Autorizar o **Google Drive** no `rclone` | seção 5 |
| Escolher o aviso de queda (`ntfy` no celular ou e-mail) e, se quiser, a verificação externa | seção 6 |

Nada de senha, chave ou IP vai para o repositório.

## 1. Preparar a VPS (uma vez)

Entre na VPS (`ssh root@IP`) e **teste que a chave funciona** antes de continuar: o script desliga a senha
do SSH e só segue se achar uma chave autorizada.

```bash
apt-get update && apt-get install -y git
git clone <endereço do repositório> /opt/corona
bash /opt/corona/deploy/servidor/instalar-servidor.sh
```

O script ajusta o fuso para UTC, liga as atualizações de segurança, deixa o firewall só com as portas 22 e
443, desliga a senha do SSH, liga o `fail2ban`, instala o Docker e o `rclone` e cria as pastas
(`/etc/corona`, `/srv/corona`, `/var/backups/corona`). Se o repositório for privado, use uma chave de leitura
(deploy key) do GitHub na VPS.

## 2. Configurar e subir a API

```bash
cd /opt/corona/deploy
cp .env.example .env && chmod 600 .env
nano .env
```

Preencha tudo. Valores aleatórios: `openssl rand -base64 32 | tr -d '=+/'` (sem `$`, aspas nem barra
invertida). As senhas do banco e as três chaves (`JWT_CHAVE_ATUAL`, `SESSAO_SEGREDO_RENOVACAO`) **diferentes
entre si**. `ADMIN_NOME`, `ADMIN_LOGIN` e `ADMIN_SENHA` são a conta da dona (a senha é **temporária**: ela cria
a definitiva na primeira entrada). Guarde o `.env` no seu cofre de senhas: se a VPS for perdida, as chaves
ficam no cofre, não no servidor.

## 3. Certificado HTTPS

```bash
sudo /opt/corona/deploy/certificado/gerar-certificado.sh novo <IP-DA-VPS>
sudo /opt/corona/deploy/certificado/gerar-certificado.sh reserva /root/reserva
```

Anote as **duas** linhas `sha256/...` que aparecerem (a do certificado e a da reserva). Copie
`/root/reserva/reserva.key` para o seu cofre **e apague do servidor** (`shred -u /root/reserva/reserva.key`).
Sem a reserva guardada, trocar a chave um dia obriga a reinstalar o programa. Mais detalhes (renovar todo ano,
trocar a chave, IP novo): [deploy/certificado/README.md](../deploy/certificado/README.md).

## 4. Subir e conferir

```bash
cd /opt/corona/deploy
docker compose up -d --build --wait
sudo servidor/verificar-portas.sh
```

A primeira subida compila a API (alguns minutos) e cria as tabelas sozinha. Confira, **do seu computador**:

```bash
curl -k https://<IP>/actuator/health
```

Deve responder `{"groups":["liveness","readiness"],"status":"UP"}`. `verificar-portas.sh` precisa terminar em
**"OK: só o proxy publica porta"**. Se mostrar erro, **pare**: o banco ou a API estão abertos para a internet.

## 5. Backup (obrigatório antes de dado real)

1. **Diário e semanal**: `sudo /opt/corona/deploy/backup/instalar-timers.sh`.
2. **Google Drive**: `sudo rclone config --config /etc/corona/rclone.conf` (novo remoto do tipo `drive`;
   se quiser a cópia criptografada, crie também um remoto `crypt` por cima e **guarde a senha dele no cofre**).
   Crie `/etc/corona/backup.env` (permissão 600):
   ```
   RCLONE_CONFIG=/etc/corona/rclone.conf
   RCLONE_DESTINO=nome-do-remoto:corona-backups
   ```
   e rode de novo `instalar-timers.sh` para ligar o envio semanal.
3. **Snapshot da Hostinger**: agende no painel (diário ou semanal).
4. **Teste de restauração, de verdade, na VPS**:
   ```bash
   sudo /opt/corona/deploy/backup/fazer-backup.sh
   sudo /opt/corona/deploy/backup/testar-restauracao.sh $(ls -1t /var/backups/corona/diario/corona-*.dump | head -1)
   sudo /opt/corona/deploy/backup/enviar-semanal.sh
   ```
   O teste sobe um banco **novo e descartável** (não toca no de produção) e confere tabelas, administradora e
   migrações. **Registre o resultado e o tempo** na tabela de `docs/restauracao-de-backup.md`. Confira no Drive
   que o arquivo chegou.

Faça este teste **de novo depois de importar os produtos** (seção 9), com catálogo de verdade.

## 6. Aviso de queda

1. Crie `/etc/corona/aviso.env` (permissão 600): `NTFY_URL=https://ntfy.sh/um-nome-longo-e-dificil-de-adivinhar`
   (instale o app ntfy no celular e assine o mesmo nome) **ou** `AVISO_EMAIL=...` (exige `msmtp`).
2. `sudo /opt/corona/deploy/monitoramento/avisar.sh teste "ola"` deve chegar no celular.
3. `sudo /opt/corona/deploy/monitoramento/instalar-timers.sh` (verifica a cada 5 minutos e avisa também se o
   disco passar de 85%).
4. Recomendado: uma verificação externa gratuita (por exemplo UptimeRobot) em `https://<IP>/actuator/health`
   **aceitando certificado não confiável** (o serviço externo não conhece o nosso certificado; a rota não mostra dado nenhum).

## 7. Gerar o instalador (no seu computador de desenvolvimento)

```bash
cp apps/owner-app/.env.producao.example apps/owner-app/.env.producao
```

Preencha `MAIN_VITE_CORONA_API_URL=https://<IP>` e `MAIN_VITE_CORONA_API_PINS=sha256/ATUAL,sha256/RESERVA`
(as duas linhas da seção 3). Depois:

```bash
npm run owner:empacotar
```

O comando recusa endereço local ou impressão faltando **antes** de compilar. Sai
`apps/owner-app/release/Corona-Gestao-Setup-<versão>.exe` e o `latest.yml`. O instalador tem o endereço e as
impressões **embutidos**; nada fica no código.

**Publicar a primeira versão** (para o app já nascer com o canal de atualização funcionando):

```bash
cp apps/owner-app/.env.publicar.example apps/owner-app/.env.publicar   # preencha CORONA_VPS=root@IP
npm run owner:publicar
```

Confirme: `curl -k https://<IP>/atualizacoes/latest.yml` mostra a versão publicada.

## 8. Instalar no computador da dona

1. Leve o `Corona-Gestao-Setup-<versão>.exe` (pen drive, ou baixe de `https://<IP>/atualizacoes/` com o
   navegador aceitando o aviso de certificado; **só o responsável**, nunca peça isso à dona).
2. Dê dois cliques. O Windows pode mostrar **"O Windows protegeu o computador"** (instalador sem assinatura).
   É esperado **só na primeira instalação**: clique em **Mais informações** e depois em **Executar assim mesmo**.
3. O programa instala sozinho (sem pedir administrador), cria o atalho **Corona Gestão** na área de trabalho e
   no menu Iniciar e abre.
4. Se o antivírus bloquear, libere o instalador e a pasta `%LocalAppData%\Programs\corona-gestao`.
5. Tela de entrada: se aparecer **"Sem conexão com o servidor"**, confira a internet do computador e veja a
   seção 11.

## 9. Primeiro acesso e produtos

1. Entre com o usuário e a **senha temporária** da dona (`ADMIN_LOGIN` e `ADMIN_SENHA`). O programa pede para
   **criar a senha nova** (pelo menos 8 caracteres, com letras e números, diferente da temporária). A dona
   anota a senha dela. Ninguém mais precisa saber.
2. **Apague `ADMIN_SENHA` do `.env` do servidor** (`nano /opt/corona/deploy/.env`) e rode
   `docker compose up -d`.
3. Cadastros básicos, nesta ordem: **Cadastros > Categorias** e **Cadastros > Fornecedores**.
4. Produtos: **Cadastros > Produtos > Importar planilha**. A planilha tem as colunas **código, nome,
   categoria, custo e preço**. Escolha o arquivo: o programa mostra a **prévia, linha a linha**, e nada é gravado
   até clicar em **Importar produtos**. Corrija as linhas **Não importar** e importe de novo. No fim aparece
   **Importação concluída** e o resultado fica no relatório **Resultado das importações**.
5. Fotos: **Importar fotos** (opcional agora; a falta de foto nunca trava nada).
6. Entrada das peças que já estão na loja: **Estoque > Receber compra** (**Ler as peças** ou **Importar
   planilha**).

## 10. Antes de a dona lançar dado real (lista de conferência)

- [ ] `curl -k https://<IP>/actuator/health` responde `UP` e o programa entra pelo atalho.
- [ ] `verificar-portas.sh` terminou em OK (só a 443 aberta).
- [ ] Senha temporária trocada pela dona; `ADMIN_SENHA` apagada do `.env`.
- [ ] Reserva da chave do certificado guardada fora do servidor (e apagada de lá).
- [ ] `.env` do servidor e `reserva.key` no cofre do responsável.
- [ ] **Backup diário rodou** (arquivo em `/var/backups/corona/diario/`) e **o teste de restauração foi feito na
      VPS e registrado** em `docs/restauracao-de-backup.md`, **depois de importar os produtos**.
- [ ] Cópia semanal chegou ao Google Drive (arquivo com a data certa).
- [ ] Snapshot da Hostinger agendado.
- [ ] Aviso de queda chegando ao celular (teste feito).
- [ ] Publicar uma versão **de teste maior** (por exemplo 1.0.1) e ver o programa se atualizar sozinho no computador
      da dona: abrir o programa, esperar a faixa **"Há uma versão nova. Ela será instalada quando você fechar o
      programa."**, fechar e abrir de novo (o programa abre já na versão nova).

## 11. Se algo falhar

| O que aparece | O que fazer |
| --- | --- |
| **"Sem conexão com o servidor. Verifique a internet e tente de novo."** só neste computador | Internet do computador da dona. Se o resto do computador navega bem, veja as linhas abaixo. |
| O mesmo aviso em **todos** os computadores | No servidor: `cd /opt/corona/deploy && docker compose ps` (os três devem estar `healthy`). Se algum caiu: `docker compose logs --tail 100 api` e `docker compose up -d`. O aviso de queda também deve ter chegado. |
| O aviso aparece **depois de o IP da VPS mudar** | O certificado só vale para o IP antigo e o programa só fala com o endereço do instalador (as atualizações também). Gere o certificado para o IP novo (`gerar-certificado.sh renovar <IP-NOVO>`, mesma chave), `docker compose restart proxy`, gere um **instalador novo** com o IP novo e **reinstale uma vez** no computador da dona. Para evitar: nunca recriar nem trocar a VPS sem avisar. |
| O aviso aparece **depois de renovar ou trocar o certificado** | Renovar com `renovar` (mesma chave) não pede nada ao programa. Se a **chave** foi trocada, o programa só aceita se a chave nova for a **reserva** que veio no instalador; senão é preciso instalador novo. |
| A dona **esqueceu a senha** | Se houver outro administrador, ele redefine em **Usuários**. Senão, no servidor: `docker compose exec api java -jar /app/corona-api.jar redefinir-senha-admin --login=LOGIN` (mostra uma senha temporária uma vez; [passo a passo](redefinir-senha-da-dona.md)). |
| O programa **não se atualiza** | Confira `curl -k https://<IP>/atualizacoes/latest.yml` (versão **maior** que a instalada). O programa só procura ao abrir e a cada 2 a 6 horas, e instala ao **fechar**. Se nada resolver, reinstale com o instalador novo (os dados ficam no servidor). |
| O disco da VPS está quase cheio | O aviso chega ao celular. `du -sh /var/backups/corona /srv/corona/*`; apague instaladores velhos de `/srv/corona/atualizacoes` e confira a retenção do backup (14 dias). |
| `docker compose up` diz que falta uma variável | Falta preencher essa linha no `.env`; a mensagem diz qual. |
| **Nunca** | `docker compose down -v` em produção: apaga o banco. |

## 12. Como restaurar o backup

Passo a passo completo, com o teste: [restauracao-de-backup.md](restauracao-de-backup.md), seção "Backup em
produção". Resumo: servidor novo (seções 1 a 3, **com a mesma chave do certificado**), banco no ar, baixar o
backup do Drive (`rclone copy`), `pg_restore` na ordem do roteiro, `SELECT corona.preparar_sync_apos_restauracao();`,
fotos de volta em `/srv/corona/arquivos`, subir a API.

## 13. Como publicar uma atualização

1. Em `apps/owner-app/package.json`, aumente `version` (três números; **sempre maior**: o programa nunca volta
   para uma versão antiga).
2. Confira que o código está na `main` com o CI verde.
3. `npm run owner:publicar`. Ele gera o instalador, recusa versão igual ou menor, envia o instalador, confere o
   hash no servidor e só então anuncia a versão (`latest.yml`).
4. O programa da dona acha a versão ao abrir (ou em até 6 horas), baixa em segundo plano e mostra a faixa
   **"Há uma versão nova. Ela será instalada quando você fechar o programa."**. Ela não faz nada: ao fechar, a
   versão entra.
5. Se a versão nova tiver um defeito: **publique outra, maior, com a correção** (a antiga não volta). Os 3
   instaladores mais recentes ficam na VPS.
6. Mudança que exija versão mínima na API (`APP_VERSAO_MINIMA_GESTAO`): só aumente o número **depois** de a dona
   já estar na versão nova.

## O que esta entrega ainda não tem (Fase 9 completa)

Domínio próprio com Let's Encrypt (acaba com o risco do IP), assinatura do instalador (acaba com o aviso do
Windows), backup diário fora da VPS, Corona Vendas no mesmo canal de atualização e monitoramento melhor.
