# ADR-0004: Autenticação e sessão

- **Status:** Aceito
- **Data:** 09/11/2026
- **Detalhado em 09/11/2026 (tarefa 11):** a sessão usa duas tabelas só do servidor, `sessoes` (a família; o `id` é o `sid`) e `tokens_de_renovacao` (um registro por token da família, com o anterior e o sucessor); o login com senha temporária também abre uma família, do tipo `TROCA_SENHA` e sem token de renovação, e o token restrito leva o `sid` dela (assim a redefinição o derruba como qualquer outro); `tentativas_login` ganha três resultados além dos do item 7: `SENHA_TEMPORARIA_VENCIDA` (senha certa, prazo vencido: não conta como erro), `PEDIDO_INVALIDO` (vendedor sem identificador de aparelho ou dona com ele: não conta como erro) e `BLOQUEIO_LIMPO` (registro gravado pela redefinição da senha, que retira o bloqueio daquele login)
- **Tarefas que implementam:** 9 (usuários, perfis, JWT, BCrypt, rotas por perfil, primeiro admin), 10 (criação de usuários, senha temporária, regras da senha, redefinição, comando de servidor para a dona), 11 (sessão longa, tentativas, registro de acessos, aparelho, prazo offline guardado), 12 (cabeçalhos e versão mínima), 15 e 16 (guarda dos tokens no Electron). Fase 5: bloqueio de segundo aparelho e regra do prazo offline. Tarefa 18: banco local criptografado

## Contexto

A autenticação precisa funcionar nos dois apps, com uma exigência especial: o Corona Vendas trabalha sem internet por dias (§6, §10), então a sessão não pode depender de consulta ao servidor a cada tela. Ao mesmo tempo, o notebook do vendedor pode ser perdido (§11), a dona não é da área de tecnologia (§19), e o servidor nunca confia no que o app diz (CLAUDE.md). Este ADR fixa as regras da §3, §6.15 e §11 (v1.4) e as mensagens da parte 6 do glossário. Nada aqui altera a especificação.

## Decisão

### 1. Token de acesso

- **Duração: 15 minutos.** Curto o bastante para limitar o estrago de um token roubado; longo o bastante para o app não renovar o tempo todo.
- **Assinatura: HS256**, com segredo de 256 bits ou mais, guardado no `.env` (nunca no repositório, §11). Justificativa: só **uma** aplicação (a API) cria e confere os tokens, então não há quem precise verificar sem poder assinar; a chave pública do RS256 não traz ganho aqui e exige gerar, guardar e distribuir um par de chaves. O cabeçalho do token leva um `kid` (identificador da chave), e a API aceita **duas chaves ao mesmo tempo** (a atual e a anterior), para trocar o segredo sem derrubar ninguém. Se um dia outro serviço precisar conferir tokens, vale um ADR novo para RS256.
- **Conteúdo, sem dado sensível:** `sub` (id do usuário), `perfil`, `vendedor_id` (só quando for vendedor), `dispositivo_id` (só no Corona Vendas), `sid` (identificador da família da sessão), `jti`, `iat`, `exp`, `iss`, e `escopo` quando for o token restrito da troca de senha (item 6). **Nunca** vão no token: senha, nome, CPF, e-mail, telefone, preço de custo, nem dados de outros vendedores.
- **Conferência a cada requisição:** além da assinatura e da validade, a API consulta por chave primária se o usuário continua **ativo** e se a família da sessão **não foi revogada**. É uma consulta barata (dezenas de usuários) e faz com que o usuário desativado perca o acesso no **próximo uso**, não só em 15 minutos.

### 2. Sessão longa (refresh token)

