import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach, beforeEach } from 'vitest';
import { definirIdioma } from '../renderer/idioma';

// O padrão do programa é o espanhol; os testes de tela começam em português (os textos que eles esperam) e os que
// testam o espanhol trocam o idioma no começo do próprio teste.
beforeEach(() => {
  definirIdioma('pt');
});

afterEach(() => {
  cleanup();
});
