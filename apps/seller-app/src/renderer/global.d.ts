/// <reference types="vite/client" />

import type { PonteDoCorona } from '../shared/ponte';

declare global {
  interface Window {
    /** A ponte com o processo principal (preload). Não leva token. */
    corona: PonteDoCorona;
  }
}
