import '@corona/ui/estilos.css';
import './estilos.css';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';

document.documentElement.dataset.app = 'vendas';
const raiz = document.getElementById('raiz');
if (raiz) {
  createRoot(raiz).render(
    <StrictMode>
      <App ponte={window.corona} />
    </StrictMode>,
  );
}
