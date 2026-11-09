# ADR-0003: Separação entre o Corona Gestão e o Corona Vendas

- **Status:** Aceito
- **Data:** 09/11/2026
- **Tarefas que implementam:** 4 (regras de lint do monorepo), 6 (grupos do OpenAPI e pacotes de DTOs), 13 (clientes gerados), 14 (`packages/ui`), 15 (Corona Gestão), 16 (Corona Vendas e a barreira)

## Contexto

O notebook do vendedor pode ser perdido ou roubado, e o vendedor é pessoa externa à loja. Por isso **o código exclusivo da dona não pode ir dentro do instalador do vendedor**, e esconder telas não basta (§4). O app do vendedor nunca recebe preço de custo nem dados de outros vendedores (CLAUDE.md, §7.5, §10, §11), e nunca importa código da dona (§14). Os dois apps compartilham `packages/`. Os nomes dos aplicativos são **Corona Gestão** (app da dona, pasta `apps/owner-app`) e **Corona Vendas** (app do vendedor, pasta `apps/seller-app`), com ícones diferentes (§18).

A separação precisa valer em **todas as camadas ao mesmo tempo**, porque uma única falha (um DTO que devolve o custo, um import errado) já vaza o dado.

## Decisão

### 1. Dois grupos de rotas e dois documentos OpenAPI

- `/api/dona/**`: rotas da dona, perfil `ADMIN`. Documento OpenAPI do grupo `dona`.
- `/api/vendedor/**`: rotas do vendedor, perfil `VENDEDOR`. Documento OpenAPI do grupo `vendedor`.
- `/api/auth/**`: login, renovação de sessão, saída e troca de senha. **Aparece nos dois grupos** (os dois apps precisam entrar).
- Qualquer outra rota é negada por padrão (a configuração de segurança termina com "negar tudo").
- Implementação: um agrupamento por prefixo de rota no springdoc (`GroupedOpenApi`) por grupo, cada um com seu documento (`/v3/api-docs/dona` e `/v3/api-docs/vendedor`). A permissão por perfil vem do ADR-0004 e não depende do documento nem do cabeçalho de tipo de app.

### 2. DTOs separados por grupo

- Cada grupo tem os próprios DTOs, em pacotes separados e sem herança entre eles. O nome base dos pacotes é definido na tarefa 6; a regra é uma só: **o pacote do grupo vendedor nunca depende do pacote do grupo dona, nem o contrário**. O que os dois usam fica num pacote comum de tipos neutros (por exemplo, o texto decimal de dinheiro do ADR-0002).
- **Nenhum DTO do grupo vendedor tem `preco_custo` (nem `precoCusto`, nem dados de outro vendedor).** O DTO de produto do vendedor é escrito à mão, campo a campo, sem herdar do DTO da dona e sem o mapeamento automático de toda a entidade.
- **O custo do produto mora em `produtos_custos`** (ADR-0002), tabela só do servidor, sem `sync_seq`, fora do pull e fora de qualquer rota do grupo vendedor. Assim o custo não pode vazar nem por um DTO mal escrito nem por um `SELECT *`: ele simplesmente não está na tabela que o vendedor lê. Só os DTOs do grupo dona leem `produtos_custos`.
- O filtro "somente do vendedor" é aplicado **sempre a partir do vendedor do token**, nunca de parâmetro da requisição. Rota do vendedor não recebe `vendedor_id`.
- O pull de sincronização (Fase 5) usa lista explícita de colunas por tabela.

**Como será verificado (testes da tarefa 6, mantidos pelas tarefas seguintes):**

1. **Teste de arquitetura** (ArchUnit): o pacote de DTOs e controladores do vendedor não depende do da dona, e o inverso; nenhum DTO do vendedor tem campo com nome de custo.
2. **Teste do documento OpenAPI**: gera o documento do grupo `vendedor` e falha se ele contiver `preco_custo`, `precoCusto`, `custo`, o prefixo `/api/dona` ou qualquer esquema do pacote da dona. Roda no CI a cada Pull Request.
3. **Teste de integração com dois vendedores** no banco: cada rota do vendedor, chamada com o token de cada um, só devolve registros do dono do token; chamada com o token de um para o dado do outro responde "não encontrado" (sem confirmar que o dado existe). Inclui o sync quando ele existir.
4. **Teste de perfil**: o token de `VENDEDOR` é recusado em toda rota de `/api/dona/**` e o de `ADMIN` em toda rota de `/api/vendedor/**`. O teste percorre as rotas registradas, então uma rota nova sem regra falha sozinha.

### 3. Dois clientes gerados

- `packages/api-client-dona`: gerado do documento do grupo `dona` (inclui `/api/auth/**`).
- `packages/api-client-vendedor`: gerado do documento do grupo `vendedor` (inclui `/api/auth/**`).
- O gerador é o do ADR-0001 (@hey-api/openapi-ts, versão fixa). A tarefa 13 cria os dois pacotes e o comando de geração; o código gerado é versionado, e o CI regenera e falha se houver diferença.
- **O Corona Vendas depende só de `api-client-vendedor`.** O Corona Gestão depende só de `api-client-dona`.

