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

