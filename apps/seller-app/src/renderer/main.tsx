import '@corona/ui/estilos.css';
import './estilos.css';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { iniciarIdioma } from './idioma';

document.documentElement.dataset.app = 'vendas';
const raiz = document.getElementById('raiz');
if (raiz) {
  // Primeiro o idioma guardado neste computador (espanhol, se a pessoa nunca escolheu), depois a primeira tela.
  void iniciarIdioma(window.corona).finally(() => {
    createRoot(raiz).render(
      <StrictMode>
        <App ponte={window.corona} />
      </StrictMode>,
    );
  });
}
