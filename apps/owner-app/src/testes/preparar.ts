import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';
import { definirIdioma } from '../renderer/idioma/idioma';

// Os testes de tela existentes procuram os textos em português; o programa abre em espanhol.
definirIdioma('pt');

afterEach(() => {
  cleanup();
});