- **Validade: 30 dias**, contados do último uso (cada rotação renova), em configuração da aplicação.
- **Formato:** valor aleatório de 256 bits, mostrado ao app uma única vez. **No servidor guarda-se só o SHA-256** do valor (tabela só do servidor `sessoes`, ADR-0002), porque o valor já é aleatório e longo; BCrypt não é necessário aqui.
- **Ligada ao aparelho:** a sessão guarda o `dispositivo_id` do item 4. Um refresh token usado com outro identificador de aparelho é recusado e revoga a família.
- **Rotação a cada uso:** usar o token entrega um token novo e invalida o anterior. Os tokens de uma mesma sessão formam uma **família** (`familia_id`), que nasce no login.
- **Janela de tolerância de 2 minutos:** se a resposta de uma renovação se perde (queda de conexão), o app volta com o token anterior. **Dentro da janela, o token anterior ainda é aceito uma única vez e devolve o mesmo sucessor**, sem criar outro. Para devolver o mesmo valor sem guardá-lo, o sucessor é **derivado** (HMAC-SHA256 com um segredo próprio do `.env` sobre o identificador do registro filho), e a conferência continua sendo pelo SHA-256 guardado. Alternativa descartada: guardar o sucessor cifrado até o fim da janela, que exige limpeza periódica. A janela é configurável.
- **Reuso fora da janela, ou segunda reapresentação do token anterior, revoga a família inteira** (todos os tokens daquela sessão, no servidor), e o app precisa entrar de novo (com internet). Também revoga se o token anterior for reapresentado depois que o sucessor já foi usado.
- **Saída (logout)** revoga a família. **Redefinir a senha, desativar o usuário ou desativar o aparelho** revoga todas as famílias do usuário.
- **Usuário desativado** perde a sessão no próximo uso (item 1), com a mensagem "Seu acesso está inativo." do glossário (até 24/11/2026, "Seu acesso foi desativado.").
- A sessão guarda quando foi criada, usada e revogada e o motivo. **Nada é apagado** (ADR-0002).

### 3. Onde os tokens ficam nos aplicativos

- **Só no processo principal do Electron**, protegidos pelo `safeStorage` do Electron (que usa o cofre do Windows). O token de acesso fica na memória do processo principal; o de renovação, cifrado em disco pelo `safeStorage`.
- **Nunca** em `localStorage`, `sessionStorage`, cookies ou IndexedDB, **nem acessíveis ao React**. A tela chama o processo principal por uma ponte pequena e fixa (login, sair, "estou logado?", chamadas à API), e o processo principal injeta o token nas chamadas. O React nunca recebe o valor do token.
- A janela fica com `contextIsolation` ligado, `nodeIntegration` desligado e sem `remote`.
- Tarefas 15 e 16.

### 4. Identificador do aparelho

- **Como é gerado:** um UUID v4 aleatório, criado **na primeira abertura do Corona Vendas** (não é derivado de hardware, nome do computador ou usuário do Windows, para não ser rastreável e não mudar com troca de peça).
- **Onde fica:** no processo principal do Electron, num arquivo na pasta de dados do aplicativo. Na Fase 5 passa a ficar também no banco local criptografado (SQLCipher, ADR-0005 da tarefa 18). Reinstalar o aplicativo ou o Windows cria **outro** identificador; na Fase 5 a dona libera o aparelho novo.
- **Quando é enviado:** no corpo do login e da renovação da sessão, e fica ligado à sessão e ao token de acesso (`dispositivo_id`). Não é enviado em todo cabeçalho. O Corona Gestão não envia identificador de aparelho.
- **Fase 0:** só é **registrado** (na sessão e nos registros de acesso). Não existe a tabela `dispositivos` nem bloqueio. O bloqueio do segundo aparelho e a liberação ficam para a **Fase 5** (§6.15).

### 5. Prazo máximo offline (10 dias)

