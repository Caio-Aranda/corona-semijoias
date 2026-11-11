# Corona Gestão (`apps/owner-app`)

Aplicativo da dona da loja. Electron + React, só funciona com internet. Usa `@corona/ui` (tema e componentes) e `@corona/api-client-dona`. **Nunca** importa o Corona Vendas (`apps/seller-app`) nem `api-client-vendedor` (ADR-0003).

## Como rodar em desenvolvimento

1. Banco e API de pé (na raiz do projeto):

   ```bash
   npm run db:up
   ```

   ```bash
   npm run api:run
   ```

   A API precisa de um `.env` completo na raiz. **Quem já tinha um `.env` de antes** precisa acrescentar `JWT_CHAVE_ATUAL_ID`, `JWT_CHAVE_ATUAL` e `SESSAO_SEGREDO_RENOVACAO` (a API não sobe sem elas e explica). Veja o `.env.example` da raiz e a seção "API" do `README.md`.

2. Abra o Corona Gestão (na raiz):

   ```bash
   npm run owner:dev
   ```

   Abre a janela do aplicativo com recarga automática. Entre com a conta da dona (a de `ADMIN_LOGIN` no `.env`). Na primeira vez a API pede para criar a senha nova.

O endereço da API fica em `MAIN_VITE_CORONA_API_URL` (padrão `http://localhost:8080`). Para mudar, copie `apps/owner-app/.env.example` para `apps/owner-app/.env`. O endereço só pode ser `http://` quando for deste computador; fora dele precisa ser `https://`. Nada nesse arquivo é segredo.

## Outros comandos

```bash
npm run owner:build
```

Gera o pacote de código em `apps/owner-app/out/` (processo principal, ponte e tela), sem instalador.

```bash
npm run owner:empacotar
```

Gera o **instalador do Windows** (`apps/owner-app/release/Corona-Gestao-Setup-<versão>.exe`, NSIS por usuário, sem assinatura). Antes, copie `apps/owner-app/.env.producao.example` para `.env.producao` e preencha o endereço do servidor (`https://IP`) e as impressões digitais do certificado (`MAIN_VITE_CORONA_API_PINS`, a atual e a reserva). O comando confere os dois **antes** de compilar e recusa endereço local ou impressão faltando. Esses valores são embutidos no pacote na compilação (`import.meta.env`) e **nunca ficam no código versionado**. O app só confia no certificado do servidor pela impressão digital da chave (`src/main/conexao-fixada.ts`, ADR-0009); para qualquer outro endereço vale a verificação normal. Ao terminar, mostra o SHA-512 do instalador.

```bash
npm run owner:publicar
```

Publica uma versão nova na VPS: gera o instalador, confere que a `version` do `package.json` é **maior** que a do servidor, envia o instalador e **só depois** o `latest.yml` (que anuncia a versão), confere o SHA-512 no servidor e guarda só os 3 instaladores mais novos. Precisa de `apps/owner-app/.env.publicar` (veja `.env.publicar.example`; acesso por chave SSH). Os computadores se atualizam sozinhos: o app confere ao abrir e a cada 2 a 6 horas (`src/main/atualizacao.ts`), baixa em segundo plano, confere o hash e instala quando a pessoa fecha o programa, com a faixa "Há uma versão nova. Ela será instalada quando você fechar o programa.". Só funciona no app instalado (em desenvolvimento não procura). Para testar o instalador neste computador: `npm run owner:empacotar -- --para-teste` (aceita endereço local; nunca entregue esse instalador).

```bash
npm test -w @corona/owner-app
```

Testes (Vitest, sem abrir janela).

## Como é por dentro

| Parte              | Pasta           | O que faz                                                                   |
| ------------------ | --------------- | --------------------------------------------------------------------------- |
| Processo principal | `src/main/`     | Cria a janela, guarda os tokens, fala com a API.                            |
| Ponte (preload)    | `src/preload/`  | A única ligação entre a tela e o processo principal. Lista fixa de funções. |
| Tela (React)       | `src/renderer/` | Telas em português da loja. Não sabe o que é token.                         |
| Contrato           | `src/shared/`   | Os nomes e formatos que a tela e o processo principal combinam.             |

**Tokens (ADR-0004, item 3).** Só o processo principal os vê. O de acesso fica na memória; o de renovação, na memória e cifrado em disco pelo cofre do sistema (`safeStorage`). Sem cofre disponível, nada é gravado em claro e a pessoa entra de novo na próxima abertura. A tela chama a ponte (`entrar`, `trocarSenha`, `sair`, `estouLogado`, `chamarApi`, `aoMudarASessao`) e recebe só resultados; as chamadas à API levam o token por conta do processo principal. A renovação é uma por vez (fila única) e acontece 1 minuto antes de o token de acesso vencer ou ao receber 401.

**Janela.** `contextIsolation` ligado, `nodeIntegration` desligado, `sandbox` ligado, sem `remote`, sem `webview`, janelas novas e navegação para fora bloqueadas, nenhuma permissão do navegador concedida, e política de conteúdo (CSP) que só deixa carregar o que o próprio aplicativo empacotou e não deixa a tela falar com a rede.

**Versão.** O `version` do `package.json` (três números) vai em `X-App-Versao`. Ele precisa ser igual ou maior que `APP_VERSAO_MINIMA_GESTAO` do `.env` da API (padrão `1.0.0`); abaixo disso a API responde que o aplicativo está desatualizado.
