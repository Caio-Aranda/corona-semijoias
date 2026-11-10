# Roteiro do teste de uso

Para quem vai sentar com a dona. **Não é para a dona ler.** O objetivo é descobrir onde o sistema atrapalha, não avaliar a dona. Critério de aceite: a §19 da especificação (sem treinamento longo e sem ajuda, ela consegue as 4 tarefas abaixo).

**Idioma da tela:** ______ (português, espanhol ou os dois; ainda pendente com a dona. Anote o que foi usado.)

## Antes de ela chegar

1. No computador de teste, rode `npm run setup` (confere o Java, o Docker e os arquivos `.env`, e sobe o banco). Resolva qualquer "ATENÇÃO" que ele mostrar.
2. Em terminais separados: `npm run api:run`, depois `npm run owner:dev` (Corona Gestão) e, se for testar o vendedor, `npm run seller:dev` (Corona Vendas).
3. A conta da dona é a do `.env` (`ADMIN_LOGIN`); na primeira entrada ela cria a senha nova. A conta de vendedor de teste é a do `DEMO_VENDEDOR_*` do `.env`.
4. Deixe a tela limpa, sem outras janelas, e ligue o gravador de tela se ela concordar.
5. Tenha à mão um produto de verdade (com código e uma foto) e o relatório que ela costuma pedir.

## Como apresentar

- Diga: "Quem está sendo testado é o sistema, não você. Não existe errado."
- Peça para ela **pensar em voz alta** ("o que você está procurando? o que esperava ver?").
- **Nunca ajude antes de ela tentar.** Se ela travar, espere. Só depois de um bom tempo, pergunte "o que você faria agora?". Se ainda assim não sair, anote que precisou de ajuda e mostre.
- Não explique a tela, não use palavras técnicas e não diga onde clicar.

## Tarefas a pedir (uma de cada vez, com as suas palavras)

Hoje o sistema está na Fase 0 (só há login e tela inicial). Peça as tarefas que já existirem e deixe as outras marcadas como "ainda não existe".

1. "Cadastre este produto, com o código e a foto." (§19, critério 1)
2. "Monte uma maleta para este vendedor e envie." (critério 2)
3. "Faça o acerto da semana desta maleta." (critério 3)
4. "Tire o relatório de vendas da semana." (critério 4)

Antes disso, se já existir: "Entre no sistema" e "crie a sua senha".

## O que observar

Para cada tarefa, anote: quanto tempo levou, **onde hesitou**, **onde errou**, **o que não entendeu** (palavra ou botão), onde precisou de ajuda, e frases que ela falou ("isto aqui o que é?"). Um silêncio longo olhando a tela já é um sinal.

## Como registrar

Cada problema vira uma entrada nova em [PROBLEMAS-DE-USO.md](PROBLEMAS-DE-USO.md), copiando o modelo do arquivo (data, tela, quem testou, o que aconteceu, gravidade, situação). Escreva o que **aconteceu**, não a sua opinião do que consertar. Use as palavras que ela usou. Ao final, pergunte: "o que foi mais difícil?" e "o que você mudaria?", e registre também.