- **Fase 0 prepara:** o valor `tempo_maximo_offline_dias` (padrão **10**) fica em `configuracoes` (§9). O login e a renovação devolvem ao app a **hora do servidor (UTC)** e esse prazo. O processo principal guarda "último contato com o servidor" a cada resposta válida, sem usar a hora do relógio do computador como verdade.
- **Fase 5 faz:** a regra de bloqueio. Esboço: o prazo conta do último contato com o servidor; relógio do computador voltado para trás conta como prazo esgotado; ao esgotar, o Corona Vendas **bloqueia vendas novas**, mostra o texto do glossário ("Este computador ficou muitos dias sem internet...") e envia tudo ao conectar. O desenho completo da sincronização não faz parte deste ADR.
- **As vendas guardadas nunca se perdem (§11).** Elas ficam no banco local criptografado, que não depende do token. Se a sessão de 30 dias também tiver acabado ao reconectar, o vendedor entra de novo (com internet) e o envio segue normalmente.

### 6. Senha

- **BCrypt, custo 12** (cerca de um quarto de segundo por conferência num servidor pequeno; sobe com o tempo por ADR novo). O custo fica em configuração da aplicação, e a conferência aceita hash de custo menor e regrava o hash no próximo login, para poder subir o custo depois. BCrypt só lê os primeiros **72 bytes** da senha: a senha com mais de 72 bytes (em UTF-8) é recusada, e a mensagem de tela correspondente é criada no glossário pela tarefa 10, antes de usá-la.
- **Regras da senha nova (§11):** pelo menos **8 caracteres**, com **letras e números**, **diferente da senha temporária**. Valem no servidor (a tela só ajuda). Os textos são os da parte 6 do glossário.
- **Senha temporária:** a dona cria o usuário com senha temporária gerada pelo sistema (aleatória, sem caracteres que se confundem, mostrada uma vez) e pode gerar outra quando precisar. O usuário fica com a marca "troca obrigatória".
- **A senha temporária vale 7 dias** a partir da criação do usuário ou da redefinição (cada redefinição começa uma contagem nova de 7 dias; o prazo fica na configuração do servidor). O servidor guarda a data de vencimento junto da marca "troca obrigatória". **Vencida, não entra mais**: o login com a senha temporária certa, depois do prazo, é recusado com o código `SENHA_TEMPORARIA_VENCIDA`, não devolve nenhum token e **não conta** como erro de senha para o bloqueio (a senha estava certa); a dona gera outra senha temporária. Essa recusa só é mostrada depois de a senha ser conferida, para não revelar que o login existe. A tarefa 10 implementa e **acrescenta ao glossário a mensagem "senha temporária vencida"** antes de usá-la. **A senha definitiva** (do vendedor e da dona) **não expira.**
- **Troca obrigatória no primeiro acesso:** o login com senha temporária correta devolve **só um token restrito** (`escopo=TROCA_SENHA`, 15 minutos, **sem token de renovação**). Esse token abre apenas a rota de troca de senha em `/api/auth/` e a de saída. **Qualquer outra rota responde que a troca é obrigatória** (HTTP 403, código `TROCA_DE_SENHA_OBRIGATORIA`), então "nenhuma outra rota responde" é garantido no servidor, não na tela. Só depois da troca a API entrega o token de acesso e o de renovação normais.
- A senha nunca aparece em log, em auditoria, em resposta ou em mensagem de erro.

### 7. Limite de tentativas e registro de acessos