### 4. Barreira em três camadas

| Camada | O que faz | Onde |
| --- | --- | --- |
| **1. Lint de importação** | Regra de importações restritas no Corona Vendas: proibido importar `api-client-dona`, qualquer coisa de `apps/owner-app` e qualquer caminho que saia da pasta do app por `../`. A mesma regra vale para `packages/ui` e `packages/shared`, que não podem importar nenhum `api-client-*`. O `package.json` do Corona Vendas não lista `api-client-dona` | Tarefas 4 (regra base) e 16 (regra do Corona Vendas) |
| **2. Verificação no CI do pacote final** | Depois de construir o Corona Vendas, o CI desempacota o pacote real (a pasta `app.asar` gerada pelo electron-builder) e procura marcadores proibidos: `/api/dona`, `preco_custo`, `precoCusto`, `api-client-dona`, `owner-app`, `Corona Gestão`. Achou qualquer um, o CI falha. Os mapas de código (source maps) são desligados no pacote de produção, para não carregar o código-fonte original | Tarefa 16 |
| **3. `packages/shared` sem nada exclusivo da dona** | `packages/shared` guarda só o que serve aos dois apps (validações de CPF e EAN-13, formatadores, dinheiro do ADR-0002). A mesma varredura de marcadores roda sobre `packages/shared` e `packages/ui`; textos e telas exclusivas da dona ficam em `apps/owner-app` | Tarefas 4, 14 e 16 |

A lista de marcadores fica em **um único arquivo** versionado, lido pelo lint e pelo CI. Acrescentar uma palavra proibida é uma linha. Por que três camadas: o lint pega o erro cedo, mas só olha o código-fonte; a verificação do pacote final pega o que o lint não vê (dependência indireta, texto copiado, arquivo gerado); e a regra do `packages/shared` impede que um código da dona chegue ao pacote por um caminho compartilhado.

### 5. Dois builds, dois instaladores

- `apps/owner-app` produz o instalador **Corona Gestão** e `apps/seller-app` produz o **Corona Vendas**, cada um com configuração de build, `productName`, identificador de aplicativo, nome do instalador e ícone próprios (§18). O ícone dos dois é diferente.
- Dois canais de atualização automática separados (cada um atualiza só o seu aplicativo).
- Um build nunca inclui o outro: não existe um único pacote com os dois e um "modo". A tarefa 15 cria o primeiro build e a 16 o segundo.
- Os dois apps compartilham `packages/ui` (tema, componentes) e `packages/shared`, e nada mais.

## Consequências

### Boas
- Um vazamento exige falhar em várias camadas independentes, e cada camada tem teste automático que roda em todo Pull Request.
- O vendedor não tem como obter o custo nem o que é da dona, mesmo com acesso total ao próprio notebook e ao instalador.
- Um cliente por app deixa claro, a quem escreve a tela, o que cada app pode chamar.

### Ruins ou custos
- DTOs quase duplicados entre os grupos: mais código e mais mapeamentos para manter.
- Dois documentos OpenAPI, dois clientes gerados e dois pipelines de build: mais tempo de CI.
- O gerador é 0.x (ADR-0001); a geração do cliente do vendedor pode mudar de formato entre versões.
- A verificação por marcadores é textual: não pega um vazamento sem nenhuma palavra da lista (por exemplo, um campo com outro nome que contenha o custo). Os testes de DTO e de integração existem para cobrir isso.
- O `packages/shared` precisa de disciplina: coisa que só a dona usa não entra nele, mesmo que a duplicação incomode.

## Alternativas consideradas

- **Um único aplicativo com "modo dona" e "modo vendedor", escondendo telas:** o código da dona iria no instalador do vendedor. Contra a §4. Descartada.
- **Um único documento OpenAPI e um único cliente:** o cliente do vendedor teria os tipos e as rotas da dona. Descartada.
- **Separar pelo cabeçalho de tipo de app em vez de por rota:** o cabeçalho é só conveniência (ADR-0004). Descartada como proteção.
- **Verificar só pelo lint:** não vê dependência indireta nem arquivo gerado. Descartada como única camada.
- **Dois repositórios:** impediria o compartilhamento seguro de `packages/` e o CI único; a §14 escolheu monorepo. Descartada.
- **Um subdomínio e uma API por app:** dobra a operação numa VPS pequena sem ganho de proteção sobre as permissões por perfil. Descartada.

## Seções da especificação relacionadas

§3 (perfis), §4 (os dois sistemas), §5, §7.5 e §11 (custo e dados de outros vendedores), §10 (o que cada vendedor sincroniza), §14 (monorepo e `packages/`), §15 (Fase 0), §18 (nomes dos apps).

## Dúvidas

Nenhuma em aberto. Duas escolhas ficam para a tarefa que implementa, sem ADR:

- o nome base dos pacotes Java dos dois grupos (tarefa 6; o ADR só fixa a regra de dependência);
- o nome e o formato do arquivo único com a lista de marcadores proibidos (tarefa 4).

O custo do produto em tabela só do servidor, que era dúvida, foi aprovado em 09/11/2026 (ADR-0002).
