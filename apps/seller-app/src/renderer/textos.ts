/**
 * Textos fixos que o servidor não consegue montar (por exemplo, quando nem chegamos a falar com ele).
 * Vêm do glossário da interface (docs/glossario-interface.md, partes 4 e 6).
 * Mensagens de erro da API nunca são escritas aqui: a tela mostra o que o servidor devolveu.
 */
export const TEXTOS = {
  semInternet: {
    titulo: 'Sem internet.',
    texto: 'Para entrar no Corona Vendas é preciso internet. Conecte-se e tente de novo.',
  },
  algoDeuErrado:
    'Algo deu errado agora. Tente de novo. Se continuar, fale com o suporte do sistema.',
  sessaoTerminou: {
    titulo: 'Sua sessão terminou.',
    texto: 'Entre de novo com seu usuário e sua senha para continuar.',
  },
  desatualizado: {
    titulo: 'Seu aplicativo está desatualizado.',
    texto: 'Atualize para continuar usando.',
  },
  senhaNova: {
    titulo: 'Crie sua senha nova.',
    texto:
      'A senha que você recebeu é temporária. Para continuar, crie uma senha nova que só você saiba.',
    regrasTitulo: 'Sua senha nova precisa ter:',
    regras: ['pelo menos 8 caracteres;', 'letras e números;', 'uma senha diferente da temporária.'],
    naoEstaBoa:
      'A senha nova ainda não está boa. Ela precisa ter pelo menos 8 caracteres, com letras e números.',
    naoSaoIguais: 'As duas senhas não são iguais. Digite a mesma senha nos dois campos.',
  },
} as const;