- **Bloqueio de 15 minutos depois de 5 erros seguidos** (§9, §11). Os dois números ficam em `configuracoes` (`tentativas_login_antes_do_bloqueio`, padrão 5; `tempo_bloqueio_login_minutos`, padrão 15).
- **A chave dos 5 erros é a combinação login + IP.** "Seguidos" quer dizer: sem um sucesso da mesma combinação no meio. Passou de 5, a combinação fica bloqueada por 15 minutos contados do último erro. Assim, um vendedor que erra a senha **não bloqueia o login dele para os outros** nem a loja inteira.
- **Limite separado, maior, só por IP**, contra força bruta que varia o login: **50 erros em 15 minutos** (janela móvel), configurável (`limite_erros_por_ip`, padrão 50, na configuração da aplicação). Passou, o IP fica bloqueado por 15 minutos.
- O login digitado é normalizado (sem espaços nas pontas, minúsculas) antes de contar. **Login que não existe é contado do mesmo jeito**, e a resposta é a mesma de senha errada, para não revelar quais logins existem.
- **Durante o bloqueio, nem a senha certa entra.** Resposta: HTTP 429 com `Retry-After` e o texto "Acesso bloqueado por alguns minutos" do glossário, com `{minutos}` preenchido pelo sistema.
- **IP real:** a API fica atrás do Nginx (§5, §11) e só confia no cabeçalho de IP encaminhado quando a conexão vem do proxy configurado. Sem isso, qualquer um poderia mudar o próprio IP.
- **Registro de cada tentativa** na tabela só de inserção `tentativas_login`: login normalizado, IP, identificador do aparelho (quando houver), tipo de app, resultado (`SUCESSO`, `SENHA_INCORRETA`, `BLOQUEADO`, `USUARIO_DESATIVADO`, `APP_INCOMPATIVEL`), hora. É o "pronto" da Fase 0. Nada é apagado (ADR-0002).
- **Muitos erros num mesmo login, vindos de IPs diferentes: só alerta, nunca bloqueio.** Bloquear por login (qualquer IP) deixaria qualquer pessoa trancar de fora a conta de outra. Em vez disso, **mais de 20 erros no mesmo login em 1 hora** (somando todos os IPs) geram um **aviso em "O que precisa da sua atenção"** da dona (§19.3), registrado a partir de `tentativas_login`. O limite (20 por hora) fica na configuração do servidor. A tarefa 11 gera o alerta; o texto do aviso é acrescentado ao glossário pela tarefa que o mostra, antes de usá-lo, e ele aparece assim que o bloco "O que precisa da sua atenção" existir.
- **Risco aceito:** um atacante que varie login **e** IP a cada tentativa não é limitado pelo IP nem pela combinação; o limite por IP, o BCrypt e o alerta tornam isso lento e visível, não impossível.

### 8. Redefinição da senha da própria dona

- **Há outro admin:** ele redefine pela tela (gera senha temporária; a dona esquecida troca no primeiro acesso, como qualquer usuário).
- **Não há outro admin (sem e-mail):** procedimento **no servidor**, para quem tem acesso à VPS. Um comando de linha administrativo do próprio sistema (executado no contêiner da API, por exemplo `docker compose exec api ...`, com o nome exato definido na tarefa 10) que: pede o login da dona; gera uma senha temporária e a mostra **uma única vez** no terminal; marca "troca obrigatória"; **revoga todas as sessões** do usuário; **limpa o bloqueio por tentativas**; e grava a ação na auditoria como feita "pelo servidor". O procedimento passo a passo é escrito em `docs/` pela tarefa 10 e referenciado na tela de login do Corona Gestão pelo texto do glossário ("Se esqueceu a senha, fale com o suporte do sistema.").
- A dona nunca precisa de e-mail, e a recuperação depende de quem opera o servidor.

### 9. Perfis extensíveis

- Hoje: `ADMIN` e `VENDEDOR`. O perfil é um **dado**, não um tipo fechado: tabela `perfis` (só do servidor) com chave estrangeira em `usuarios.perfil`; criar um perfil é uma linha numa migração nova.
- **Quem declara o perfil exigido é a rota.** Cada controlador ou método declara com uma anotação própria (por exemplo `@ExigePerfil("ADMIN")`), que o framework traduz em permissão. O padrão do grupo vem do prefixo (`/api/dona/**` aceita `ADMIN`; `/api/vendedor/**` aceita `VENDEDOR`; ADR-0003), e o método pode restringir mais. **Rota sem declaração de perfil é negada**, e um teste percorre todas as rotas registradas para garantir isso.
- **Acrescentar um perfil novo** (por exemplo, um gerente): uma migração que insere o perfil, a anotação nas rotas que ele pode usar (`@ExigePerfil({"ADMIN","GERENTE"})`) e uma linha no mapa "aplicativo para perfis aceitos" da configuração do servidor (item 10). Nenhuma outra classe muda.
- Permissão é sempre validada na API, não só na tela (§3).

