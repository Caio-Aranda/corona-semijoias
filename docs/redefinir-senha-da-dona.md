# Redefinir a senha da dona (quando não há outro administrador)

Para quem opera o servidor. A dona esqueceu a senha **e não existe outro administrador** que possa
redefinir pela tela. O sistema não usa e-mail (§11), então a recuperação é um comando que roda no
servidor (ADR-0004, item 8). Quem tem acesso à VPS faz o passo a passo abaixo.

> **Se existe outro administrador**, não use este comando: ele entra no Corona Gestão e redefine a senha
> pela tela (rota `POST /api/dona/usuarios/{id}/redefinir-senha`). A senha temporária aparece uma vez.

## O que o comando faz

1. Procura o administrador pelo login. Só aceita usuário **ativo** com perfil de administrador; qualquer
   outro caso termina com uma mensagem em português e **não altera nada**. Ele não reativa ninguém.
2. Gera uma senha temporária aleatória, que obedece às regras da senha (12 letras e números, sem os
   caracteres que se confundem: `0 O o 1 l I`).
3. Grava só o hash da senha, marca a **troca de senha obrigatória**, dá **7 dias** de prazo (configuração
   `corona.seguranca.senha-temporaria.validade`) e grava a hora da troca de credencial.
4. **Encerra todos os acessos abertos desse usuário:** revoga todas as sessões dele (tabela `sessoes`,
   motivo `SENHA_REDEFINIDA_PELO_SERVIDOR`). Nenhum token emitido antes vale depois, nem o do mesmo
   segundo, nem o de renovação, nem o restrito da troca de senha.
5. **Retira o bloqueio por tentativas desse login**, em qualquer endereço (grava uma linha
   `BLOQUEIO_LIMPO` em `tentativas_login`; a tabela é só de inserção). O bloqueio **só por endereço**
   (muitos erros com logins variados vindos do mesmo IP) não é retirado: ele acaba sozinho em 15 minutos.
6. Mostra a senha **uma única vez, no terminal**. Ela não vai para o log nem para nenhum arquivo.
7. Deixa uma linha no log da API, sem a senha:
   `Senha temporária do administrador <login> redefinida pelo servidor; vence em <data>`.

Tudo isso acontece numa transação só: ou tudo vale, ou nada muda. A redefinição pela tela (outro
administrador) faz o mesmo, com o motivo `SENHA_REDEFINIDA`.

Ainda não faz parte do comando: gravar a ação na auditoria como feita "pelo servidor" (Fase 1).

## Passo a passo em produção

O comando usa o mesmo programa da API (o jar), em modo **sem servidor web**: não abre a porta da API,
então pode rodar com a API no ar. O contêiner da API nasce na Fase 9; o formato abaixo já vale para ele.

1. Entre na VPS por SSH e vá para a pasta do projeto (onde está o `docker-compose.yml`).
2. Rode, trocando `maria.silva` pelo login da dona:

   ```bash
   docker compose exec api java -jar /app/corona-api.jar redefinir-senha-admin --login=maria.silva
   ```

   Se esquecer o `--login=...`, o comando pergunta o login no terminal (precisa de terminal interativo;
   sem ele, o comando pede para informar o login e termina).
3. O terminal mostra algo assim:

   ```text
   Senha temporária do administrador "maria.silva" gerada.

       Senha temporária: Kd7mTq3XvHw9
       Vale até: 16/11/2026 às 14:30 (horário de Brasília)

   Anote esta senha agora: ela não será mostrada de novo.
   Os acessos abertos desse usuário foram encerrados. Na próxima entrada, ele usa esta
   senha e é obrigado a criar uma senha nova.
   O bloqueio por tentativas erradas desse login foi retirado.
   ```

4. Passe a senha à dona por um meio seguro (pessoalmente ou por telefone, nunca por mensagem
   guardada). Ela entra no Corona Gestão com essa senha e **é obrigada a criar uma senha nova** antes
   de usar qualquer outra coisa.
5. Se a senha vencer (7 dias) sem a dona ter entrado, rode o comando de novo: cada execução gera uma
   senha nova e uma contagem nova de 7 dias.

> **Atenção: o comando também aplica as migrações pendentes do banco**, exatamente como a subida da
> API, porque sobe a mesma aplicação (só não abre o servidor web). Por isso, em produção, use sempre o
> **mesmo jar da API** (a mesma versão que está rodando): um jar mais antigo ou mais novo mexeria no
> banco de forma que a API em execução não espera. Ele também confere a configuração da API (chaves do
> token, `SESSAO_SEGREDO_RENOVACAO` e, se o banco ainda não tiver nenhum administrador, as variáveis `ADMIN_*`).

Códigos de saída: `0` deu certo; `1` recusado ou falhou (a mensagem diz o motivo); `2` faltou o login.

O comando precisa das mesmas variáveis de ambiente da API (banco e chaves do token), que o contêiner
já tem.

## Em desenvolvimento

Com o banco do Compose no ar (`npm run db:up`) e o `.env` preenchido:

```bash
npm run api:redefinir-senha -- maria.silva
```

## Primeiro administrador

A conta criada pelo `.env` (`ADMIN_NOME`, `ADMIN_LOGIN`, `ADMIN_SENHA`) também nasce com a troca de
senha obrigatória, **mas sem vencimento**: a senha veio do `.env` e não foi gerada pelo sistema. Na
primeira entrada a dona só consegue criar a senha nova. Depois disso a senha do `.env` pode (e deve)
sair do arquivo.
