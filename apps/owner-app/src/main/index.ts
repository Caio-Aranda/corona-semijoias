import { app, BrowserWindow, safeStorage, session, type WebContents } from 'electron';
import { mkdir, readFile, rm, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { CANAIS, type AvisoDeSessao } from '../shared/ponte';
import { criarApiDoGestao } from './api';
import { CacheDeFotos } from './cache-de-fotos';
import { enderecoDaApi, versaoDoApp } from './configuracao';
import { GuardaDeTokens } from './guarda-de-tokens';
import { registrarPonte } from './ipc';
import { enderecoDoApp } from './seguranca';
import { ServicoDeSessao } from './servico-de-sessao';
import type { ArquivoDoToken, Cofre, Relogio } from './tipos';

const NOME_DO_APP = 'Corona Gestão';
const enderecoDeDesenvolvimento = process.env['ELECTRON_RENDERER_URL'];

app.setName(NOME_DO_APP);

let janela: BrowserWindow | null = null;

const cofre: Cofre = {
  disponivel: () => safeStorage.isAsyncEncryptionAvailable(),
  cifrar: async (texto) => new Uint8Array(await safeStorage.encryptStringAsync(texto)),
  // Ao renovar, o token é gravado de novo, então "shouldReEncrypt" já fica atendido.
  decifrar: async (dados) => (await safeStorage.decryptStringAsync(Buffer.from(dados))).result,
};

function criarArquivoDoToken(): ArquivoDoToken {
  const caminho = join(app.getPath('userData'), 'sessao', 'renovacao.bin');
  return {
    async ler() {
      try {
        return new Uint8Array(await readFile(caminho));
      } catch {
        return null;
      }
    },
    async gravar(dados) {
      await mkdir(dirname(caminho), { recursive: true });
      await writeFile(caminho, dados, { mode: 0o600 });
    },
    async apagar() {
      await rm(caminho, { force: true });
    },
  };
}

const relogio: Relogio = {
  agora: () => Date.now(),
  agendar(ms, fn) {
    const id = setTimeout(fn, ms);
    return () => clearTimeout(id);
  },
};

function avisarATela(aviso: AvisoDeSessao): void {
  if (janela && !janela.isDestroyed()) janela.webContents.send(CANAIS.sessaoMudou, aviso);
}

/** A janela só carrega a tela do próprio aplicativo e não abre nada além dela. */
function travarNavegacao(conteudo: WebContents): void {
  conteudo.setWindowOpenHandler(() => ({ action: 'deny' }));
  conteudo.on('will-navigate', (evento, url) => {
    if (!enderecoDoApp(url, enderecoDeDesenvolvimento)) evento.preventDefault();
  });
  conteudo.on('will-attach-webview', (evento) => evento.preventDefault());
}

function criarJanela(): void {
  janela = new BrowserWindow({
    width: 1200,
    height: 800,
    minWidth: 900,
    minHeight: 600,
    show: false,
    title: NOME_DO_APP,
    backgroundColor: '#FBF7F0',
    webPreferences: {
      preload: join(__dirname, '../preload/index.cjs'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      webSecurity: true,
      webviewTag: false,
      allowRunningInsecureContent: false,
      spellcheck: false,
    },
  });
  travarNavegacao(janela.webContents);
  janela.once('ready-to-show', () => janela?.show());
  janela.on('closed', () => {
    janela = null;
  });

  if (enderecoDeDesenvolvimento) void janela.loadURL(enderecoDeDesenvolvimento);
  else void janela.loadFile(join(__dirname, '../renderer/index.html'));
}

function iniciar(): void {
  // Nenhuma permissão do navegador (câmera, microfone, localização...) é concedida.
  session.defaultSession.setPermissionRequestHandler((_conteudo, _permissao, responder) => {
    responder(false);
  });
  session.defaultSession.setPermissionCheckHandler(() => false);

  // Fotos já baixadas ficam em disco (por hash, nunca mudam). Saiu da sessão, a pasta é apagada.
  const cacheDeFotos = new CacheDeFotos({
    pasta: join(app.getPath('userData'), 'fotos'),
    limiteEmBytes: 200 * 1024 * 1024,
  });
  const api = criarApiDoGestao({
    baseUrl: enderecoDaApi(process.env['MAIN_VITE_CORONA_API_URL']),
    versaoDoApp: versaoDoApp(app.getVersion()),
    cacheDeFotos,
  });
  // `servico` nasce depois da guarda, e a guarda avisa o serviço pelo `aoPerderASessao`.
  let servico: ServicoDeSessao | null = null;
  const guarda = new GuardaDeTokens({
    api,
    cofre,
    arquivo: criarArquivoDoToken(),
    relogio,
    aoPerderASessao: (aviso) => {
      if (aviso.estado === 'fora') void cacheDeFotos.limpar().catch(() => undefined);
      if (aviso.estado === 'app-desatualizado' && aviso.mensagem) {
        servico?.marcarDesatualizado(aviso.mensagem);
      }
      avisarATela(aviso);
    },
  });
  // A sessão guardada é retomada antes de a tela perguntar "estou logado?".
  servico = new ServicoDeSessao(guarda, api, guarda.restaurar(), () => cacheDeFotos.limpar());

  registrarPonte(servico, (e) => {
    const quadro = e.senderFrame;
    return (
      quadro !== null &&
      janela !== null &&
      e.sender === janela.webContents &&
      enderecoDoApp(quadro.url, enderecoDeDesenvolvimento)
    );
  });

  criarJanela();
}

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (janela) {
      if (janela.isMinimized()) janela.restore();
      janela.focus();
    }
  });
  app.on('web-contents-created', (_evento, conteudo) => {
    travarNavegacao(conteudo);
  });
  void app.whenReady().then(iniciar);
  app.on('window-all-closed', () => {
    app.quit();
  });
}