### 10. Cabeçalhos de versão e de tipo de app

| Cabeçalho | Valor | Quem envia |
| --- | --- | --- |
| `X-App-Versao` | versão do aplicativo, três números (por exemplo `1.4.2`) | os dois apps, em **toda** chamada, inclusive o login |
| `X-App-Tipo` | `gestao` (Corona Gestão) ou `vendas` (Corona Vendas) | os dois apps, em toda chamada, inclusive o login |

- **Versão mínima:** a API tem uma versão mínima por tipo de app, na configuração da aplicação (`corona.app.versao-minima.gestao` e `corona.app.versao-minima.vendas`, preenchidas por variáveis de ambiente do `.env`). Compara-se número a número (maior, menor, correção). Subir o mínimo é uma mudança de configuração e uma nova subida da API.
- **Resposta:** versão abaixo do mínimo, ou cabeçalho de versão ausente ou ilegível, recebe **HTTP 426** com um corpo de erro que leva o código `APP_DESATUALIZADO` (para o programa), as versões mínima e atual (para o registro) e a mensagem do glossário, **sem código de erro para a pessoa**:
  > **Seu aplicativo está desatualizado.** Atualize para continuar usando.

  O botão "Atualizar agora" só existe com a atualização automática (Fase 9); antes, a tela mostra só o texto (glossário).
- **Exceções à verificação** (não exigem cabeçalho): o teste de saúde do servidor (`/actuator/health`) e a documentação do OpenAPI (`/v3/api-docs/**` e a tela do Swagger). O Nginx e a verificação de saúde do Docker não enviam cabeçalho de aplicativo.
- `X-App-Tipo` ausente ou diferente de `gestao`/`vendas` recebe 400 (`APP_TIPO_INVALIDO`).
- **Login com perfil incompatível:** o perfil `ADMIN` só entra no tipo `gestao` e o `VENDEDOR` só no `vendas` (mapa "aplicativo para perfis aceitos" da configuração do servidor). A comparação é feita **depois** de a senha estar correta, para não revelar que o login existe. Resposta: HTTP 403, código `APP_INCOMPATIVEL`, com as mensagens "Este é o Corona Vendas." / "Este é o Corona Gestão." do glossário. A tentativa é registrada (item 7).

> ### O cabeçalho de tipo de app é conveniência de experiência, NÃO é segurança
>
> Ele só serve para a API escolher a mensagem certa, aplicar a versão mínima do app certo e registrar de onde veio a chamada. Qualquer pessoa pode enviar qualquer valor nele. **A proteção real é a permissão por perfil em cada rota** (item 9 e ADR-0003). **Nenhum código pode depender só dele** para autorizar, filtrar dados ou liberar uma função. O teste de perfil do ADR-0003 envia chamadas com o cabeçalho "errado" e confere que a permissão é decidida pelo perfil do token.

## Consequências

### Boas
- Revogação rápida: usuário desativado e sessão revogada deixam de funcionar no próximo uso, mesmo com token de acesso ainda válido.
- Roubo de token de renovação é detectado pela rotação e pela revogação da família.
- Uma queda de conexão no meio da renovação não derruba a sessão do vendedor (janela de tolerância).
- Perfis novos sem refatoração; rota sem perfil é negada por padrão.
- O token nunca chega ao código da tela, o que reduz o estrago de uma falha de XSS.

