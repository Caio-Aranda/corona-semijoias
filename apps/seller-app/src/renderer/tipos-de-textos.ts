/**
 * A forma de um dicionário de textos sem prender o valor: todo texto vira `string` e toda função mantém os mesmos
 * parâmetros. Assim o dicionário em espanhol só compila se tiver exatamente as mesmas chaves do português.
 */
export type Igual<T> = T extends string
  ? string
  : T extends (...argumentos: infer A) => infer R
    ? (...argumentos: A) => R extends string ? string : R
    : { [K in keyof T]: Igual<T[K]> };
