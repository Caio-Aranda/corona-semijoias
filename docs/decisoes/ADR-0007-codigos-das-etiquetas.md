# ADR-0007: Códigos das etiquetas

- **Status:** Proposto
- **Data:** 10/11/2026
- **Tarefas que implementam:** 1.5 (backend de produtos e códigos) e 1.11 (app da dona: produtos e leitura de código), da Fase 1

## Contexto

A especificação v1.5 supunha que os fornecedores usam EAN-13 e que a peça já vem etiquetada (§6, premissas 11 e 12). A dona ainda não confirmou. As informações disponíveis indicam que **a loja imprime as próprias etiquetas de joia**, com código curto e sem EAN-13, e que cada peça tem o **COD** (interno, sequencial, da loja) e o **COD Fábrica** (do fornecedor, alfanumérico). A Fase 1 cria a tabela `produto_codigos`, e a migração publicada não se edita; por isso a regra precisa estar decidida antes (§8, §9, §15).

Decisão provisória, de **padrão seguro**: aceita qualquer formato, valida o que dá para validar e não obriga nada que a loja talvez não tenha.

## Decisão

1. **Código é texto livre.** Guardado como texto, sem tirar zeros à esquerda.
2. **Único** em `produto_codigos`, **sem diferenciar maiúsculas de minúsculas** e **sem espaços nas pontas** (o servidor tira os espaços e guarda uma forma normalizada para a unicidade).
3. Cada linha de `produto_codigos` tem **tipo**: `LOJA` (COD), `FORNECEDOR` (COD Fábrica) ou `EAN13`; `fornecedor_id` opcional; e `principal`.
4. O **dígito verificador** só é validado quando o tipo é `EAN13` e o código tem 13 dígitos. EAN-13 **não é obrigatório**.
5. **Código da loja:** sequencial gerado pelo servidor quando a dona pede um novo (peça sem etiqueta). O formato do sequencial (só número, com prefixo?) fica para a tarefa 1.5, a confirmar com a dona.
6. **Mesmo código do fornecedor em fornecedores diferentes:** como o código é único, a tela avisa e a dona escolhe entre mudar o código ou vincular ao fornecedor correto.
7. A importação em massa e as fotos nomeadas pelo código usam a mesma regra.
8. Todos os códigos sincronizam para o app do vendedor (sem custo).

## Consequências

### Boas
- Funciona com a etiqueta que a loja já imprime e com EAN-13, se algum fornecedor usar.
- Nenhuma migração futura é necessária se a dona confirmar outro formato: só mudam regras de validação.

### Ruins ou custos
- Sem formato fixo, o leitor pode trazer um código truncado que passe como válido; só o tipo `EAN13` tem proteção do dígito verificador.
- Normalizar para a unicidade exige índice próprio (texto em minúsculas, sem espaços nas pontas).

## Alternativas consideradas

- **EAN-13 obrigatório (v1.5):** descartada, não bate com as etiquetas da loja.
- **Dois campos fixos em `produtos` (COD e COD Fábrica):** descartada; a especificação já exige vários códigos por produto e a tabela `produto_codigos` cobre os dois.
- **Unicidade só por fornecedor:** descartada; a leitura da peça precisa achar um único produto.

## Seções da especificação relacionadas

§6 (premissas 11 e 12), §8, §9, §15, §17 (pendências 2 e 3), §20.

## Dúvidas

Para a dona, antes da homologação do leitor (Fase 9) e, de preferência, antes da tarefa 1.11:

1. **Leitura no Bloco de Notas:** o leitor, ligado ao computador, escreve o código e dá Enter no Bloco de Notas? (confirma o modo teclado)
2. **Impressora de etiquetas:** qual modelo e que código ela imprime (texto, Code 128, QR)?
3. **Preço = custo x 7?** O preço de venda é sempre o custo vezes 7, ou a dona digita? Isso define se o cadastro calcula o preço sugerido.
4. Pedir foto ou amostra de 3 ou 4 etiquetas (da loja e de fornecedores diferentes).
5. Formato do código da loja (só número sequencial? com letras?).

Nenhuma dúvida muda a especificação sozinha.
