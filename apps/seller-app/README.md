# Corona Vendas (`apps/seller-app`)

Aplicativo do vendedor. Electron + React. Nesta etapa (Fase 0) só entra **com internet**; o modo sem internet vem na Fase 5. Usa `@corona/ui` (tema e componentes) e `@corona/api-client-vendedor`. **Nunca** importa o Corona Gestão (`apps/owner-app`) nem `api-client-dona` (ADR-0003); a barreira abaixo garante isso.

## Como rodar em desenvolvimento

1. Banco e API de pé (na raiz do projeto): `npm run db:up` e `npm run api:run`. A API precisa de um `.env` completo na raiz (veja o `.env.example` da raiz).
2. Abra o Corona Vendas (na raiz):

   ```bash
   npm run seller:dev
   ```

   Entre com um usuário **vendedor** (a dona o cria; na primeira vez a API pede para criar a senha nova). Com a conta da dona, a API responde "Este é o Corona Vendas." e a tela mostra essa mensagem.

O endereço da API fica em `MAIN_VITE_CORONA_API_URL` (padrão `http://localhost:8080`; copie `apps/seller-app/.env.example` para `.env` para mudar). `http://` só vale para este computador; fora dele precisa ser `https://`.

## Outros comandos

```bash
npm run seller:build
```

Gera o pacote de código em `apps/seller-app/out/`, sem mapas de código.

```bash
npm run seller:empacotar
```

Empacota de verdade com o electron-builder (`--dir`, sem instalador): gera `apps/seller-app/release/<sistema>-unpacked/resources/app.asar`. Usa o Electron que o npm já baixou. Instalador, ícone e atualização automática ficam para a Fase 9.

```bash
npm run barreira:varrer -- apps/seller-app/release/win-unpacked/resources/app.asar
```

Desempacota o `app.asar` e procura os marcadores proibidos (camada 2 abaixo). O CI faz o mesmo em todo Pull Request.

```bash
npm test -w @corona/seller-app
```

Testes (Vitest, sem abrir janela).

## Como é por dentro

| Parte              | Pasta           | O que faz                                                                      |
| ------------------ | --------------- | ------------------------------------------------------------------------------ |
| Processo principal | `src/main/`     | Cria a janela, guarda os tokens e o identificador do aparelho, fala com a API. |
| Ponte (preload)    | `src/preload/`  | A única ligação entre a tela e o processo principal. Lista fixa de funções.    |
| Tela (React)       | `src/renderer/` | Telas em português da loja. Não sabe o que é token nem aparelho.               |
| Contrato           | `src/shared/`   | Os nomes e formatos que a tela e o processo principal combinam.                |
| Barreira           | `src/barreira/` | Testes que provam que a regra de importação e a varredura do pacote pegam.     |

Tokens, janela e versão funcionam como no Corona Gestão (ADR-0004, itens 2, 3 e 10), com estas diferenças:

- **Tipo de app:** `X-App-Tipo: vendas`. A versão do `package.json` (três números) vai em `X-App-Versao` e precisa ser igual ou maior que `APP_VERSAO_MINIMA_VENDAS` da API (padrão `1.0.0`).
- **Identificador do aparelho (ADR-0004, item 4):** um UUID v4 criado na primeira abertura e guardado em `<pasta de dados>/aparelho/identificador.txt`, só no processo principal. Vai no corpo do login e da renovação (`dispositivoId`), nunca na tela, e não é segredo. Reinstalar cria outro.
- **Prazo offline (item 5):** o login e a renovação trazem a hora do servidor e o prazo máximo sem internet; o processo principal os guarda em `aparelho/ultimo-contato.json`. **Só guarda:** a regra que bloqueia vendas novas é da Fase 5.

## A barreira contra código da dona (ADR-0003)

O pacote do vendedor não pode carregar nada da dona (o notebook pode ser perdido ou roubado). Três camadas, todas lendo a lista única de `scripts/marcadores-proibidos.json`:

1. **Lint** (`npm run lint`): neste app é proibido importar `api-client-dona`, qualquer coisa de `owner-app` e qualquer caminho que saia desta pasta por `../` (regra em `scripts/barreira/`). Em `packages/ui` e `packages/shared` é proibido importar qualquer `api-client-*` e qualquer app.
2. **CI, pacote final:** depois de construir, o CI empacota o Corona Vendas, desempacota o `app.asar` e falha se achar `/api/dona`, `preco_custo`, `precoCusto`, `api-client-dona`, `owner-app` ou `Corona Gestão`. Os mapas de código ficam desligados.
3. **`packages/shared` e `packages/ui`:** a mesma varredura roda sobre eles.

Para acrescentar uma palavra proibida, é uma linha em `scripts/marcadores-proibidos.json`.