### Ruins ou custos
- A conferência por requisição acrescenta uma consulta ao banco em cada chamada.
- A derivação do sucessor exige mais um segredo no `.env`, e quem tem esse segredo e acesso ao banco consegue recalcular tokens de renovação. Isso é aceito porque quem tem os dois já controla o servidor.
- HS256 usa o mesmo segredo para assinar e conferir: se vazar, qualquer um fabrica tokens. O `kid` e as duas chaves simultâneas permitem trocar o segredo, mas exigem disciplina.
- Vendedores atrás do mesmo IP (a mesma rede) compartilham o limite de 50 erros por IP, bem maior que o de 5 por combinação.
- Quem esquece a senha da dona sem outro admin depende de quem opera o servidor.
- Reinstalar o aplicativo gera outro identificador de aparelho (custo operacional na Fase 5).
- Reuso fora da janela exige novo login com internet; as vendas ficam salvas e o envio recomeça depois.

## Alternativas consideradas

- **RS256 (par de chaves):** útil se outro serviço conferisse tokens. Hoje só a API usa, então só traria complexidade. Descartada por ora.
- **Sessão só no servidor (identificador de sessão em cookie):** não funciona bem para o app offline-first nem para um futuro app web e de celular (§5). Descartada.
- **Token de acesso de longa duração, sem refresh:** um token roubado valeria por dias. Descartada.
- **Guardar o refresh token em `localStorage` ou no banco local:** legível por qualquer código da tela e por quem copiar o arquivo. Descartada.
- **Identificador do aparelho derivado do hardware:** rastreável e instável. Descartado.
- **Bloquear por login (qualquer IP) ou só por IP:** o primeiro deixaria qualquer pessoa trancar a conta do vendedor de fora (por isso, nesse caso, só há alerta); o segundo trancaria a loja inteira por um erro. Descartados em favor de login + IP, mais um limite maior só por IP.
- **Argon2 no lugar de BCrypt:** melhor, mas a especificação escolheu BCrypt (§5, §11).
- **Redefinição por e-mail ou perguntas secretas:** a especificação decidiu "sem e-mail" (§11). Descartada.

## Seções da especificação relacionadas

§3 (perfis), §5 (autenticação), §6.15 (um aparelho por vendedor), §9 (`configuracoes`, `dispositivos`), §10 (offline), §11 (segurança), §19 (facilidade de uso), parte 6 do glossário (mensagens padrão).

## Parâmetros técnicos e onde ficam

Aprovado em 09/11/2026: os parâmetros técnicos ficam **na configuração do servidor** (arquivo de configuração da aplicação e variáveis de ambiente do `.env`), **sem tela**; mudar um deles é mudança de configuração e nova subida da API. São eles: duração do token de acesso (15 minutos), validade do refresh (30 dias), janela de tolerância (2 minutos), validade da senha temporária (7 dias), limite de erros por IP (50 em 15 minutos), limite do alerta por login (20 por hora), custo do BCrypt (12) e versões mínimas dos apps. **A tabela `configuracoes` mantém só as chaves da §9:** tempo máximo offline (padrão 10 dias), tentativas de login antes do bloqueio (padrão 5) e tempo de bloqueio (padrão 15 minutos), além das demais chaves já listadas lá.

## Dúvidas

Nenhuma em aberto. Resolvidas na aprovação de 09/11/2026: parâmetros na configuração do servidor, validade da senha temporária (7 dias), alerta (sem bloqueio) para muitos erros num login e a janela de tolerância do refresh.

Duas escolhas ficam para a tarefa 10, sem ADR: o nome e o local do comando de servidor que redefine a senha da dona, e a mensagem de tela para "senha com mais de 72 bytes" (acrescentada ao glossário antes de ser usada, como a de "senha temporária vencida").

**Linha datada, 11/11/2026:** `sessoes` encerradas, `tokens_de_renovacao` e `tentativas_login` são apagadas de verdade depois de 90 dias (exceção à exclusão lógica; ver ADR-0002, mesma data). A contagem de erros por login e IP olha só minutos, então nada do que é apagado alimenta o bloqueio.
