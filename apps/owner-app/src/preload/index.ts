import { contextBridge, ipcRenderer } from 'electron';
import { CANAIS, type AvisoDeSessao, type PonteDoCorona } from '../shared/ponte';

/**
 * A ponte: pequena e fixa. A tela só recebe resultados; nenhum token passa por aqui.
 * O ouvinte da tela nunca recebe o objeto do evento do Electron, só o aviso já limpo.
 */
const ponte: PonteDoCorona = {
  entrar: (dados) => ipcRenderer.invoke(CANAIS.entrar, dados),
  trocarSenha: (dados) => ipcRenderer.invoke(CANAIS.trocarSenha, dados),
  sair: () => ipcRenderer.invoke(CANAIS.sair),
  estouLogado: () => ipcRenderer.invoke(CANAIS.estouLogado),
  chamarApi: (pedido) => ipcRenderer.invoke(CANAIS.chamarApi, pedido),
  aoMudarASessao: (ouvinte) => {
    const escuta = (_evento: unknown, aviso: AvisoDeSessao): void => {
      ouvinte({
        estado: aviso.estado,
        ...(aviso.mensagem ? { mensagem: aviso.mensagem } : {}),
      });
    };
    ipcRenderer.on(CANAIS.sessaoMudou, escuta);
    return () => {
      ipcRenderer.removeListener(CANAIS.sessaoMudou, escuta);
    };
  },
};

contextBridge.exposeInMainWorld('corona', ponte);
